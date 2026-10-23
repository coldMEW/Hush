package io.coldmew.hush;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Nothing that reaches the adb-level shell may carry shell syntax.
public class ShellTest {
    @Test public void quotesSafeNames() {
        assertEquals("'com.spotify.music'", Shell.arg("com.spotify.music"));
        assertEquals("'com.example/.Main$Inner'", Shell.arg("com.example/.Main$Inner"));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsQuote() { Shell.arg("a'; reboot; '"); }
    @Test(expected = IllegalArgumentException.class) public void rejectsSpace() { Shell.arg("a b"); }
    @Test(expected = IllegalArgumentException.class) public void rejectsSubstitution() { Shell.arg("$(id)"); }
    @Test(expected = IllegalArgumentException.class) public void rejectsNull() { Shell.arg(null); }

    @Test public void spotifyUris() {
        assertTrue(Shell.playable("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"));
        assertTrue(Shell.playable("spotify:album:4aawyAB9vmqN3uQ7FjRGTy"));
        assertFalse(Shell.playable("spotify:list:popular-release:artist_1"));
        assertFalse(Shell.playable("spotify:playlist:abc' ; reboot"));
        assertEquals("'spotify:playlist:abc:play'", Shell.spotifyUri("spotify:playlist:abc"));
    }
}
