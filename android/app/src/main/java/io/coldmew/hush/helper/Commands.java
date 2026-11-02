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
    // GRANT_SECURE_SETTINGS and HUSH_USERS; "<user id>/<Hush's package>" for REMOVE_HUSH_COPY. The
    // others take none.
    public static final int STOP_READ_FRONT = 1, ALLOW_AUDIO = 2, OPEN_AND_ALLOW = 3, RESTART = 4,
            MEDIA_PLAY = 5, KEY_PLAY = 6, OPEN_URI = 7, REPAIR_MUTE = 8, UNFREEZE = 9, IS_FROZEN = 10,
            MUTED_STATE = 11, FRONT = 12, HOME = 13, OPEN_APP = 14, RESET_AUDIO = 15,
            ALLOW_AND_UNFREEZE = 16, GRANT_SECURE_SETTINGS = 17, USB_DEBUGGING_STATE = 18, USB_DEBUGGING_ON = 19,
            SPOTIFY_HOLDERS = 20, MUTE_SPOTIFY = 21, UNMUTE_SPOTIFY = 22, RESUMED_APP = 23,
            START_ALLOW = 24, SPOTIFY_FGS = 25, SPOTIFY_BG_STATE = 26, SPOTIFY_BG_ALLOW = 27,
            HUSH_USERS = 28, REMOVE_HUSH_COPY = 29, UNMUTE_MUSIC = 30, SPOTIFY_EXIT = 31;

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.$/]{1,255}");
    private static final Pattern PLAYABLE = Pattern.compile("spotify:(playlist|album):[A-Za-z0-9]{1,64}");
    private static final Pattern USER = Pattern.compile("[0-9]{1,5}");

    // Android 15+ mutes media apps that play from the background without a foreground service
    // (CONTROL_AUDIO app-ops); see BackgroundAudio.
    private static final String[] AUDIO_OPS = {"CONTROL_AUDIO", "CONTROL_AUDIO_PARTIAL", "TAKE_AUDIO_FOCUS"};
    static final String ALLOW = appops("allow");
    static final String RESET = appops("default");
    private static final String STOP = "am force-stop " + arg(SPOTIFY);
    // The temporary allowlist lets the new Spotify start its playback service from the background;
    // without it Spotify plays with no notification or lock-screen controls, as a cached app that
    // Android may freeze or kill. Android grants that right only when the entry is new: a force-stop
    // takes the right away but leaves the entry (e.g. one Android added for a headset button press),
    // and adding to an existing entry only extends it. So the entry is removed and added again.
    static final String ALLOWLIST = "cmd deviceidle tempwhitelist -r " + arg(SPOTIFY) + " >/dev/null 2>&1; "
            + "cmd deviceidle tempwhitelist -d 30000 " + arg(SPOTIFY) + " >/dev/null 2>&1";
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
            // Right before the stopped Spotify is started again: a fresh allowlist entry (see
            // ALLOWLIST; the one set with the stop can be gone again by now) and the audio app-ops.
            case START_ALLOW: return ALLOWLIST + "; " + ALLOW;
            case OPEN_AND_ALLOW: return ALLOWLIST + "; " + open(a) + "; " + ALLOW;
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
            // USB debugging keeps Android's debugging service (and with it Hush's helper) running when
            // Wi-Fi drops and Android switches Wireless debugging off. Apps cannot read it on newer
            // Android versions, so the helper reads and sets it.
            case USB_DEBUGGING_STATE: return "settings get global adb_enabled";
            case USB_DEBUGGING_ON: return "settings put global adb_enabled 1";
            // Android Auto's stable connections to Spotify's content providers (it holds one while it
            // loads an item, e.g. at the start of an ad). Android kills an app that holds one when
            // Spotify is stopped, so a skip waits until there is none. One line per connection.
            // System UI holds them too at times, but Android never kills it for this (persistent).
            case SPOTIFY_HOLDERS:
                return "dumpsys activity providers 2>/dev/null | awk '/ContentProviderRecord/{p=index($0, \"com.spotify.music/\")>0} "
                        + "p && /->/ && index($0, \"com.google.android.projection.gearhead\")>0 && $0 ~ \" s[1-9][0-9]*/\" {print}'";
            // Silences Spotify's own playback (Android's PLAY_AUDIO app-op), wherever it is played:
            // the phone's volume does not reach Android Auto, which takes Spotify's audio directly.
            case MUTE_SPOTIFY: return "appops set " + arg(SPOTIFY) + " PLAY_AUDIO ignore";
            case UNMUTE_SPOTIFY: return "appops set " + arg(SPOTIFY) + " PLAY_AUDIO allow";
            // The activity on screen, for phones whose recents list Hush cannot read (see Skipper).
            case RESUMED_APP: return "dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'";
            // Read-only: how many of Spotify's services run in the foreground (its playback service
            // does while it plays normally). 0 after a restart means Android refused it.
            case SPOTIFY_FGS: return "dumpsys activity services " + arg(SPOTIFY) + " | grep -c 'isForeground=true'";
            // Spotify's "background usage" (Battery > Restricted sets it to ignore): anything but
            // allow keeps a restarted Spotify from its playback service. See SpotifyBackground.
            case SPOTIFY_BG_STATE: return "appops get " + arg(SPOTIFY) + " RUN_ANY_IN_BACKGROUND";
            case SPOTIFY_BG_ALLOW: return "appops set " + arg(SPOTIFY) + " RUN_ANY_IN_BACKGROUND allow";
            // Read-only: why Spotify's main process last ended ("reason=5 (APP CRASH(NATIVE))", ...).
            case SPOTIFY_EXIT:
                return "dumpsys activity exit-info " + arg(SPOTIFY) + " | grep -m1 'process=" + SPOTIFY + " '";
            // Read-only: the Android users Hush is installed for, with when it arrived there, and
            // when it was last updated (see UpdateCopies).
            case HUSH_USERS:
                return "dumpsys package " + arg(a) + " | grep -E '^ *(User [0-9]+: |firstInstallTime=|lastUpdateTime=)'";
            // Turns the music volume back on the way the volume keys do (system-level), only if it is
            // muted: the mute key toggles, so it must never be pressed on an unmuted stream.
            case UNMUTE_MUSIC:
                return "dumpsys audio | grep -m1 -A1 '^- STREAM_MUSIC' | grep -q 'Muted: true' && input keyevent 164";
            // Removes Hush from one other Android user only (an app-clone space it was copied into).
            case REMOVE_HUSH_COPY: {
                String[] p = a == null ? new String[0] : a.split("/", 2);
                if (p.length != 2 || !USER.matcher(p[0]).matches()) throw new IllegalArgumentException("bad user");
                return "pm uninstall --user " + p[0] + " " + arg(p[1]) + " 2>&1";
            }
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
