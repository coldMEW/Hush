package io.coldmew.hush.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;
import java.security.SecureRandom;
import java.util.concurrent.CopyOnWriteArrayList;

// App-side end of Hush's privileged helper (the uid-2000 process started from Hush's own APK over
// Wireless debugging). Once connected it does what Shizuku did for Hush, but it answers only Hush
// and runs only the commands in Commands.
//
// Lifecycle: Hush stores a random token and starts the helper with it (HelperStarter); the helper
// hands its Binder to HelperProvider, which calls onHello() here. The token is kept in private
// storage, so the helper can hand its Binder over again when Android restarts Hush's process.
public final class HushHelper {
    static final String TAG = "Hush";
    private static final String PREFS = "helper", KEY_TOKEN = "token";

    private static volatile IBinder binder;          // the helper's service binder
    private static final IBinder liveness = new android.os.Binder();   // helper watches this to notice Hush restart
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public interface Listener {
        // connected: the helper just connected (true) or died (false).
        void onHelperChanged(boolean connected);
    }

    private HushHelper() {}

    // ---- connection state ----

    public static boolean ready() {
        IBinder b = binder;
        return b != null && b.pingBinder();
    }

    public static void addListener(Listener l) { listeners.addIfAbsent(l); }
    public static void removeListener(Listener l) { listeners.remove(l); }

    private static void notifyChanged(boolean connected) {
        for (Listener l : listeners) {
            try { l.onHelperChanged(connected); } catch (RuntimeException e) { Log.w(TAG, "helper listener failed", e); }
        }
    }

    // Called by HelperProvider when the helper presents its binder. Returns Hush's liveness binder,
    // or null if the token or version does not match (a stale or forged handoff).
    static IBinder onHello(Context c, IBinder helperBinder, String presentedToken, int version) {
        String token = prefs(c).getString(KEY_TOKEN, null);
        if (token == null || !token.equals(presentedToken)) {
            Log.w(TAG, "helper hello rejected: token mismatch");
            return null;
        }
        if (version != HelperProtocol.VERSION) {
            Log.w(TAG, "helper hello rejected: version " + version + " != " + HelperProtocol.VERSION);
            return null;
        }
        IBinder old = binder;
        try {
            helperBinder.linkToDeath(() -> {
                if (binder != helperBinder) return;   // an old helper that was replaced
                binder = null;
                Log.i(TAG, "helper died");
                notifyChanged(false);
            }, 0);
        } catch (Exception e) {
            return null;
        }
        binder = helperBinder;
        if (old != null && old != helperBinder && old.pingBinder()) {   // a second helper: the new one wins
            new Thread(() -> transactSimple(old, HelperProtocol.EXIT), "hush-helper-replace").start();
        }
        Log.i(TAG, "helper connected");
        notifyChanged(true);
        return liveness;
    }

    // ---- starting the helper ----

