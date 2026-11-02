package io.coldmew.hush;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

// Android counts only an explicit "allow" as allowed in the background; "default" is restricted too
// (seen on a real phone: Spotify was in Android's background-restricted list with "default").
public class SpotifyBackgroundTest {
    @Test public void allowed() {
        assertEquals(Boolean.FALSE, SpotifyBackground.parse("RUN_ANY_IN_BACKGROUND: allow"));
        assertEquals(Boolean.FALSE, SpotifyBackground.parse("RUN_ANY_IN_BACKGROUND: allow; time=+2m ago"));
        assertEquals(Boolean.FALSE, SpotifyBackground.parse("No operations.\nDefault mode: allow"));
    }

    @Test public void restricted() {
        assertEquals(Boolean.TRUE, SpotifyBackground.parse("RUN_ANY_IN_BACKGROUND: ignore"));
        assertEquals(Boolean.TRUE, SpotifyBackground.parse("RUN_ANY_IN_BACKGROUND: default"));
        assertEquals(Boolean.TRUE, SpotifyBackground.parse("RUN_ANY_IN_BACKGROUND: deny"));
    }

    @Test public void unknown() {
        assertNull(SpotifyBackground.parse(null));
        assertNull(SpotifyBackground.parse(""));
        assertNull(SpotifyBackground.parse("Error: unknown operation string"));
    }
}
