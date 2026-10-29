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
    // Only a 1.0.x install counts: 2.0 stores keys 1.0.x never had (pairing, helper, setup), and a
    // 2.0 user who never touched the switch also has no "dns" key. Treating those as 1.0.x turned the
    // filter on by itself after the first restart (2.0.0 to 2.0.2). The answer is stored, so it is
    // decided once.
    static void keepDnsDefault(Context c) {
        SharedPreferences sp = c.getSharedPreferences("hush", Context.MODE_PRIVATE);
        if (sp.contains("dns")) return;
        boolean from1x = !sp.getAll().isEmpty();
        for (String k : new String[]{"paired", "dev_step", "helper_boot", "auto_restart", "phone_test"})
            if (sp.contains(k)) from1x = false;
        sp.edit().putBoolean("dns", from1x).apply();
    }
    void dns(boolean v) { sp.edit().putBoolean("dns", v).apply(); }

    String mode() { return sp.getString("mode", MODE_RESTART); }
    void mode(String v) { sp.edit().putString("mode", v).apply(); }
    // Updates: the daily check (on unless turned off), the version announced last (each version is
    // announced once), and the latest release seen, for the Update row.
    // Set right before Hush installs its own update, so the new version restarts its helper by itself.
    boolean updating() { return sp.getBoolean("updating", false); }
    void updating(boolean v) { sp.edit().putBoolean("updating", v).commit(); }
    boolean updateCheck() { return sp.getBoolean("update_check", true); }
    void updateCheck(boolean v) { sp.edit().putBoolean("update_check", v).apply(); }
    String updateNotified() { return sp.getString("update_notified", ""); }
    void updateNotified(String v) { sp.edit().putString("update_notified", v).apply(); }
    String latestVersion() { return sp.getString("latest_version", ""); }
    String latestSummary() { return sp.getString("latest_summary", ""); }
    void latest(String version, String summary) { sp.edit().putString("latest_version", version).putString("latest_summary", summary).apply(); }
    // What Skip does while Android Auto is connected: skip, mute or do nothing (MODE_* values).
    String carMode() { return sp.getString("car_mode", MODE_RESTART); }
    void carMode(String v) { sp.edit().putString("car_mode", v).apply(); }

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
    // The user tapped Allow for notification access at least once: if access is still missing, Android
    // may have blocked the switch as a restricted setting (apps installed outside the Play Store).
    boolean listenerAsked() { return sp.getBoolean("listener_asked", false); }
    void listenerAsked(boolean v) { sp.edit().putBoolean("listener_asked", v).apply(); }
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
