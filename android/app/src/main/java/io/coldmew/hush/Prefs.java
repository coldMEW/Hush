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

    // The DNS filter is off for new installs (skipping does not need it, and it takes Android's only
    // VPN slot). Until 1.0.3 it was on by default and stored only when changed, so an existing
    // install without the key keeps it on (see keepDnsDefault).
    boolean dns() { return sp.getBoolean("dns", false); }

    // From App.onCreate, before anything is stored: an update from 1.0.x keeps the filter as it was.
    static void keepDnsDefault(Context c) {
        SharedPreferences sp = c.getSharedPreferences("hush", Context.MODE_PRIVATE);
        if (!sp.contains("dns") && !sp.getAll().isEmpty()) sp.edit().putBoolean("dns", true).apply();
    }
    void dns(boolean v) { sp.edit().putBoolean("dns", v).apply(); }

    String mode() { return sp.getString("mode", MODE_RESTART); }
    void mode(String v) { sp.edit().putString("mode", v).apply(); }

    boolean mutedByUs() { return sp.getBoolean("muted_by_us", false); }
    void mutedByUs(boolean v) { sp.edit().putBoolean("muted_by_us", v).commit(); }

    // Hush's own helper: paired with Wireless debugging; automatic restart after a reboot (opt-in);
    // the per-phone test result (TEST_* below).
    static final String TEST_NONE = "", TEST_SAFE = "safe", TEST_NEEDS_ON = "needs_on";
    boolean paired() { return sp.getBoolean("paired", false); }
    void paired(boolean v) { sp.edit().putBoolean("paired", v).apply(); }
    // The user went through the Developer options step (newer Android versions do not tell apps).
    boolean devStep() { return sp.getBoolean("dev_step", false); }
    void devStep(boolean v) { sp.edit().putBoolean("dev_step", v).apply(); }
    boolean autoRestart() { return sp.getBoolean("auto_restart", false); }
    void autoRestart(boolean v) { sp.edit().putBoolean("auto_restart", v).apply(); }
    String phoneTest() { return sp.getString("phone_test", TEST_NONE); }
    void phoneTest(String v) { sp.edit().putString("phone_test", v).commit(); }
    // Android's boot count when the helper last connected: a different count means the phone has
    // restarted since, so no helper can still be running.
    int helperBoot() { return sp.getInt("helper_boot", -1); }
    void helperBoot(int v) { sp.edit().putInt("helper_boot", v).apply(); }

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
