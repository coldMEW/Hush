package io.coldmew.hush;

import android.content.Context;
import io.coldmew.hush.helper.Commands;

// Spotify's "background usage" setting (Android's RUN_ANY_IN_BACKGROUND app-op). Battery >
// Restricted, and some makers' battery savers, set it so that Spotify may not start its playback
// service unless it is on screen. A Spotify that Hush restarted in the background then plays as a
// cached app: no notification or lock-screen controls, and a few minutes later Android pauses,
// freezes or stops it. Only an explicit "allow" counts as allowed (Android treats "default" as
// restricted too). Skipping cannot work properly with it, so Hush allows it by itself through its
// helper or Shizuku (and says so in Recent activity); the Fix button on the main screen is only
// there for the case where that was not possible.
final class SpotifyBackground {
    private SpotifyBackground() {}

    // null until read (or without a backend).
    private static volatile Boolean restricted;

    static boolean restricted() { return Boolean.TRUE.equals(restricted); }

    // From `appops get <pkg> RUN_ANY_IN_BACKGROUND`: "RUN_ANY_IN_BACKGROUND: allow; time=...",
    // "...: ignore", "...: default", or "No operations." (never set: allowed). null if unreadable.
    static Boolean parse(String out) {
        if (out == null) return null;
        String s = out.trim();
        int i = s.indexOf("RUN_ANY_IN_BACKGROUND:");
        if (i < 0) return s.startsWith("No operations") ? Boolean.FALSE : null;
        String mode = s.substring(i + "RUN_ANY_IN_BACKGROUND:".length()).trim();
        return !mode.startsWith("allow");
    }

    // Blocking (one privileged command). Returns the new state; keeps the old one if unreadable.
    static boolean refresh() {
        if (!Shell.ready()) return restricted();
        Boolean r = parse(Shell.run(Commands.SPOTIFY_BG_STATE));
        if (r != null) restricted = r;
        return restricted();
    }

    // Allow Spotify in the background (by itself, or when the user taps Fix). Returns true if it is
    // allowed now.
    static boolean allow(Context c) {
        if (!Shell.ready()) return false;
        Shell.run(Commands.SPOTIFY_BG_ALLOW);
        boolean ok = !refresh();
        if (ok) Events.add(c, Events.INFO, "Spotify was restricted in the background (battery setting): allowed it, so it keeps playing after a skip");
        return ok;
    }

    // Blocking. If Spotify is restricted, allow it. Returns true if Spotify may run in the
    // background now (or it cannot be read).
    static boolean ensureAllowed(Context c) {
        return !refresh() || allow(c);
    }
}
