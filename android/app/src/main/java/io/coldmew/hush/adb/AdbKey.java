package io.coldmew.hush.adb;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

// Hush's identity towards Wireless debugging: an RSA-2048 key, paired once. The private key is kept
// in app-private storage, encrypted with an AES key that never leaves Android's Keystore (the same
// approach Shizuku uses; a software RSA key works with every TLS stack). TLS presents it inside a
// self-signed certificate; adbd only checks that the certificate's public key is a paired one.
public final class AdbKey {
    private static final String TAG = "Hush";
    private static final String KEYSTORE = "AndroidKeyStore", WRAP_ALIAS = "hush_adb_key_wrap";
    private static final String PREFS = "adb", PREF_KEY = "key";
    private static final byte[] AAD = "hush-adbkey".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_SIZE = 12, TAG_BITS = 128;

    private static AdbKey instance;

    final PrivateKey privateKey;
    final RSAPublicKey publicKey;
    final X509Certificate certificate;
    private SSLContext ssl;

    private AdbKey(PrivateKey priv, RSAPublicKey pub) throws GeneralSecurityException {
        privateKey = priv;
        publicKey = pub;
        certificate = selfSigned(priv, pub);
    }

    // Loads the stored key, or makes and stores a new one (which then has to be paired).
    public static synchronized AdbKey get(Context c) throws GeneralSecurityException {
        if (instance != null) return instance;
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Key wrap = wrappingKey();
        String stored = p.getString(PREF_KEY, null);
        if (stored != null) {
            try {
                byte[] blob = Base64.decode(stored, Base64.NO_WRAP);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, wrap, new GCMParameterSpec(TAG_BITS, blob, 0, IV_SIZE));
                cipher.updateAAD(AAD);
                byte[] pkcs8 = cipher.doFinal(blob, IV_SIZE, blob.length - IV_SIZE);
                RSAPrivateCrtKey priv = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                RSAPublicKey pub = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
                return instance = new AdbKey(priv, pub);
            } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
                Log.w(TAG, "stored adb key unusable, making a new one: " + e.getClass().getSimpleName());
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
        KeyPair kp = g.generateKeyPair();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrap);
        cipher.updateAAD(AAD);
        byte[] ct = cipher.doFinal(kp.getPrivate().getEncoded());
        byte[] blob = new byte[IV_SIZE + ct.length];
        System.arraycopy(cipher.getIV(), 0, blob, 0, IV_SIZE);
        System.arraycopy(ct, 0, blob, IV_SIZE, ct.length);
        p.edit().putString(PREF_KEY, Base64.encodeToString(blob, Base64.NO_WRAP)).commit();
        return instance = new AdbKey(kp.getPrivate(), (RSAPublicKey) kp.getPublic());
    }

    private static Key wrappingKey() throws GeneralSecurityException {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            Key k = ks.getKey(WRAP_ALIAS, null);
            if (k != null) return k;
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        g.init(new KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    // ---- adb public key format ----

    // The key in adb's own format (android_pubkey.c), base64, followed by " name" and a NUL: what
    // pairing sends and what Settings lists under "Paired devices".
    public byte[] adbPublicKey(String name) {
        final int words = 2048 / 32;
        BigInteger n = publicKey.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        putLittleEndian(b, n, 256);
        putLittleEndian(b, rr, 256);
        b.putInt(publicKey.getPublicExponent().intValue());
        byte[] base64 = Base64.encode(b.array(), Base64.NO_WRAP);
        byte[] suffix = (" " + name + "\u0000").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[base64.length + suffix.length];
        System.arraycopy(base64, 0, out, 0, base64.length);
        System.arraycopy(suffix, 0, out, base64.length, suffix.length);
        return out;
    }

    private static void putLittleEndian(ByteBuffer b, BigInteger v, int size) {
        byte[] be = v.toByteArray();   // big-endian, maybe with a leading 0 byte
        for (int i = 0; i < size; i++) {
            int j = be.length - 1 - i;
            b.put(j >= 0 ? be[j] : 0);
        }
    }

    // ---- TLS ----

    // TLS 1.3 with this key as the client certificate. adbd's own certificate is self-signed and the
    // connection goes to this phone itself (127.0.0.1); pairing is protected by SPAKE2, bound to
    // this very TLS session (see AdbPairing).
    @SuppressLint({"CustomX509TrustManager", "TrustAllX509TrustManager"})
    synchronized SSLContext sslContext() throws GeneralSecurityException {
        if (ssl != null) return ssl;
        X509ExtendedKeyManager km = new X509ExtendedKeyManager() {
            @Override public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket s) {
                for (String t : keyTypes) if ("RSA".equals(t)) return "key";
                return null;
            }
            @Override public X509Certificate[] getCertificateChain(String alias) { return "key".equals(alias) ? new X509Certificate[]{certificate} : null; }
            @Override public PrivateKey getPrivateKey(String alias) { return "key".equals(alias) ? privateKey : null; }
            @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return null; }
            @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return null; }
            @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket s) { return null; }
        };
        X509ExtendedTrustManager tm = new X509ExtendedTrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket s) {}
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine e) {}
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket s) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine e) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(new KeyManager[]{km}, new TrustManager[]{tm}, new SecureRandom());
        return ssl = ctx;
    }

    // ---- self-signed certificate (minimal X.509 v3, DER) ----

    private static final byte[] OID_SHA256_RSA = {0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b};
    private static final byte[] OID_CN = {0x55, 0x04, 0x03};

    static X509Certificate selfSigned(PrivateKey priv, RSAPublicKey pub) throws GeneralSecurityException {
        byte[] algId = der(0x30, der(0x06, OID_SHA256_RSA), der(0x05));
        byte[] name = der(0x30, der(0x31, der(0x30, der(0x06, OID_CN), der(0x0c, "00".getBytes(StandardCharsets.US_ASCII)))));
        byte[] validity = der(0x30,
                der(0x17, "200101000000Z".getBytes(StandardCharsets.US_ASCII)),     // UTCTime
                der(0x18, "20991231235959Z".getBytes(StandardCharsets.US_ASCII)));  // GeneralizedTime
        byte[] tbs = der(0x30,
                der(0xa0, der(0x02, new byte[]{2})),   // version v3
                der(0x02, new byte[]{1}),             // serial number
                algId, name, validity, name,
                pub.getEncoded());                    // SubjectPublicKeyInfo
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(priv);
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];       // BIT STRING: 0 unused bits
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] cert = der(0x30, tbs, algId, der(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
    }

    // One DER element: tag, length, and the concatenated contents.
    static byte[] der(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        int n = body.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (n < 0x80) out.write(n);
        else if (n < 0x100) { out.write(0x81); out.write(n); }
        else if (n < 0x10000) { out.write(0x82); out.write(n >> 8); out.write(n & 0xff); }
        else { out.write(0x83); out.write(n >> 16); out.write((n >> 8) & 0xff); out.write(n & 0xff); }
        byte[] b = body.toByteArray();
        out.write(b, 0, b.length);
        return out.toByteArray();
    }
}
