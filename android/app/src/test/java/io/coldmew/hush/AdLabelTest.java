package io.coldmew.hush;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Labels seen on Spotify 9.1 ads, and song titles that must not be mistaken for ads.
public class AdLabelTest {
    @Test public void adLabels() {
        assertTrue(AdWatcher.isAdLabel("Advertisement"));
        assertTrue(AdWatcher.isAdLabel("Advertisement • 1 of 3"));
        assertTrue(AdWatcher.isAdLabel("Advertisement • Skip after 30s"));
        assertTrue(AdWatcher.isAdLabel("  advertisement "));
        assertTrue(AdWatcher.isAdLabel("Werbung · 1 von 2"));
    }

    @Test public void songsAreNotAds() {
        assertFalse(AdWatcher.isAdLabel("Advertisement Song"));
        assertFalse(AdWatcher.isAdLabel("The Advertisement"));
        assertFalse(AdWatcher.isAdLabel("1 of 3"));
        assertFalse(AdWatcher.isAdLabel("Reklamation"));
    }
}
