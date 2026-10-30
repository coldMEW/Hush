package io.coldmew.hush;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.VpnService;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

// The master switch. Turning Hush on starts the DNS filter (if enabled) and arms the ad watcher;
// turning it off stops the filter, disarms the watcher and restores any volume Hush muted.
final class Hush {
    static final String TAG = "Hush";
    static final String SPOTIFY = "com.spotify.music";
    static final String CH_STATUS = "status", CH_ALERT = "alert", CH_PAIR = "pair";
    static final int NOTIF_STATUS = 1, NOTIF_ALERT = 2, NOTIF_SHIZUKU = 3, NOTIF_HELPER = 4;
    static final String ACTION_OFF = "io.coldmew.hush.TURN_OFF", ACTION_RESUME = "io.coldmew.hush.RESUME";

    private Hush() {}

    static void setEnabled(Context c, boolean on) {
        Prefs p = new Prefs(c);
        p.enabled(on);
        if (on) {
            Events.add(c, Events.INFO, "Hush turned on");
            applyDns(c);
            BackgroundAudio.apply(c);
            HelperControl.keepRunningOffWifiAsync(c);
            try { NotificationListenerService.requestRebind(new ComponentName(c, AdWatcher.class)); } catch (Exception ignored) {}
        } else {
            Events.add(c, Events.INFO, "Hush turned off");
            stopVpn(c);
            BackgroundAudio.resetWhenIdle();
            unmuteIfNeeded(c);
        }
        refreshNotification(c);
        AdWatcher.recheck();
    }

    // Starts or stops the VPN to match the settings. Returns false if VPN permission is missing.
    static boolean applyDns(Context c) {
        Prefs p = new Prefs(c);
        if (!p.enabled() || !p.dns()) { stopVpn(c); return true; }
        if (VpnService.prepare(c) != null) return false;
        c.startForegroundService(new Intent(c, DnsVpnService.class));
        return true;
    }

    static void stopVpn(Context c) {
        if (DnsVpnService.running) c.startService(new Intent(c, DnsVpnService.class).setAction(DnsVpnService.ACTION_STOP));
    }

    // Muting Spotify itself runs a shell command, which takes a moment: done off the caller's
    // thread, one at a time so a mute and the unmute after it never swap places.
    private static final java.util.concurrent.ExecutorService SPOTIFY_AUDIO =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    // Mutes an ad. Android Auto plays Spotify's audio itself, so the phone's music volume does not
    // reach the car: there Spotify is muted directly (needs the helper or Shizuku). Elsewhere the
    // music volume is muted, which works without any setup.
    static void muteAd(Context c) {
        Prefs p = new Prefs(c);
        if (Shell.ready() && Car.connected(c)) {
            p.mutedSpotify(true);
            SPOTIFY_AUDIO.execute(() -> Shell.run(io.coldmew.hush.helper.Commands.MUTE_SPOTIFY));
        } else {
            c.getSystemService(AudioManager.class).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            p.mutedByUs(true);
        }
    }

    static void unmuteIfNeeded(Context c) {
        Prefs p = new Prefs(c);
        if (p.mutedSpotify() && Shell.ready()) {   // without a backend it stays marked and is undone later
            p.mutedSpotify(false);
            SPOTIFY_AUDIO.execute(() -> Shell.run(io.coldmew.hush.helper.Commands.UNMUTE_SPOTIFY));
        }
        if (!p.mutedByUs()) return;
        c.getSystemService(AudioManager.class).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        p.mutedByUs(false);
    }

