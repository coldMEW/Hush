package io.coldmew.hush;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import io.coldmew.hush.helper.HelperProtocol;
import io.coldmew.hush.helper.HushHelper;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

// Rooted phones (Magisk, KernelSU, APatch): Hush can start its helper with `su` instead of
// Wireless debugging, so no pairing, no Wi-Fi and nothing to redo after a reboot. Root is used for
// that one command only, and it starts the helper as the shell user (uid 2000): the helper then
// has exactly the rights and the command list it has when started over Wireless debugging. No
// other command ever goes through su.
final class Root {
    private Root() {}

    private static final String[] SU = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su"};
    // Root managers. KernelSU and APatch show su only to apps that were granted root, so their
    // apps are what tells Hush that root may be there.
    static final String[] MANAGERS = {"com.topjohnwu.magisk", "io.github.vvb2060.magisk", "io.github.huskydg.magisk",
            "me.weishu.kernelsu", "com.rifsxd.ksunext", "me.bmax.apatch"};

    // Root may be available: an su binary or a root manager app. Only a start tells for sure.
    static boolean available(Context c) {
        for (String s : SU) if (new File(s).exists()) return true;
        PackageManager pm = c.getPackageManager();
        for (String m : MANAGERS) {
            try { pm.getPackageInfo(m, 0); return true; } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return false;
    }

    // Blocking. Asks the root manager to run the helper's start command as the shell user; the first
    // time the manager asks the user, which can take a while. Returns null when the helper is
    // connected, or why not.
    static String startHelper(Context c) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", String.valueOf(HelperProtocol.SHELL_UID), "-c", HushHelper.startCommand(c))
                    .redirectErrorStream(true).start();
            drain(p.getInputStream());
            if (!p.waitFor(60, TimeUnit.SECONDS)) return "no answer from the root app";
            int exit = p.exitValue();
            long end = SystemClock.elapsedRealtime() + 8000;
            while (!HushHelper.ready() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50);
            if (HushHelper.ready()) return null;
            return exit == 0 ? "helper did not connect" : "root access was denied";
        } catch (java.io.IOException e) {
            return "su not found";
        } catch (InterruptedException e) {
            return "interrupted";
        } finally {
            if (p != null) p.destroy();
        }
    }

    // su's output is not needed, but it must not fill the pipe and block it.
    private static void drain(InputStream in) {
        Thread t = new Thread(() -> {
            byte[] b = new byte[1024];
            try (InputStream i = in) { while (i.read(b) > 0) { /* discard */ } } catch (java.io.IOException ignored) {}
        }, "hush-su-output");
        t.setDaemon(true);
        t.start();
    }
}
