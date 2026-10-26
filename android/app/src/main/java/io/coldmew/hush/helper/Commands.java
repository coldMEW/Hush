package io.coldmew.hush.helper;

import java.util.regex.Pattern;

// Every privileged command Hush uses, and nothing else. Both backends build their command lines
// here: Hush's own helper (which only ever runs a command from this table, built from an action id
// and validated arguments) and Shizuku. Everything that is not a constant goes through arg() or
// spotifyUri(), which only let package/component names and Spotify playlist/album URIs through.
public final class Commands {
    public static final String SPOTIFY = "com.spotify.music";

    // Spotify was the app in front and its screen was opened again (printed by STOP_READ_FRONT).
    public static final String OPENED = "@@hush-opened-spotify";

    // Action ids. arg: Spotify's launch component for STOP_READ_FRONT, OPEN_AND_ALLOW, RESTART; a
    // launcher component for OPEN_APP; a playlist/album URI for OPEN_URI; Hush's package name for
    // GRANT_SECURE_SETTINGS. The others take none.
    public static final int STOP_READ_FRONT = 1, ALLOW_AUDIO = 2, OPEN_AND_ALLOW = 3, RESTART = 4,
            MEDIA_PLAY = 5, KEY_PLAY = 6, OPEN_URI = 7, REPAIR_MUTE = 8, UNFREEZE = 9, IS_FROZEN = 10,
            MUTED_STATE = 11, FRONT = 12, HOME = 13, OPEN_APP = 14, RESET_AUDIO = 15,
            ALLOW_AND_UNFREEZE = 16, GRANT_SECURE_SETTINGS = 17;

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.$/]{1,255}");
    private static final Pattern PLAYABLE = Pattern.compile("spotify:(playlist|album):[A-Za-z0-9]{1,64}");

    // Android 15+ mutes media apps that play from the background without a foreground service
    // (CONTROL_AUDIO app-ops); see BackgroundAudio.
    private static final String[] AUDIO_OPS = {"CONTROL_AUDIO", "CONTROL_AUDIO_PARTIAL", "TAKE_AUDIO_FOCUS"};
    static final String ALLOW = appops("allow");
    static final String RESET = appops("default");
    private static final String STOP = "am force-stop " + arg(SPOTIFY);
    // The temporary allowlist lets the new Spotify start its playback service from the background;
    // without it Spotify plays with no notification or lock-screen controls.
    static final String ALLOWLIST = "cmd deviceidle tempwhitelist -d 30000 " + arg(SPOTIFY) + " >/dev/null 2>&1";
    private static final String UNFREEZE_CMD = "am unfreeze --sticky " + arg(SPOTIFY) + " >/dev/null 2>&1";
    // The most recent task's entry in the recents list: the app on screen (or behind the lock screen).
    static final String RECENT_TOP = "dumpsys activity recents | sed -n '/Recent #0/,/Recent #1/p' | head -40";
    private static final String HOME_CMD = "am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1";

    private Commands() {}

    // The command line for an action. Throws IllegalArgumentException for an unknown action or an
    // argument that is not allowed.
    public static String build(int action, String a) {
        switch (action) {
            case STOP_READ_FRONT:   // reads the app in front, then stops Spotify right after
                return "R=$(" + RECENT_TOP + "); echo \"$R\"; " + STOP + "; case \"$R\" in *=" + SPOTIFY
                        + "/*) " + open(a) + "; echo " + OPENED + ";; esac; " + ALLOWLIST;
            case ALLOW_AUDIO: return ALLOW;
            case OPEN_AND_ALLOW: return open(a) + "; " + ALLOW;
            case RESTART: return STOP + "; " + ALLOWLIST + "; " + open(a) + "; " + ALLOW;
            case MEDIA_PLAY: return "cmd media_session dispatch play";
            case KEY_PLAY: return "input keyevent 126";
            case OPEN_URI:
                return "am start -a android.intent.action.VIEW -p " + arg(SPOTIFY) + " -d " + spotifyUri(a) + " >/dev/null 2>&1";
            case REPAIR_MUTE: return ALLOW + "; cmd media_session dispatch pause; sleep 0.3; cmd media_session dispatch play";
            case UNFREEZE: return UNFREEZE_CMD;
            case IS_FROZEN: return "am isfrozen " + arg(SPOTIFY) + " 2>/dev/null";
            case MUTED_STATE:   // the mute state of Spotify's playing audio ("mutedState:none", ...)
                return "P=$(pidof " + arg(SPOTIFY) + " | cut -d' ' -f1); [ -n \"$P\" ] && dumpsys audio"
                        + " | grep -E \"u/pid:[0-9]+/$P \" | grep 'state:started' | grep -oE 'mutedState:[a-z_]+' | head -1";
            case FRONT: return RECENT_TOP;
            case HOME: return HOME_CMD;
            case OPEN_APP:   // NEW_TASK | RESET_TASK_IF_NEEDED: resumes the existing task instead of a fresh start
                return "am start -f 0x10200000 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "
                        + arg(a) + " >/dev/null 2>&1";
            case RESET_AUDIO: return RESET;
            case ALLOW_AND_UNFREEZE: return ALLOW + "; " + UNFREEZE_CMD;
            case GRANT_SECURE_SETTINGS: return "pm grant " + arg(a) + " android.permission.WRITE_SECURE_SETTINGS 2>&1";
            default: throw new IllegalArgumentException("unknown action " + action);
        }
    }

    private static String open(String component) {
        return "am start -n " + arg(component) + " >/dev/null 2>&1";
    }

    static String openCommand(String component) { return open(component); }

    private static String appops(String mode) {
        StringBuilder b = new StringBuilder();
        for (String op : AUDIO_OPS) {
            if (b.length() > 0) b.append("; ");
            b.append("cmd appops set ").append(arg(SPOTIFY)).append(' ').append(op).append(' ').append(mode)
                    .append(" 2>/dev/null");
        }
        return b.toString();
    }

    // A package or component name as a single-quoted shell word. Anything else is rejected, so a
    // hostile name (e.g. another app's activity) can never inject a command.
    public static String arg(String s) {
        if (s == null || !SAFE.matcher(s).matches()) throw new IllegalArgumentException("unsafe shell argument");
        return "'" + s + "'";
    }

    // A Spotify playlist or album URI as a single-quoted shell word; anything else is rejected.
    public static String spotifyUri(String s) {
        if (s == null || !PLAYABLE.matcher(s).matches()) throw new IllegalArgumentException("unsupported URI");
        return "'" + s + ":play'";
    }

    public static boolean playable(String s) { return s != null && PLAYABLE.matcher(s).matches(); }
}
