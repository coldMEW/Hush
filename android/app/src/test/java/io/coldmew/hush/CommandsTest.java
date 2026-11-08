package io.coldmew.hush;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.coldmew.hush.helper.Commands;
import org.junit.Test;

// Nothing that reaches the adb-level shell may carry shell syntax, and the command lines must stay
// exactly the ones measured in 1.0.3 (the helper and Shizuku both run these).
public class CommandsTest {
    private static final String SPOTIFY_MAIN = "com.spotify.music/.MainActivity";
    private static final String ALLOW = "cmd appops set 'com.spotify.music' CONTROL_AUDIO allow 2>/dev/null; "
            + "cmd appops set 'com.spotify.music' CONTROL_AUDIO_PARTIAL allow 2>/dev/null; "
            + "cmd appops set 'com.spotify.music' TAKE_AUDIO_FOCUS allow 2>/dev/null";
    private static final String OPEN = "am start -n 'com.spotify.music/.MainActivity' >/dev/null 2>&1";
    // Removed and added again: Android grants the playback-service right only for a new entry.
    private static final String ALLOWLIST = "cmd deviceidle tempwhitelist -r 'com.spotify.music' >/dev/null 2>&1; "
            + "cmd deviceidle tempwhitelist -d 30000 'com.spotify.music' >/dev/null 2>&1";
    private static final String STOP = "am force-stop 'com.spotify.music'";

