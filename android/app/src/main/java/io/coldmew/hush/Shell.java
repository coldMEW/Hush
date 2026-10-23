package io.coldmew.hush;

import android.content.pm.PackageManager;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.regex.Pattern;
import rikka.shizuku.Shizuku;

// Shell commands with adb (shell user) privileges, through Shizuku. Everything that is not a
// constant must go through arg(), which only lets package/component names through.
final class Shell {
    static final String SHIZUKU = "moe.shizuku.privileged.api";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.$/]{1,255}");

    private Shell() {}

    static boolean installed(android.content.Context c) {
        try { c.getPackageManager().getPackageInfo(SHIZUKU, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    static boolean running() {
        try { return Shizuku.pingBinder(); } catch (Throwable e) { return false; }
    }

    static boolean ready() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) { return false; }
    }

    static void requestPermission() {
        try { if (running() && !ready()) Shizuku.requestPermission(7); } catch (Throwable ignored) {}
    }

    // A package or component name as a single-quoted shell word. Anything else is rejected, so a
    // hostile name (e.g. another app's activity) can never inject a command.
    static String arg(String s) {
        if (s == null || !SAFE.matcher(s).matches()) throw new IllegalArgumentException("unsafe shell argument");
        return "'" + s + "'";
    }

    private static final Pattern PLAYABLE = Pattern.compile("spotify:(playlist|album):[A-Za-z0-9]{1,64}");

    // A Spotify playlist or album URI as a single-quoted shell word; anything else is rejected.
    static String spotifyUri(String s) {
        if (s == null || !PLAYABLE.matcher(s).matches()) throw new IllegalArgumentException("unsupported URI");
        return "'" + s + ":play'";
    }

    static boolean playable(String s) { return s != null && PLAYABLE.matcher(s).matches(); }

    // Runs `sh -c cmd` and returns stdout ("" on failure). Shizuku.newProcess is hidden from the
    // public API but kept for compatibility (Shizuku-API issue #276), so it is called reflectively.
    static String sh(String cmd) {
        if (!ready()) return "";
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
