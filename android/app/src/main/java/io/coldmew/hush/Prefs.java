package io.coldmew.hush;

import android.content.Context;
import android.content.SharedPreferences;

// Settings and statistics, in one SharedPreferences file.
final class Prefs {
    static final String MODE_RESTART = "restart", MODE_MUTE = "mute", MODE_OFF = "off";
    static final String SEEN = "seen", SKIPPED = "skipped", MUTED = "muted", FAILED = "failed",
            RESUME_MS = "resume_ms", DNS_HITS = "dns_hits";

    private final SharedPreferences sp;

    Prefs(Context c) { sp = c.getApplicationContext().getSharedPreferences("hush", Context.MODE_PRIVATE); }

    boolean enabled() { return sp.getBoolean("enabled", false); }
    void enabled(boolean v) { sp.edit().putBoolean("enabled", v).apply(); }

    boolean dns() { return sp.getBoolean("dns", true); }
    void dns(boolean v) { sp.edit().putBoolean("dns", v).apply(); }

    String mode() { return sp.getString("mode", MODE_RESTART); }
    void mode(String v) { sp.edit().putString("mode", v).apply(); }

    boolean mutedByUs() { return sp.getBoolean("muted_by_us", false); }
    void mutedByUs(boolean v) { sp.edit().putBoolean("muted_by_us", v).commit(); }

    // Sleep timer: wall-clock end time, or "end of track" with the song it started on.
    long sleepAt() { return sp.getLong("sleep_at", 0); }
    void sleepAt(long v) { sp.edit().putLong("sleep_at", v).putBoolean("sleep_eot", false).apply(); }
    boolean sleepEndOfTrack() { return sp.getBoolean("sleep_eot", false); }
    String sleepTrack() { return sp.getString("sleep_track", null); }
    void sleepEndOfTrack(boolean on, String track) {
        sp.edit().putBoolean("sleep_eot", on).putString("sleep_track", track).putLong("sleep_at", 0).apply();
    }
    void sleepClear() { sp.edit().remove("sleep_at").remove("sleep_eot").remove("sleep_track").apply(); }

    long get(String stat) { return sp.getLong("stat_" + stat, 0); }
    private static final Object STATS = new Object();

    void add(String stat, long n) {
        synchronized (STATS) { sp.edit().putLong("stat_" + stat, get(stat) + n).commit(); }
    }

    void resetStats() {
        SharedPreferences.Editor e = sp.edit();
        for (String k : sp.getAll().keySet()) if (k.startsWith("stat_")) e.remove(k);
        e.apply();
    }
}