    // The single command line that starts the helper, run as the shell user. A fresh token is stored
    // each time, so only the helper started with this command is accepted.
    public static String startCommand(Context c) {
        String token = newToken();
        prefs(c).edit().putString(KEY_TOKEN, token).commit();
        String apk = c.getApplicationInfo().sourceDir;
        String authority = c.getPackageName() + HelperProtocol.AUTHORITY_SUFFIX;
        // setsid puts the helper in its own session so it survives the debugging session that
        // started it. CLASSPATH is set before setsid so app_process inherits it. The shell waits a
        // second before it ends: adbd ends the session's processes when the shell exits, and the
        // helper must have left that session (setsid, within milliseconds) by then.
        return "CLASSPATH=" + apk + " setsid /system/bin/app_process /system/bin --nice-name=hush_helper "
                + Helper.class.getName()
                + " --token=" + token
                + " --uid=" + Process.myUid()
                + " --apk=" + apk
                + " --authority=" + authority + " </dev/null >/dev/null 2>&1 & sleep 1";
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String newToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---- actions ----

    // Runs one command from Commands through the helper and returns its output, or null if the
    // helper is not connected or the call did not get through (the caller may then use Shizuku).
    // The command is also built here first, so an argument that is not allowed throws
    // IllegalArgumentException exactly as it does with Shizuku.
    public static String run(int action, String arg) {
        if (action != Commands.GRANT_SECURE_SETTINGS) Commands.build(action, arg);
        IBinder b = binder;
        if (b == null) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HelperProtocol.DESCRIPTOR);
            data.writeInt(action);
            data.writeString(arg);
            if (!b.transact(HelperProtocol.RUN, data, reply, 0)) return null;
            return reply.readInt() == HelperProtocol.OK ? reply.readString() : "";
        } catch (Exception e) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // Force-stop of Spotify straight through the activity service (no shell). Returns the
    // helper-measured microseconds, or -1 on failure. direct[0] is set true if the internal API was
    // used, false if the helper fell back to the shell command.
    public static long forceStopSpotify(boolean[] direct) {
        IBinder b = binder;
        if (b == null) return -1;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HelperProtocol.DESCRIPTOR);
            data.writeString(Commands.SPOTIFY);
            if (!b.transact(HelperProtocol.FORCE_STOP, data, reply, 0)) return -1;
            if (reply.readInt() != HelperProtocol.OK) return -1;
            long micros = reply.readLong();
            if (direct != null && direct.length > 0) direct[0] = reply.readInt() == 1;
            return micros;
        } catch (Exception e) {
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // The app in front read directly by the helper ("pkg/cls" and microseconds), or null.
    public static Object[] front() {
        IBinder b = binder;
        if (b == null) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HelperProtocol.DESCRIPTOR);
            if (!b.transact(HelperProtocol.FRONT, data, reply, 0) || reply.readInt() != HelperProtocol.OK) return null;
            return new Object[]{reply.readString(), reply.readLong()};
        } catch (Exception e) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // Brings the task with this id back to the front (the exact window the user was in). False if the
    // helper is not connected or the device does not allow it; the caller then reopens the app.
    public static boolean toFront(int task) {
        IBinder b = binder;
        if (b == null || task <= 0) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HelperProtocol.DESCRIPTOR);
            data.writeInt(task);
            return b.transact(HelperProtocol.TO_FRONT, data, reply, 0) && reply.readInt() == HelperProtocol.OK && reply.readInt() == 1;
        } catch (Exception e) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // Installs a Hush update through the helper (see HelperProtocol.INSTALL). Returns pm's output
    // ("Success" when it worked), or null if the helper is not connected.
    public static String install(java.io.File apk) {
        IBinder b = binder;
        if (b == null) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try (android.os.ParcelFileDescriptor f = android.os.ParcelFileDescriptor.open(apk, android.os.ParcelFileDescriptor.MODE_READ_ONLY)) {
            data.writeInterfaceToken(HelperProtocol.DESCRIPTOR);
            data.writeFileDescriptor(f.getFileDescriptor());
            data.writeLong(apk.length());
            if (!b.transact(HelperProtocol.INSTALL, data, reply, 0) || reply.readInt() != HelperProtocol.OK) return null;
            return reply.readString();
        } catch (Exception e) {
            // The helper exits when its APK is replaced, so the reply can be lost even though the
            // install worked; the caller checks the installed version.
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // Android's active projection types as the helper reads them (bit 1: Android Auto), or -1.
    public static int projection() {
        IBinder b = binder;
        if (b == null) return -1;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HelperProtocol.DESCRIPTOR);
            if (!b.transact(HelperProtocol.PROJECTION, data, reply, 0) || reply.readInt() != HelperProtocol.OK) return -1;
            return reply.readInt();
        } catch (Exception e) {
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // Helper version and pid, or null if not connected.
    public static int[] ping() {
        IBinder b = binder;
        if (b == null) return null;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HelperProtocol.DESCRIPTOR);
            if (!b.transact(HelperProtocol.PING, data, reply, 0)) return null;
            if (reply.readInt() != HelperProtocol.OK) return null;
            return new int[]{reply.readInt(), reply.readInt()};   // version, pid
        } catch (Exception e) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // Stops the running helper (debug and tests; a newer helper replaces an old one by itself).
    public static void stop() {
        IBinder b = binder;
        if (b != null) transactSimple(b, HelperProtocol.EXIT);
    }

    private static void transactSimple(IBinder b, int code) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HelperProtocol.DESCRIPTOR);
            b.transact(code, data, reply, 0);
        } catch (Exception ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