    static boolean listenerEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(new ComponentName(c, AdWatcher.class).flattenToString());
    }

    static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel s = new NotificationChannel(CH_STATUS, c.getString(R.string.notif_channel_status), NotificationManager.IMPORTANCE_LOW);
        s.setShowBadge(false);
        nm.createNotificationChannel(s);
        nm.createNotificationChannel(new NotificationChannel(CH_ALERT, c.getString(R.string.notif_channel_alert), NotificationManager.IMPORTANCE_DEFAULT));
        // The pairing code field pops up over Settings, so the code can be typed without leaving the
        // pairing popup (leaving it ends pairing). Used only for that one notification.
        nm.createNotificationChannel(new NotificationChannel(CH_PAIR, c.getString(R.string.notif_channel_pair), NotificationManager.IMPORTANCE_HIGH));
    }

    static Notification statusNotification(Context c) {
        Prefs p = new Prefs(c);
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent off = PendingIntent.getBroadcast(c, 1, new Intent(c, Receiver.class).setAction(ACTION_OFF), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(c, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat_hush)
                .setColor(c.getColor(R.color.accent))
                .setContentTitle(c.getString(R.string.notif_title))
                .setContentText(SleepTimer.active(c) ? SleepTimer.describe(c)
                        : c.getResources().getQuantityString(R.plurals.notif_text, (int) Math.min(p.get(Prefs.SKIPPED), Integer.MAX_VALUE),
                                p.get(Prefs.SKIPPED), p.get(Prefs.MUTED)))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, c.getString(R.string.notif_stop), off).build())
                .build();
    }

    // The status notification is owned by the VPN service while it runs; otherwise posted here.
    static void refreshNotification(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (new Prefs(c).enabled()) nm.notify(NOTIF_STATUS, statusNotification(c));
        else nm.cancel(NOTIF_STATUS);
    }

    // Xiaomi, Redmi and POCO phones (MIUI / HyperOS) add their own background limits ("Autostart",
    // battery saver) on top of Android's.
    static boolean isXiaomi() { return "xiaomi".equalsIgnoreCase(Build.MANUFACTURER); }

    static void alert(Context c, String title, String text, Intent tap) {
        PendingIntent pi = PendingIntent.getActivity(c, 2, tap, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        c.getSystemService(NotificationManager.class).notify(NOTIF_ALERT, new Notification.Builder(c, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_hush).setColor(c.getColor(R.color.accent))
                .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi).setAutoCancel(true).build());
    }

    private static boolean skipAlerted;

    // The first ad that has to be muted because skipping is not ready leads to the fix, once:
    // Hush's own helper is started again if it can be (or its "tap to resume" notice is shown);
    // a Shizuku user gets the reminder to start Shizuku (it stops at every reboot); everyone else
    // gets the one-time setup.
    static void skipAlert(Context c) {
        if (skipAlerted) return;
        skipAlerted = true;
        Prefs p = new Prefs(c);
        if (HelperControl.supported() && p.paired()) {
            HelperControl.ensure(c, p.autoRestart() && HelperControl.canToggleDebugging(c), true);
            return;
        }
        boolean shizuku = Shell.installed(c);
        Intent open = shizuku ? c.getPackageManager().getLaunchIntentForPackage(Shell.SHIZUKU) : null;
        if (open == null) open = new Intent(c, HelperControl.supported() ? HelperSetupActivity.class : ShizukuSetupActivity.class);
        boolean lostPairing = HelperControl.lastResult() == HelperControl.Result.NOT_PAIRED;   // was paired before
        String text = c.getString(shizuku ? R.string.alert_shizuku_text : lostPairing ? R.string.helper_notice_pair : R.string.alert_setup_text);
        PendingIntent pi = PendingIntent.getActivity(c, 3, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        c.getSystemService(NotificationManager.class).notify(NOTIF_SHIZUKU, new Notification.Builder(c, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_hush).setColor(c.getColor(R.color.accent))
                .setContentTitle(c.getString(shizuku ? R.string.alert_shizuku_title : R.string.helper_notice_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi).setAutoCancel(true).build());
    }

    // Skipping works again: the notices are no longer needed.
    static void clearSkipAlert(Context c) {
        skipAlerted = false;
        c.getSystemService(NotificationManager.class).cancel(NOTIF_SHIZUKU);
        HelperControl.cancelNotice(c);
    }

    // Notification buttons, boot and app-update events.
    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACTION_OFF.equals(a)) setEnabled(c, false);
            else if (Updater.ACTION_LATER.equals(a)) Updater.cancelNotice(c);   // this version is not announced again
            else if (SleepTimer.ACTION_SLEEP.equals(a)) SleepTimer.fireFromAlarm(c);
            else if (ACTION_RESUME.equals(a)) {   // "tap to resume skipping"
                PendingResult done = goAsync();
                HelperControl.resumeFromNotice(c, done::finish);
            } else if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
                Prefs p = new Prefs(c);
                if (Intent.ACTION_BOOT_COMPLETED.equals(a)) p.mutedByUs(false);   // reboot clears mute
                else unmuteIfNeeded(c);   // an update killed us mid-ad: don't leave the phone muted
                if (p.enabled()) { applyDns(c); refreshNotification(c); }
                // The helper does not survive a reboot, and an update replaces it.
                HelperControl.onBoot(c);
            }
        }
    }
}
