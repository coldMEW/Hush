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
    private static final String ALLOWLIST = "cmd deviceidle tempwhitelist -d 30000 'com.spotify.music' >/dev/null 2>&1";
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
        assertEquals(OPEN + "; " + ALLOW, Commands.build(Commands.OPEN_AND_ALLOW, SPOTIFY_MAIN));
        assertEquals(STOP + "; " + ALLOWLIST + "; " + OPEN + "; " + ALLOW, Commands.build(Commands.RESTART, SPOTIFY_MAIN));
        assertEquals("cmd media_session dispatch play", Commands.build(Commands.MEDIA_PLAY, null));
        assertEquals("input keyevent 126", Commands.build(Commands.KEY_PLAY, null));
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

    @Test(expected = IllegalArgumentException.class) public void unknownAction() { Commands.build(999, null); }
    @Test(expected = IllegalArgumentException.class) public void hostileComponent() { Commands.build(Commands.OPEN_APP, "x;reboot"); }
    @Test(expected = IllegalArgumentException.class) public void hostileUri() { Commands.build(Commands.OPEN_URI, "spotify:album:x;id"); }
    @Test(expected = IllegalArgumentException.class) public void missingComponent() { Commands.build(Commands.RESTART, null); }
}