    @Test public void quotesSafeNames() {
        assertEquals("'com.spotify.music'", Commands.arg("com.spotify.music"));
        assertEquals("'com.example/.Main$Inner'", Commands.arg("com.example/.Main$Inner"));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsQuote() { Commands.arg("a'; reboot; '"); }
    @Test(expected = IllegalArgumentException.class) public void rejectsSpace() { Commands.arg("a b"); }
    @Test(expected = IllegalArgumentException.class) public void rejectsSubstitution() { Commands.arg("$(id)"); }
    @Test(expected = IllegalArgumentException.class) public void rejectsNull() { Commands.arg(null); }

    @Test public void spotifyUris() {
        assertTrue(Commands.playable("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"));
        assertTrue(Commands.playable("spotify:album:4aawyAB9vmqN3uQ7FjRGTy"));
        assertFalse(Commands.playable("spotify:list:popular-release:artist_1"));
        assertFalse(Commands.playable("spotify:playlist:abc' ; reboot"));
        assertEquals("'spotify:playlist:abc:play'", Commands.spotifyUri("spotify:playlist:abc"));
    }

    @Test public void skipCommandsMatch103() {
        assertEquals("R=$(dumpsys activity recents | sed -n '/Recent #0/,/Recent #1/p' | head -40); echo \"$R\"; "
                + STOP + "; case \"$R\" in *=com.spotify.music/*) " + OPEN + "; echo @@hush-opened-spotify;; esac; " + ALLOWLIST,
                Commands.build(Commands.STOP_READ_FRONT, SPOTIFY_MAIN));
        assertEquals(ALLOW, Commands.build(Commands.ALLOW_AUDIO, null));
        assertEquals(ALLOWLIST + "; " + OPEN + "; " + ALLOW, Commands.build(Commands.OPEN_AND_ALLOW, SPOTIFY_MAIN));
        assertEquals(ALLOWLIST + "; " + ALLOW, Commands.build(Commands.START_ALLOW, "x; reboot"));
        assertEquals(STOP + "; " + ALLOWLIST + "; " + OPEN + "; " + ALLOW, Commands.build(Commands.RESTART, SPOTIFY_MAIN));
        assertEquals("cmd media_session dispatch play", Commands.build(Commands.MEDIA_PLAY, null));
        assertEquals("input keyevent 126 2>&1", Commands.build(Commands.KEY_PLAY, null));
        assertEquals(ALLOW + "; cmd media_session dispatch pause; sleep 0.3; cmd media_session dispatch play",
                Commands.build(Commands.REPAIR_MUTE, null));
    }

    @Test public void audioAndRestoreCommandsMatch103() {
        String unfreeze = "am unfreeze --sticky 'com.spotify.music' >/dev/null 2>&1";
        assertEquals(unfreeze, Commands.build(Commands.UNFREEZE, null));
        assertEquals(ALLOW + "; " + unfreeze, Commands.build(Commands.ALLOW_AND_UNFREEZE, null));
        assertEquals(ALLOW.replace(" allow ", " default "), Commands.build(Commands.RESET_AUDIO, null));
        assertEquals("am isfrozen 'com.spotify.music' 2>/dev/null", Commands.build(Commands.IS_FROZEN, null));
        assertEquals("am start -a android.intent.action.VIEW -p 'com.spotify.music' -d 'spotify:album:x1:play' >/dev/null 2>&1",
                Commands.build(Commands.OPEN_URI, "spotify:album:x1"));
        assertEquals("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1",
                Commands.build(Commands.HOME, null));
        assertEquals("am start -f 0x10200000 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "
                + "'com.example/.Main' >/dev/null 2>&1", Commands.build(Commands.OPEN_APP, "com.example/.Main"));
        assertEquals("dumpsys activity recents | sed -n '/Recent #0/,/Recent #1/p' | head -40", Commands.build(Commands.FRONT, null));
    }

    // Only reads and switches on USB debugging; the argument is ignored, so nothing else can run.
    @Test public void usbDebuggingCommandsAreFixed() {
        assertEquals("settings get global adb_enabled", Commands.build(Commands.USB_DEBUGGING_STATE, null));
        assertEquals("settings put global adb_enabled 1", Commands.build(Commands.USB_DEBUGGING_ON, null));
        assertEquals("settings put global adb_enabled 1", Commands.build(Commands.USB_DEBUGGING_ON, "x; reboot"));
    }

    // Muting Spotify (Android Auto) touches only Spotify's PLAY_AUDIO, whatever the argument.
    @Test public void spotifyMuteCommandsAreFixed() {
        assertEquals("appops set 'com.spotify.music' PLAY_AUDIO ignore", Commands.build(Commands.MUTE_SPOTIFY, null));
        assertEquals("appops set 'com.spotify.music' PLAY_AUDIO allow", Commands.build(Commands.UNMUTE_SPOTIFY, "x; reboot"));
    }

    @Test public void spotifyHoldersOnlyReads() {
        String c = Commands.build(Commands.SPOTIFY_HOLDERS, "x; reboot");
        assertTrue(c.startsWith("dumpsys activity providers 2>/dev/null | awk '"));
        assertFalse(c.contains("reboot"));
    }

    @Test public void mutedStateIsOneCall() {
        String c = Commands.build(Commands.MUTED_STATE, null);
        assertTrue(c.startsWith("P=$(pidof 'com.spotify.music' | cut -d' ' -f1); [ -n \"$P\" ] && dumpsys audio"));
        assertTrue(c.endsWith("| grep 'state:started' | grep -oE 'mutedState:[a-z_]+' | head -1"));
    }

    // Read-only checks of Spotify's state, and the one setting Fix changes: arguments are ignored.
    @Test public void spotifyServiceAndBackgroundCommandsAreFixed() {
        assertEquals("dumpsys activity services 'com.spotify.music' | grep -c 'isForeground=true'",
                Commands.build(Commands.SPOTIFY_FGS, "x; reboot"));
        assertEquals("appops get 'com.spotify.music' RUN_ANY_IN_BACKGROUND", Commands.build(Commands.SPOTIFY_BG_STATE, "x; reboot"));
        assertEquals("appops set 'com.spotify.music' RUN_ANY_IN_BACKGROUND allow", Commands.build(Commands.SPOTIFY_BG_ALLOW, "x; reboot"));
    }

    // Read-only, Spotify's main process only (not its web view or other processes).
    @Test public void spotifyExitIsFixed() {
        assertEquals("dumpsys activity exit-info 'com.spotify.music' | grep -m1 'process=com.spotify.music '",
                Commands.build(Commands.SPOTIFY_EXIT, "x; reboot"));
    }

    // Real exit-info lines: only a crash or a kill counts, never a stop.
    @Test public void spotifyCrashed() {
        assertTrue(Skipper.crashed("          process=com.spotify.music reason=5 (APP CRASH(NATIVE)) subreason=0 (UNKNOWN) status=6"));
        assertTrue(Skipper.crashed("          process=com.spotify.music reason=4 (APP CRASH(EXCEPTION)) subreason=0 (UNKNOWN) status=0"));
        assertTrue(Skipper.crashed("          process=com.spotify.music reason=3 (LOW_MEMORY) subreason=0 (UNKNOWN) status=0"));
        assertFalse(Skipper.crashed("          process=com.spotify.music reason=10 (USER REQUESTED) subreason=21 (FORCE STOP) status=0"));
        assertFalse(Skipper.crashed("          process=com.spotify.music reason=10 (USER REQUESTED) subreason=22 (REMOVE TASK) status=0"));
        assertFalse(Skipper.crashed(""));
        assertFalse(Skipper.crashed(null));
    }

    // The mute key toggles: it may only be pressed while the music stream is muted.
    @Test public void unmuteMusicOnlyWhenMuted() {
        assertEquals("dumpsys audio | grep -m1 -A1 '^- STREAM_MUSIC' | grep -q 'Muted: true' && input keyevent 164 2>&1",
                Commands.build(Commands.UNMUTE_MUSIC, "x; reboot"));
    }

    @Test public void hushCopyCommands() {
        assertEquals("dumpsys package 'io.coldmew.hush' | grep -E '^ *(User [0-9]+: |firstInstallTime=|lastUpdateTime=)'",
                Commands.build(Commands.HUSH_USERS, "io.coldmew.hush"));
        assertEquals("pm uninstall --user 95 'io.coldmew.hush' 2>&1", Commands.build(Commands.REMOVE_HUSH_COPY, "95/io.coldmew.hush"));
    }

    @Test(expected = IllegalArgumentException.class) public void hostileCopyUser() { Commands.build(Commands.REMOVE_HUSH_COPY, "0;reboot/io.coldmew.hush"); }
    @Test(expected = IllegalArgumentException.class) public void hostileCopyPackage() { Commands.build(Commands.REMOVE_HUSH_COPY, "95/x y"); }
    @Test(expected = IllegalArgumentException.class) public void copyNeedsUser() { Commands.build(Commands.REMOVE_HUSH_COPY, "io.coldmew.hush"); }
    @Test(expected = IllegalArgumentException.class) public void hostileHushUsers() { Commands.build(Commands.HUSH_USERS, "$(id)"); }
    // Xiaomi's Autostart switch: set and read for one quoted package only (the helper allows only
    // Hush and Spotify, see Actions).
    @Test public void miuiAutostartCommands() {
        assertEquals("appops set 'com.spotify.music' 10008 allow 2>&1; appops get 'com.spotify.music' 10008 2>&1",
                Commands.build(Commands.MIUI_AUTOSTART, "com.spotify.music"));
        assertEquals("appops get 'io.coldmew.hush' 10008 2>&1", Commands.build(Commands.MIUI_AUTOSTART_STATE, "io.coldmew.hush"));
    }

    // Spotify's players on this phone, by Spotify's app id; takes no argument (any is ignored).
    @Test public void spotifyHere() {
        String cmd = Commands.build(Commands.SPOTIFY_HERE, null);
        assertEquals(cmd, Commands.build(Commands.SPOTIFY_HERE, "$(reboot)"));
        assertTrue(cmd.contains("cmd package list packages -U 'com.spotify.music'"));
        assertTrue(cmd.endsWith("grep -c 'state:started'"));
    }

    @Test(expected = IllegalArgumentException.class) public void hostileAutostart() { Commands.build(Commands.MIUI_AUTOSTART, "x; reboot"); }
    @Test(expected = IllegalArgumentException.class) public void hostileAutostartState() { Commands.build(Commands.MIUI_AUTOSTART_STATE, "$(id)"); }
    @Test(expected = IllegalArgumentException.class) public void autostartNeedsPackage() { Commands.build(Commands.MIUI_AUTOSTART, null); }

    // Real answers of `appops get <pkg> 10008` on HyperOS 2, and of a phone without the op.
    @Test public void miuiAutostartState() {
        assertTrue(Xiaomi.allowed("MIUIOP(10008): allow; time=+19m30s539ms ago; rejectTime=+2m28s703ms ago"));
        assertFalse(Xiaomi.allowed("MIUIOP(10008): ignore; time=+19m30s478ms ago; rejectTime=+2m28s642ms ago"));
        assertFalse(Xiaomi.allowed("Error: Unknown operation string: 10008"));
        assertFalse(Xiaomi.allowed(""));
        assertFalse(Xiaomi.allowed(null));
    }

    @Test public void inputRefusalIsNoticed() {
        Xiaomi.inputRefused = false;
        Xiaomi.noteInput("");
        assertFalse(Xiaomi.inputRefused);
        Xiaomi.noteInput("java.lang.SecurityException: Injecting input events requires the caller (or the source of the instrumentation, if any) to have the INJECT_EVENTS permission.");
        assertTrue(Xiaomi.inputRefused);
        Xiaomi.inputRefused = false;
    }

    @Test(expected = IllegalArgumentException.class) public void unknownAction() { Commands.build(999, null); }
    @Test(expected = IllegalArgumentException.class) public void hostileComponent() { Commands.build(Commands.OPEN_APP, "x;reboot"); }
    @Test(expected = IllegalArgumentException.class) public void hostileUri() { Commands.build(Commands.OPEN_URI, "spotify:album:x;id"); }
    @Test(expected = IllegalArgumentException.class) public void missingComponent() { Commands.build(Commands.RESTART, null); }
}
