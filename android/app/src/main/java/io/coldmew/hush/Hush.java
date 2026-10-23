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
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

// The master switch. Turning Hush on starts the DNS filter (if enabled) and arms the ad watcher;
// turning it off stops the filter, disarms the watcher and restores any volume Hush muted.
final class Hush {
    static final String TAG = "Hush";
    static final String SPOTIFY = "com.spotify.music";
    static final String CH_STATUS = "status", CH_ALERT = "alert";
    static final int NOTIF_STATUS = 1, NOTIF_ALERT = 2, NOTIF_SHIZUKU = 3;
    static final String ACTION_OFF = "io.coldmew.hush.TURN_OFF";

    private Hush() {}

    static void setEnabled(Context c, boolean on) {
        Prefs p = new Prefs(c);
        p.enabled(on);
        if (on) {
            Events.add(c, Events.INFO, "Hush turned on");
            applyDns(c);
            try { NotificationListenerService.requestRebind(new ComponentName(c, AdWatcher.class)); } catch (Exception ignored) {}
        } else {
            Events.add(c, Events.INFO, "Hush turned off");
            stopVpn(c);
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

    static void unmuteIfNeeded(Context c) {
        Prefs p = new Prefs(c);
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
                        : c.getString(R.string.notif_text, p.get(Prefs.SKIPPED), p.get(Prefs.MUTED)))
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

    static void alert(Context c, String title, String text, Intent tap) {
        PendingIntent pi = PendingIntent.getActivity(c, 2, tap, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        c.getSystemService(NotificationManager.class).notify(NOTIF_ALERT, new Notification.Builder(c, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_hush).setColor(c.getColor(R.color.accent))
                .setContentTitle(title).setContentText(text).setContentIntent(pi).setAutoCancel(true).build());
    }

    private static boolean shizukuAlerted;

    // Shizuku (without root) stops at every reboot. The first ad that has to be muted because of
    // that posts one reminder with a button that opens Shizuku.
    static void shizukuAlert(Context c) {
        if (shizukuAlerted) return;
        shizukuAlerted = true;
        Intent open = c.getPackageManager().getLaunchIntentForPackage(Shell.SHIZUKU);
        if (open == null) open = new Intent(c, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(c, 3, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        c.getSystemService(NotificationManager.class).notify(NOTIF_SHIZUKU, new Notification.Builder(c, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_hush).setColor(c.getColor(R.color.accent))
                .setContentTitle(c.getString(R.string.alert_shizuku_title))
                .setContentText(c.getString(R.string.alert_shizuku_text))
                .setStyle(new Notification.BigTextStyle().bigText(c.getString(R.string.alert_shizuku_text)))
                .setContentIntent(pi).setAutoCancel(true).build());
    }

    static void clearShizukuAlert(Context c) {
        shizukuAlerted = false;
        c.getSystemService(NotificationManager.class).cancel(NOTIF_SHIZUKU);
    }

    // Notification "Turn off" button, boot and app-update events.
    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACTION_OFF.equals(a)) setEnabled(c, false);
            else if (SleepTimer.ACTION_SLEEP.equals(a)) SleepTimer.fireFromAlarm(c);
            else if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
                Prefs p = new Prefs(c);
                if (Intent.ACTION_BOOT_COMPLETED.equals(a)) p.mutedByUs(false);   // reboot clears mute
                else unmuteIfNeeded(c);   // an update killed us mid-ad: don't leave the phone muted
                if (p.enabled()) { applyDns(c); refreshNotification(c); }
            }
        }
    }
}
