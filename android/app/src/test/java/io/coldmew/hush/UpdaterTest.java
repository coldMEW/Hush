package io.coldmew.hush;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdaterTest {
    @Test public void newerComparesNumbers() {
        assertTrue(Updater.newer("2.0.4", "2.0.3"));
        assertTrue(Updater.newer("2.0.10", "2.0.9"));
        assertTrue(Updater.newer("2.1", "2.0.9"));
        assertTrue(Updater.newer("3.0.0", "2.9.9"));
        assertFalse(Updater.newer("2.0.3", "2.0.3"));
        assertFalse(Updater.newer("2.0.2", "2.0.3"));
        assertFalse(Updater.newer("", "2.0.3"));
        assertFalse(Updater.newer("2.0.3", "2.0.3.0"));
    }

    @Test public void summaryIsFirstLineWithoutMarkdown() {
        assertEquals("Fixes skipping on mobile data and Android Auto.",
                Updater.summary("**Fixes skipping on mobile data and Android Auto.**\r\n\r\n### Fixed\r\n- more"));
        assertEquals("Hush is now the only app you need.", Updater.summary("\n\n## Hush is now the only app you need.\nrest"));
        assertEquals("See the setup video", Updater.summary("- See the [setup video](https://example.com)"));
        assertEquals("", Updater.summary(""));
    }

    @Test public void summaryIsShortened() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 30; i++) s.append("word ");
        String out = Updater.summary(s.toString());
        assertTrue(out.length() <= 140);
        assertTrue(out.endsWith("…"));
    }
}
