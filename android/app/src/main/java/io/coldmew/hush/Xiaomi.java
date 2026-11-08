package io.coldmew.hush;

import android.content.Context;
import android.os.Build;
import io.coldmew.hush.helper.Commands;

// Xiaomi, Redmi and POCO phones (MIUI / HyperOS) add their own limits on top of Android's:
//  - "Autostart" (off for apps installed outside the Play Store): when Xiaomi's cleaner kills Hush,
//    for example when it is swiped away from recent apps, Android tries to connect Hush's ad watcher
//    again and Xiaomi refuses that, so Hush stays closed and ads are no longer noticed (issue #12).
//    The same switch keeps Spotify from being started again in the background. Hush's 2.0.4 notice
//    asked people to allow it for Spotify by hand; Hush's helper now allows it for Hush and Spotify
//    itself, the same way it lifts Spotify's Android background restriction.
//  - "USB debugging (Security settings)": without it the shell may not press keys or grant
//    permissions. Hush then does not use key presses, and the setup screen shows the switch.
final class Xiaomi {
    private Xiaomi() {}

    // MIUI_AUTOSTART's answer: "MIUIOP(10008): allow; time=..." once allowed.
    static boolean allowed(String appopsOut) {
        return appopsOut != null && appopsOut.contains("MIUIOP(10008): allow");
    }

    static boolean phone() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(java.util.Locale.ROOT);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    // Set when the shell's `input` was refused (KEY_PLAY or UNMUTE_MUSIC printed INPUT_REFUSED).
    static volatile boolean inputRefused;

    static void noteInput(String out) {
        if (out != null && out.contains(Commands.INPUT_REFUSED)) inputRefused = true;
    }

    // Blocking (privileged commands). From HelperControl when the helper connects, on Xiaomi only.
    // Sets the switch only where it is not allowed yet, and says so once in Recent activity.
    static void allowAutostart(Context c) {
        if (!phone() || !Shell.ready()) return;
        android.content.SharedPreferences sp = c.getSharedPreferences("hush", Context.MODE_PRIVATE);
        int boot = HelperControl.bootCount(c), setAt = sp.getInt("xiaomi_autostart_boot", -1);
        boolean hushAllowed = allowed(Shell.run(Commands.MIUI_AUTOSTART_STATE, c.getPackageName()));
        // Xiaomi keeps only an Autostart the user switched on in its own settings: the one Hush sets
        // is gone after the next restart (measured on HyperOS 2: back to "ignore", Hush not started
        // after the reboot). Still allowed at a later boot means the user switched it on; found off
        // again after Hush had set it means the user has to, once (the setup row in MainActivity).
        if (hushAllowed && setAt >= 0 && setAt != boot) sp.edit().putBoolean("xiaomi_autostart_ask", false).apply();
        if (!hushAllowed) sp.edit().putBoolean("xiaomi_autostart_ask", true).apply();   // the user has not switched it on
        boolean changed = false;
        for (String pkg : new String[]{c.getPackageName(), Commands.SPOTIFY}) {
            if (allowed(Shell.run(Commands.MIUI_AUTOSTART_STATE, pkg))) continue;
            if (allowed(Shell.run(Commands.MIUI_AUTOSTART, pkg))) changed = true;
        }
        if (!hushAllowed) sp.edit().putInt("xiaomi_autostart_boot", boot).apply();
        if (changed) Events.add(c, Events.INFO, "Allowed Autostart for Hush and Spotify (Xiaomi), so Android can bring them back after its cleaner closes them");
    }

    // Show the "Allow Autostart" setup row: Xiaomi dropped the Autostart Hush set for itself at a restart.
    static boolean askAutostart(Context c) {
        return phone() && c.getSharedPreferences("hush", Context.MODE_PRIVATE).getBoolean("xiaomi_autostart_ask", false);
    }

    static void autostartAsked(Context c) {
        c.getSharedPreferences("hush", Context.MODE_PRIVATE).edit().putBoolean("xiaomi_autostart_ask", false).apply();
    }

    // Xiaomi's own Autostart list (Security app); App info if that page is not there.
    static android.content.Intent autostartSettings(Context c) {
        // Started directly; the caller falls back to App info if this page is not there.
        return new android.content.Intent().setClassName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    // For the diagnostics report (blocking).
    static String diagnostics(Context c) {
        if (!phone()) return "";
        String hush = !Shell.ready() ? "?" : allowed(Shell.run(Commands.MIUI_AUTOSTART_STATE, c.getPackageName())) ? "allowed" : "off";
        String spotify = !Shell.ready() ? "?" : allowed(Shell.run(Commands.MIUI_AUTOSTART_STATE, Commands.SPOTIFY)) ? "allowed" : "off";
        return "\nxiaomi autostart: hush " + hush + ", spotify " + spotify
                + "\nxiaomi key presses: " + (inputRefused ? "refused (USB debugging (Security settings) off)" : "not refused so far");
    }
}
