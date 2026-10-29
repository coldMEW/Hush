package io.coldmew.hush.helper;

import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import java.lang.reflect.Method;

// The only things the helper does, each with validated arguments. Force-stop calls Android's
// activity service directly (no process is started); if that internal API is missing on a device,
// it falls back to the equivalent shell command.
final class Actions {
    private static int userId;
    private static String hushPackage;
    private static Object activityManager;
    private static Method forceStop;   // IActivityManager.forceStopPackage(String, int)
    private static Object taskManager;
    // Id of the task frontComponent() found last (-1 if unknown), so it can be brought back as is.
    private static volatile int frontTask = -1;

    private Actions() {}

    static void init(int user, String pkg) {
        userId = user;
        hushPackage = pkg;
        try {
            activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            forceStop = Helper.method(activityManager.getClass(), "forceStopPackage", 2);
        } catch (Throwable t) {
            System.err.println("hush_helper: direct force-stop unavailable, using the shell command: " + t);
        }
        try {
            taskManager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        } catch (Throwable t) {
            System.err.println("hush_helper: direct task read unavailable, using the shell command: " + t);
        }
    }

    // The task on top ("pkg/cls"), straight from the activity task service: the running tasks,
    // topmost first, including the home screen (with the phone locked, the app behind the lock
    // screen). Same answer as the recents dump used before, without starting a process. null if
    // the internal API is not there.
    static String frontComponent() {
        if (taskManager == null) return null;
        try {
            Object list = null;
            for (Method m : taskManager.getClass().getMethods()) {
                if (!m.getName().equals("getTasks")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 4) list = m.invoke(taskManager, 1, false, false, 0);   // Android 13+: + display id
                else if (p.length == 3) list = m.invoke(taskManager, 1, false, false); // Android 12
                else if (p.length == 1) list = m.invoke(taskManager, 1);               // Android 11
                else continue;
                break;
            }
            if (!(list instanceof java.util.List) || ((java.util.List<?>) list).isEmpty()) return null;
            Object info = ((java.util.List<?>) list).get(0);
            frontTask = taskId(info);
            // The task's root activity, like the realActivity of the recents entry 1.0.3 parsed.
            android.content.ComponentName cn = (android.content.ComponentName) info.getClass().getField("baseActivity").get(info);
            if (cn == null) cn = (android.content.ComponentName) info.getClass().getField("topActivity").get(info);
            return cn == null ? null : cn.flattenToShortString();
        } catch (Throwable t) {
            System.err.println("hush_helper: direct task read failed: " + t);
            return null;
        }
    }

    private static int taskId(Object info) {
        for (String f : new String[]{"taskId", "id"}) {   // TaskInfo.taskId; RunningTaskInfo.id before Android 10
            try { return info.getClass().getField(f).getInt(info); } catch (Throwable ignored) {}
        }
        return -1;
    }

