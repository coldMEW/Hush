package io.coldmew.hush;

import android.content.Context;

// Android 15 and later mute media apps that play from the background without a foreground service
// ("AudioHardening", enforced through the CONTROL_AUDIO app-ops). After a restart-skip Spotify
// plays before it has registered its playback service, so it would go silent about 10 seconds
// later, and with the phone locked it stays silent until the phone is unlocked. Hush therefore
// allows these ops for Spotify while Hush is on, and returns them to Android's default when Hush
// is turned off.
final class BackgroundAudio {
    private static final String[] OPS = {"CONTROL_AUDIO", "CONTROL_AUDIO_PARTIAL", "TAKE_AUDIO_FOCUS"};
    static final String ALLOW = command("allow");
    static final String RESET = command("default");

    private BackgroundAudio() {}

    private static String command(String mode) {
        StringBuilder b = new StringBuilder();
        for (String op : OPS) {
            if (b.length() > 0) b.append("; ");
            b.append("appops set ").append(Shell.arg(Hush.SPOTIFY)).append(' ').append(op).append(' ').append(mode)
                    .append(" 2>/dev/null");
        }
        return b.toString();
    }

    // Off the main thread: each runs one shell command through Shizuku (no-op without it).
    static void allow() { new Thread(() -> Shell.sh(ALLOW), "hush-audio").start(); }
    static void reset() { new Thread(() -> Shell.sh(RESET), "hush-audio").start(); }

    static void apply(Context c) {
        if (new Prefs(c).enabled()) allow(); else reset();
    }
}
