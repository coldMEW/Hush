package io.coldmew.hush.adb;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

// The smallest adb client Hush needs: connect to Wireless debugging with the paired key and run one
// shell command (the one that starts Hush's helper). Protocol: AOSP adb/protocol.txt; Wireless
// debugging always upgrades to TLS 1.3 (STLS) right after CNXN, and the paired client certificate
// is the authentication.
public final class AdbConnection implements Closeable {
    private static final int A_CNXN = 0x4e584e43, A_OPEN = 0x4e45504f, A_OKAY = 0x59414b4f,
            A_CLSE = 0x45534c43, A_WRTE = 0x45545257, A_STLS = 0x534c5453, A_AUTH = 0x48545541;
    private static final int VERSION = 0x01000001, STLS_VERSION = 0x01000000, MAX_DATA = 1024 * 1024;
    private static final int LOCAL_ID = 1;

    // The key is not (or no longer) paired: pairing is needed again.
    public static final class NotPairedException extends IOException {
        NotPairedException(Throwable cause) { super("this key is not paired with Wireless debugging", cause); }
    }

    private final Socket raw;
    private InputStream in;
    private OutputStream out;

    private AdbConnection(Socket raw) { this.raw = raw; }

    public static AdbConnection open(AdbKey key, String host, int port) throws IOException {
        Socket raw = new Socket();
        AdbConnection c = new AdbConnection(raw);
        try {
            raw.connect(new InetSocketAddress(host, port), 5000);
            raw.setTcpNoDelay(true);
            raw.setSoTimeout(10000);
            c.in = raw.getInputStream();
            c.out = raw.getOutputStream();
            c.send(A_CNXN, VERSION, MAX_DATA, "host::\u0000".getBytes(StandardCharsets.UTF_8));
            Message m = c.read();
            if (m.command == A_STLS) {
                c.send(A_STLS, STLS_VERSION, 0, null);
                SSLSocket tls = (SSLSocket) key.sslContext().getSocketFactory().createSocket(raw, host, port, true);
                try {
                    tls.startHandshake();
                } catch (SSLException e) {
                    throw new NotPairedException(e);   // adbd rejects an unknown client certificate here
                }
                c.in = tls.getInputStream();
                c.out = tls.getOutputStream();
                // In TLS 1.3 the server checks the client certificate after the client's side of the
                // handshake is done, so an unknown key shows up here, as an alert or a closed socket.
                try {
                    m = c.read();
                } catch (SSLException | java.io.EOFException e) {
                    throw new NotPairedException(e);
                }
            }
            if (m.command == A_AUTH) throw new NotPairedException(null);   // legacy RSA auth: not paired over TLS
            if (m.command != A_CNXN) throw new IOException("unexpected adb reply " + Integer.toHexString(m.command));
            return c;
        } catch (GeneralSecurityException e) {
            c.close();
            throw new IOException("TLS setup failed", e);
        } catch (IOException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    // Runs `cmd` through adb's shell service and returns what it printed. Returns when the command
    // ends (a command that starts something in the background with & returns at once).
    public String shell(String cmd) throws IOException {
        send(A_OPEN, LOCAL_ID, 0, ("shell:" + cmd + "\u0000").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Message m = read();
            if (m.command == A_OKAY) {
                remote = m.arg0;
            } else if (m.command == A_WRTE) {
                if (m.payload != null) output.write(m.payload, 0, m.payload.length);
                send(A_OKAY, LOCAL_ID, m.arg0, null);
            } else if (m.command == A_CLSE) {
                if (remote == 0) throw new IOException("shell service refused");
                send(A_CLSE, LOCAL_ID, m.arg0, null);
                return output.toString("UTF-8");
            }
        }
    }

    @Override public void close() {
        try { raw.close(); } catch (IOException ignored) {}
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        ByteBuffer b = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        if (data != null) for (byte x : data) sum += x & 0xff;
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~command);
        if (data != null) b.put(data);
        out.write(b.array());
        out.flush();
    }

    private static final class Message {
        int command, arg0;
        byte[] payload;
    }

    private Message read() throws IOException {
        byte[] h = readFully(24);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = b.getInt();
        m.arg0 = b.getInt();
        b.getInt();   // arg1
        int len = b.getInt();
        b.getInt();   // checksum (not used from version 0x01000001 on)
        int magic = b.getInt();
        if (magic != ~m.command) throw new IOException("corrupt adb header");
        if (len < 0 || len > MAX_DATA) throw new IOException("bad adb payload size " + len);
        if (len > 0) m.payload = readFully(len);
        if (io.coldmew.hush.BuildConfig.DEBUG) android.util.Log.d("Hush", "adb < " + Integer.toHexString(m.command) + " arg0=" + m.arg0 + " len=" + len);
        return m;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int done = 0;
        while (done < n) {
            int r = in.read(buf, done, n - done);
            if (r < 0) throw new java.io.EOFException("adb connection closed");
            done += r;
        }
        return buf;
    }
}
