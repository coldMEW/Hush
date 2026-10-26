package io.coldmew.hush.helper;

import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.lang.reflect.Method;

// Hush's own privileged helper. It is not part of the app process: it is started with app_process
// from Hush's APK through Wireless debugging (or adb), so it runs as the shell user (uid 2000) and
// can do what Shizuku did for Hush. It hands Hush a Binder through Hush's content provider and then
// answers only Hush's uid, and only the fixed actions in HelperProtocol. It exits when Hush's APK is
// replaced (an update starts a fresh helper) and keeps itself available while Hush restarts.
public final class Helper {
    private static String token, apk, authority;
    private static int hushUid;
    private static IBinder hush;   // Hush's binder from the handshake, to notice when Hush dies

    private Helper() {}

    public static void main(String[] args) {
        for (String a : args) {
            if (a.startsWith("--token=")) token = a.substring(8);
            else if (a.startsWith("--uid=")) hushUid = Integer.parseInt(a.substring(6));
            else if (a.startsWith("--apk=")) apk = a.substring(6);
            else if (a.startsWith("--authority=")) authority = a.substring(12);
        }
        if (token == null || authority == null || !authority.endsWith(HelperProtocol.AUTHORITY_SUFFIX)
                || hushUid <= 0 || Process.myUid() != HelperProtocol.SHELL_UID) {
            System.err.println("hush_helper: bad arguments or not running as shell");
            System.exit(2);
        }
        exemptHiddenApis();   // so the reflective calls to Android's internal services work here
        Looper.prepareMainLooper();
        Actions.init(userId(), authority.substring(0, authority.length() - HelperProtocol.AUTHORITY_SUFFIX.length()));
        if (!handOver(5)) { System.err.println("hush_helper: Hush did not take the binder"); System.exit(1); }
        watchApk();
        Looper.loop();
    }

    // Sends the binder to Hush's provider; retries a few times (Hush may be starting).
    private static boolean handOver(int tries) {
        for (int i = 0; i < tries; i++) {
            try {
                Bundle extras = new Bundle();
                extras.putBinder(HelperProtocol.EXTRA_BINDER, SERVICE);
                extras.putString(HelperProtocol.EXTRA_TOKEN, token);
                extras.putInt(HelperProtocol.EXTRA_VERSION, HelperProtocol.VERSION);
                Bundle reply = Providers.call(authority, userId(), HelperProtocol.METHOD_HELLO, extras);
                IBinder b = reply == null ? null : reply.getBinder(HelperProtocol.EXTRA_BINDER);
                if (b != null && b.pingBinder()) {
                    hush = b;
                    // Hush died (killed, updated, crashed): hand the binder over again when it is back.
                    hush.linkToDeath(() -> new Thread(() -> {
                        // Hush was updated: its new version starts its own helper.
                        if (apk != null && !new File(apk).exists()) System.exit(0);
                        SystemClock.sleep(1000);
                        if (apk != null && !new File(apk).exists()) System.exit(0);
                        if (!handOver(30)) System.exit(0);
                    }, "hush-helper-rebind").start(), 0);
                    return true;
                }
            } catch (Throwable t) {
                System.err.println("hush_helper: hand-over failed: " + t);
            }
            SystemClock.sleep(1000);
        }
        return false;
    }

    // An updated Hush lives at a new path; the old helper steps aside so the new one can start.
    // An inotify watch (no wake-ups); a slow check stays as a safety net.
    private static android.os.FileObserver apkObserver;

    private static void watchApk() {
        if (apk == null) return;
        apkObserver = new android.os.FileObserver(new File(apk),
                android.os.FileObserver.DELETE_SELF | android.os.FileObserver.MOVE_SELF) {
            @Override public void onEvent(int event, String path) { System.exit(0); }
        };
        apkObserver.startWatching();
        Thread t = new Thread(() -> {
            while (true) {
                SystemClock.sleep(60_000);
                if (!new File(apk).exists()) System.exit(0);
            }
        }, "hush-helper-apk");
        t.setDaemon(true);
        t.start();
    }

    private static final Binder SERVICE = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags);
            if (Binder.getCallingUid() != hushUid) return false;   // only Hush, nobody else
            try {
                data.enforceInterface(HelperProtocol.DESCRIPTOR);
                Actions.handle(code, data, reply);
                return true;
            } catch (SecurityException | IllegalArgumentException e) {
                reply.writeInt(HelperProtocol.ERROR);
                reply.writeString(e.getMessage());
                return true;
            }
        }
    };

    static int sdk() { return Build.VERSION.SDK_INT; }

    // Android user that Hush runs in (uid = user * 100000 + app id).
    static int userId() { return hushUid / 100000; }

    // Lifts the hidden-API restriction for this process so reflection can reach the internal
    // activity-manager and provider methods. Harmless if the platform already allows it.
    private static void exemptHiddenApis() {
        if (Build.VERSION.SDK_INT < 28) return;
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vmRuntime.getDeclaredMethod("getRuntime").invoke(null);
            vmRuntime.getDeclaredMethod("setHiddenApiExemptions", String[].class)
                    .invoke(runtime, (Object) new String[]{"L"});
        } catch (Throwable t) {
            System.err.println("hush_helper: hidden-api exemption not applied: " + t);
        }
    }

    // Reflection helper: the first public method with this name and parameter count.
    static Method method(Class<?> c, String name, int params) throws NoSuchMethodException {
        for (Method m : c.getMethods())
            if (m.getName().equals(name) && m.getParameterTypes().length == params) return m;
        throw new NoSuchMethodException(c.getName() + "." + name + "/" + params);
    }
}
