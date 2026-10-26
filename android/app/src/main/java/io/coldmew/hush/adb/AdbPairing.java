package io.coldmew.hush.adb;

import android.os.Build;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;

// Pairs Hush's key with Wireless debugging using the six-digit code ("Pair device with pairing
// code"), the same protocol as `adb pair` (AOSP adb/pairing_connection, pairing_auth):
//  1. TLS 1.3 to the pairing port, Hush's certificate as the client certificate;
//  2. SPAKE2 with the code plus 64 bytes exported from the TLS session (so the exchange is bound to
//     this connection), names "adb pair client" / "adb pair server";
//  3. HKDF-SHA256 of the SPAKE2 key gives an AES-128-GCM key; each side sends its peer info (Hush's
//     public key in adb format) encrypted with it. A wrong code shows up as a failed decryption.
public final class AdbPairing {
    private static final byte VERSION = 1, TYPE_SPAKE2 = 0, TYPE_PEER_INFO = 1, PEER_RSA_PUB_KEY = 0;
    private static final int PEER_INFO_SIZE = 8192, MAX_PAYLOAD = PEER_INFO_SIZE * 2;
    private static final String EXPORT_LABEL = "adb-label\u0000";
    private static final int EXPORT_SIZE = 64;
    private static final byte[] CLIENT = "adb pair client\u0000".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SERVER = "adb pair server\u0000".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HKDF_INFO = "adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.UTF_8);

    // The code did not match (or the pairing dialog was closed meanwhile).
    public static final class WrongCodeException extends IOException {
        WrongCodeException() { super("wrong pairing code"); }
    }

    private AdbPairing() {}

    // Blocks for a second or two. name is shown in Settings under "Paired devices".
    public static void pair(AdbKey key, String host, int port, String code, String name) throws IOException {
        if (Build.VERSION.SDK_INT < 30) throw new IOException("Wireless debugging needs Android 11");
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(host, port), 5000);
            raw.setTcpNoDelay(true);
            raw.setSoTimeout(10000);
            SSLSocket tls = (SSLSocket) key.sslContext().getSocketFactory().createSocket(raw, host, port, true);
            tls.startHandshake();
            DataInputStream in = new DataInputStream(tls.getInputStream());
            DataOutputStream out = new DataOutputStream(tls.getOutputStream());

            byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
            byte[] exported = exportKeyingMaterial(tls);
            byte[] password = Arrays.copyOf(codeBytes, codeBytes.length + exported.length);
            System.arraycopy(exported, 0, password, codeBytes.length, exported.length);

            Spake2 spake = new Spake2(Spake2.Role.ALICE, CLIENT, SERVER);
            byte[] ours = spake.generateMessage(password);
            write(out, TYPE_SPAKE2, ours);
            byte[] theirs = read(in, TYPE_SPAKE2);
            byte[] secret = spake.processMessage(theirs);
            byte[] aesKey = hkdf(secret, HKDF_INFO, 16);

            ByteBuffer info = ByteBuffer.allocate(PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN);
            info.put(PEER_RSA_PUB_KEY);
            byte[] pub = key.adbPublicKey(name);
            info.put(pub, 0, Math.min(pub.length, PEER_INFO_SIZE - 1));
            write(out, TYPE_PEER_INFO, gcm(Cipher.ENCRYPT_MODE, aesKey, 0, info.array()));
            byte[] reply = read(in, TYPE_PEER_INFO);
            byte[] theirInfo;
            try {
                theirInfo = gcm(Cipher.DECRYPT_MODE, aesKey, 0, reply);
            } catch (GeneralSecurityException e) {
                throw new WrongCodeException();
            }
            if (theirInfo.length != PEER_INFO_SIZE) throw new IOException("unexpected peer info size " + theirInfo.length);
        } catch (GeneralSecurityException e) {
            throw new IOException("pairing crypto failed: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {   // their SPAKE2 message was not a valid point
            throw new WrongCodeException();
        }
    }

    // RFC 5705 keying-material exporter of the TLS session: a public API since Android 12, Conscrypt's
    // own (hidden) one on Android 11.
    static byte[] exportKeyingMaterial(SSLSocket s) throws IOException {
        if (Build.VERSION.SDK_INT >= 31) return android.net.ssl.SSLSockets.exportKeyingMaterial(s, EXPORT_LABEL, null, EXPORT_SIZE);
        try {
            return (byte[]) Class.forName("com.android.org.conscrypt.Conscrypt")
                    .getMethod("exportKeyingMaterial", SSLSocket.class, String.class, byte[].class, int.class)
                    .invoke(null, s, EXPORT_LABEL, null, EXPORT_SIZE);
        } catch (ReflectiveOperationException e) {
            throw new IOException("TLS key export not available on this Android version", e);
        }
    }

    // HKDF-SHA256 (RFC 5869) with no salt (= a zero-filled salt of hash length).
    static byte[] hkdf(byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int done = 0;
        for (int i = 1; done < length; i++) {
            mac.update(t);
            mac.update(info);
            mac.update((byte) i);
            t = mac.doFinal();
            int n = Math.min(t.length, length - done);
            System.arraycopy(t, 0, out, done, n);
            done += n;
        }
        return out;
    }

    // AES-128-GCM with adb's nonce: the message counter, little-endian, in a 12-byte IV. Each side
    // sends exactly one encrypted message, so the counter is 0 in both directions.
    static byte[] gcm(int mode, byte[] key, long counter, byte[] in) throws GeneralSecurityException {
        byte[] iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return c.doFinal(in);
    }

    private static void write(DataOutputStream out, byte type, byte[] payload) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN);
        h.put(VERSION).put(type).putInt(payload.length);
        out.write(h.array());
        out.write(payload);
        out.flush();
    }

    private static byte[] read(DataInputStream in, byte type) throws IOException {
        byte[] h = new byte[6];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN);
        byte version = b.get(), t = b.get();
        int size = b.getInt();
        if (version != VERSION) throw new IOException("pairing version " + version);
        if (t != type) throw new IOException("unexpected pairing packet " + t);
        if (size <= 0 || size > MAX_PAYLOAD) throw new IOException("bad pairing payload size " + size);
        byte[] p = new byte[size];
        in.readFully(p);
        return p;
    }
}
