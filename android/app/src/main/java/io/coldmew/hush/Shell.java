package io.coldmew.hush;

import android.content.pm.PackageManager;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import io.coldmew.hush.helper.Commands;
import io.coldmew.hush.helper.HushHelper;
import rikka.shizuku.Shizuku;

// Privileged commands (adb shell user rights). Only the commands in Commands can be run. Preferred
// backend is Hush's own helper; if it is not connected (or a call does not get through), Shizuku
// runs the very same command line.
final class Shell {
    static final String SHIZUKU = "moe.shizuku.privileged.api";

    private Shell() {}

    static boolean installed(android.content.Context c) {
        try { c.getPackageManager().getPackageInfo(SHIZUKU, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    // Shizuku is running (whether or not Hush may use it).
    static boolean running() {
        try { return Shizuku.pingBinder(); } catch (Throwable e) { return false; }
    }

    // True if any backend can run privileged commands (Hush's helper or Shizuku).
    static boolean ready() {
        return HushHelper.ready() || shizukuReady();
    }

    static boolean shizukuReady() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) { return false; }
    }

    static void requestPermission() {
        try { if (running() && !shizukuReady()) Shizuku.requestPermission(7); } catch (Throwable ignored) {}
    }

    static String run(int action) { return run(action, null); }

    // Runs one command and returns its output ("" if no backend is available or it failed). Throws
    // IllegalArgumentException for an argument that is not allowed.
    static String run(int action, String arg) {
        String out = HushHelper.run(action, arg);
        if (out != null) return out;
        String cmd = Commands.build(action, arg);
        return shizukuReady() ? sh(cmd) : "";
    }

    // Runs `sh -c cmd` through Shizuku and returns stdout ("" on failure). Shizuku.newProcess is
    // hidden from the public API but kept for compatibility (Shizuku-API issue #276), so it is
    // called reflectively.
    private static String sh(String cmd) {
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            return out.toString().trim();
        } catch (Throwable e) {
            Log.w(Hush.TAG, "sh failed: " + e.getClass().getSimpleName());
            return "";
        }
    }
}