    // ActivityTaskManager's moveTaskToFront(caller, callingPackage, taskId, flags, options): what the
    // recents screen does when you tap an app. Returns false if the call is not there on this device.
    private static boolean toFront(int task) {
        if (task <= 0) return false;
        for (Object service : new Object[]{taskManager, activityManager}) {
            if (service == null) continue;
            for (Method m : service.getClass().getMethods()) {
                if (!m.getName().equals("moveTaskToFront")) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    if (p.length == 5) { m.invoke(service, null, "com.android.shell", task, 0, null); return true; }
                    if (p.length == 3) { m.invoke(service, task, 0, null); return true; }   // older versions
                } catch (Throwable t) {
                    System.err.println("hush_helper: moveTaskToFront failed: " + t);
                }
            }
        }
        return false;
    }

    // `pm install -r -S <size>` reading the APK from stdin: no file path the shell must read, and
    // -r keeps the app's data (an update, never a fresh install). Returns pm's output ("Success").
    private static String install(android.os.ParcelFileDescriptor apk, long size) {
        if (apk == null || size <= 0) return "Failure: no APK";
        try (android.os.ParcelFileDescriptor f = apk;
             java.io.InputStream in = new java.io.FileInputStream(f.getFileDescriptor())) {
            java.lang.Process p = new ProcessBuilder("pm", "install", "-r", "-S", String.valueOf(size))
                    .redirectErrorStream(true).start();
            try (java.io.OutputStream out = p.getOutputStream()) {
                byte[] b = new byte[65536];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            }
            StringBuilder s = new StringBuilder();
            try (java.io.InputStream r = p.getInputStream()) {
                byte[] b = new byte[4096];
                for (int n; (n = r.read(b)) > 0; ) s.append(new String(b, 0, n, "UTF-8"));
            }
            p.waitFor();
            return s.toString().trim();
        } catch (Throwable t) {
            return "Failure: " + t.getClass().getSimpleName();
        }
    }

    static void handle(int code, Parcel data, Parcel reply) {
        switch (code) {
            case HelperProtocol.PING:
                reply.writeInt(HelperProtocol.OK);
                reply.writeInt(HelperProtocol.VERSION);
                reply.writeInt(Process.myPid());
                break;
            case HelperProtocol.FORCE_STOP: {
                String pkg = data.readString();
                if (!Commands.SPOTIFY.equals(pkg)) throw new IllegalArgumentException("not allowed: " + pkg);
                long t = SystemClock.elapsedRealtimeNanos();
                boolean direct = forceStopSpotify();
                reply.writeInt(HelperProtocol.OK);
                reply.writeLong((SystemClock.elapsedRealtimeNanos() - t) / 1000);   // microseconds
                reply.writeInt(direct ? 1 : 0);
                break;
            }
            case HelperProtocol.RUN: {
                int action = data.readInt();
                String arg = data.readString();
                // The permission is only ever granted to Hush itself, whatever Hush asked for.
                if (action == Commands.GRANT_SECURE_SETTINGS) arg = hushPackage;
                String cmd = Commands.build(action, arg);   // throws for anything not in the table
                reply.writeInt(HelperProtocol.OK);
                reply.writeString(action == Commands.STOP_READ_FRONT ? stopReadFront(arg, cmd) : Exec.sh(cmd));
                break;
            }
            case HelperProtocol.FRONT: {   // the app in front, read directly (and how long it took)
                reply.writeInt(HelperProtocol.OK);
                long t = SystemClock.elapsedRealtimeNanos();
                reply.writeString(frontComponent());
                reply.writeLong((SystemClock.elapsedRealtimeNanos() - t) / 1000);
                break;
            }
            case HelperProtocol.PROJECTION:
                reply.writeInt(HelperProtocol.OK);
                reply.writeInt(projectionTypes());
                break;
            case HelperProtocol.TO_FRONT: {
                int task = data.readInt();
                reply.writeInt(HelperProtocol.OK);
                reply.writeInt(toFront(task) ? 1 : 0);
                break;
            }
            case HelperProtocol.INSTALL: {
                android.os.ParcelFileDescriptor apk = data.readFileDescriptor();
                long size = data.readLong();
                reply.writeInt(HelperProtocol.OK);
                reply.writeString(install(apk, size));
                break;
            }
            case HelperProtocol.EXIT:
                reply.writeInt(HelperProtocol.OK);
                // Answer first, then leave: a newer helper replaces this one.
                new Thread(() -> { SystemClock.sleep(200); System.exit(0); }, "hush-helper-exit").start();
                break;
            default:
                throw new IllegalArgumentException("unknown transaction " + code);
        }
    }

    // UiModeManager.getActiveProjectionTypes() (Android 12+), through the uimode service: bit 1 is
    // automotive projection, i.e. Android Auto is driving a car screen. -1 if it cannot be read.
    private static int projectionTypes() {
        if (Helper.sdk() < 31) return -1;
        try {
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "uimode");
            Object ui = Class.forName("android.app.IUiModeManager$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            return (Integer) ui.getClass().getMethod("getActiveProjectionTypes").invoke(ui);
        } catch (Throwable t) {
            System.err.println("hush_helper: projection state unavailable: " + t);
            return -1;
        }
    }

    // The skip's first step, as fast as possible: the app in front is read and Spotify stopped with
    // two direct calls (a few milliseconds instead of ~50 ms for dumpsys + am); then, as before,
    // Spotify's screen is reopened if it was in front, and the temporary allowlist is set. The output
    // has the same form as the shell command's (a recents-style "realActivity=" line and the OPENED
    // marker). Without the internal API it runs that shell command unchanged.
    private static String stopReadFront(String spotifyActivity, String shellCommand) {
        String front = frontComponent();
        if (front == null) return Exec.sh(shellCommand);
        forceStopSpotify();
        boolean inFront = front.startsWith(Commands.SPOTIFY + "/");
        Exec.sh(inFront ? Commands.openCommand(spotifyActivity) + "; " + Commands.ALLOWLIST : Commands.ALLOWLIST);
        return "realActivity=" + front + "\ntaskId=" + frontTask + (inFront ? "\n" + Commands.OPENED : "");
    }

    // True if Android's activity service was called directly, false if the shell command was used.
    private static boolean forceStopSpotify() {
        if (forceStop != null) {
            try { forceStop.invoke(activityManager, Commands.SPOTIFY, userId); return true; }
            catch (Throwable t) { System.err.println("hush_helper: direct force-stop failed: " + t); }
        }
        Exec.run("am", "force-stop", "--user", String.valueOf(userId), Commands.SPOTIFY);
        return false;
    }
}
