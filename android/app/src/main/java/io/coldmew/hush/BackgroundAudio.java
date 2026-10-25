package io.coldmew.hush;

import android.content.Context;

// Keeps a restarted Spotify audible in the background.
//  - Android 15 and later mute media apps that play from the background without a foreground
//    service ("AudioHardening", enforced through the CONTROL_AUDIO app-ops). After a restart-skip
//    Spotify plays before it has registered its playback service, so it would go silent about 10
//    seconds later, and with the phone locked it stays silent until the phone is unlocked. Hush
//    allows these ops for Spotify while Hush is on, and returns them to Android's default when Hush
//    is turned off.
//  - For the same reason Android counts the restarted Spotify as a cached app and freezes its
//    process: the media session still says "playing", but no sound comes out until Spotify is
//    opened. UNFREEZE exempts the running Spotify process from the freezer for its lifetime only
//    (Android 14+; older versions ignore the command), so it is applied to every new process.
final class BackgroundAudio {
    private static final String[] OPS = {"CONTROL_AUDIO", "CONTROL_AUDIO_PARTIAL", "TAKE_AUDIO_FOCUS"};
    static final String ALLOW = command("allow");
    static final String RESET = command("default");
    static final String UNFREEZE = "am unfreeze --sticky " + Shell.arg(Hush.SPOTIFY) + " >/dev/null 2>&1";

    private BackgroundAudio() {}

    private static String command(String mode) {
        StringBuilder b = new StringBuilder();
        for (String op : OPS) {
            if (b.length() > 0) b.append("; ");
            b.append("cmd appops set ").append(Shell.arg(Hush.SPOTIFY)).append(' ').append(op).append(' ').append(mode)
                    .append(" 2>/dev/null");
        }
        return b.toString();
    }

    // Off the main thread: each runs one shell command through Shizuku (no-op without it).
    static void allow() { new Thread(() -> Shell.sh(ALLOW + "; " + UNFREEZE), "hush-audio").start(); }
    static void reset() { new Thread(() -> Shell.sh(RESET), "hush-audio").start(); }
    static void unfreeze() { new Thread(() -> Shell.sh(UNFREEZE), "hush-audio").start(); }

    static boolean frozen() { return Shell.sh("am isfrozen " + Shell.arg(Hush.SPOTIFY) + " 2>/dev/null").equals("true"); }

    static void apply(Context c) {
        if (new Prefs(c).enabled()) allow(); else reset();
    }
}
