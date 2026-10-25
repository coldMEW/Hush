package io.coldmew.hush;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

// The app in front decides how Spotify is restarted and what is brought back afterwards, so the
// recents entry must be read correctly in each form Android prints it.
public class FrontAppTest {
    @Test public void homeScreen() {
        assertEquals("com.google.android.apps.nexuslauncher", Skipper.frontPackage(
                "* Recent #0: Task{e60b0be #2 type=home I=com.google.android.apps.nexuslauncher/.NexusLauncherActivity}\n"
                + "  intent={act=android.intent.action.MAIN cat=[android.intent.category.HOME] flg=0x10000100 "
                + "cmp=com.google.android.apps.nexuslauncher/.NexusLauncherActivity}\n"));
    }

    @Test public void spotifyInFront() {
        assertEquals("com.spotify.music", Skipper.frontPackage(
                "* Recent #0: Task{3eb97aa #207 type=standard A=10415:com.spotify.music}\n"
                + "  intent={act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10200000 "
                + "cmp=com.spotify.music/.MainActivity}\n  realActivity=com.spotify.music/.MainActivity\n"));
    }

    @Test public void affinityIsNotAPackage() {   // "A=" is a task affinity, not the app
        assertEquals("org.example.app", Skipper.frontPackage(
                "* Recent #0: Task{1 #9 type=standard A=10001:shared.affinity}\n"
                + "  realActivity=org.example.app/.Main\n"));
    }

    @Test public void nothingUsable() {
        assertNull(Skipper.frontPackage(""));
        assertNull(Skipper.frontPackage("* Recent #0: Task{1 #9 type=standard A=10001:x}\n"));
    }
}
