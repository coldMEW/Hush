package io.coldmew.hush;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.text.DateFormat;
import java.util.Date;

// Hush's own sleep timer. Spotify keeps its sleep timer only in memory (no alarm, nothing in its
// media session), so a restart-skip loses it and Hush cannot read it. This timer lives in Hush and
// survives skips: when it runs out, Spotify is paused.
//  - Minutes: an in-process countdown held by a partial wake lock, plus an inexact system alarm as
//    a backup in case Hush's process is killed.
//  - End of track: Spotify is paused as soon as the current song changes (to the next song or to
//    an ad).
final class SleepTimer {
    static final int END_OF_TRACK = -1;
    static final int[] CHOICES = {5, 10, 15, 30, 45, 60, END_OF_TRACK};
    static final String ACTION_SLEEP = "io.coldmew.hush.SLEEP";

    private static final Handler main = new Handler(Looper.getMainLooper());
    @SuppressLint("StaticFieldLeak")   // the application context, never an activity
    private static Context app;
    private static PowerManager.WakeLock lock;
    private static final Runnable tick = () -> check(app);

    private SleepTimer() {}

    static boolean active(Context c) { Prefs p = new Prefs(c); return p.sleepAt() > 0 || p.sleepEndOfTrack(); }

    static void set(Context c, int minutes) {
        cancelQuietly(c);
        Prefs p = new Prefs(c);
        if (minutes == END_OF_TRACK) {
            p.sleepEndOfTrack(true, AdWatcher.lastTrack);
            Events.add(c, Events.INFO, "Sleep timer: pause at the end of this song");
        } else {
            long at = System.currentTimeMillis() + minutes * 60_000L;
            p.sleepAt(at);
            schedule(c, at);
            Events.add(c, Events.INFO, "Sleep timer: pause in " + minutes + " min");
        }
        Hush.refreshNotification(c);
    }

    static void cancel(Context c) {
        if (!active(c)) return;
        cancelQuietly(c);
        Events.add(c, Events.INFO, "Sleep timer turned off");
        Hush.refreshNotification(c);
    }

    // After Hush's process restarts: picks up a timer that is still running.
    static void restore(Context c) {
        long at = new Prefs(c).sleepAt();
        if (at <= 0) return;
        if (System.currentTimeMillis() - at > 60_000) cancelQuietly(c);   // ran out long ago (phone was off)
        else schedule(c, at);
    }

    // From AdWatcher on every Spotify change. Returns true if it paused playback.
    static boolean onTrack(Context c, String mediaId) {
        Prefs p = new Prefs(c);
        if (!p.sleepEndOfTrack() || mediaId == null || Skipper.busy()) return false;
        String start = p.sleepTrack();
        if (start == null) { p.sleepEndOfTrack(true, mediaId); return false; }   // no song was known yet
        if (start.equals(mediaId)) return false;
        fire(c, "end of song");
        return true;
    }

    static void fireFromAlarm(Context c) { check(c); }

    static String describe(Context c) {
        Prefs p = new Prefs(c);
        if (p.sleepEndOfTrack()) return c.getString(R.string.sleep_desc_eot);
        if (p.sleepAt() > 0) {
            long left = Math.max(0, p.sleepAt() - System.currentTimeMillis());
            return c.getString(R.string.sleep_desc_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(p.sleepAt())),
                    (int) Math.ceil(left / 60_000.0));
        }
        return c.getString(R.string.sleep_desc_off);
    }

    private static void schedule(Context c, long at) {
        app = c.getApplicationContext();
        long ms = Math.max(0, at - System.currentTimeMillis());
        main.removeCallbacks(tick);
        main.postAtTime(tick, SystemClock.uptimeMillis() + ms);
        wakeLock(app).acquire(ms + 60_000);
        app.getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(app));
    }

    private static void check(Context c) {
        if (c == null) return;
        long at = new Prefs(c).sleepAt();
        if (at <= 0) return;
        if (System.currentTimeMillis() < at - 1000) { schedule(c, at); return; }   // early alarm: wait
        fire(c, "time is up");
    }

    // Pauses Spotify. A skip in progress would start playback again, so wait for it to finish.
    private static void fire(Context c, String why) {
        if (Skipper.busy()) { main.postDelayed(() -> fire(c, why), 1500); return; }
        cancelQuietly(c);
        wakeLock(c).acquire(12_000);   // covers the re-checks below
        if (!pauseSpotify(c)) {
            AudioManager am = c.getSystemService(AudioManager.class);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
        }
        // A pause sent while the next song is still loading can be overtaken when it starts:
        // check again a few times and pause once more if it is playing.
        for (long ms : new long[]{2000, 5000, 10000}) main.postDelayed(() -> pauseIfPlaying(c), ms);
        Events.add(c, Events.INFO, "Sleep timer: Spotify paused (" + why + ")");
        Hush.refreshNotification(c);
    }

    private static void pauseIfPlaying(Context c) {
        try {
            for (MediaController mc : c.getSystemService(MediaSessionManager.class)
                    .getActiveSessions(new ComponentName(c, AdWatcher.class))) {
                android.media.session.PlaybackState ps = mc.getPlaybackState();
                if (Hush.SPOTIFY.equals(mc.getPackageName()) && ps != null
                        && ps.getState() == android.media.session.PlaybackState.STATE_PLAYING)
                    mc.getTransportControls().pause();
            }
        } catch (SecurityException ignored) {}
    }

    private static boolean pauseSpotify(Context c) {
        try {
            for (MediaController mc : c.getSystemService(MediaSessionManager.class)
                    .getActiveSessions(new ComponentName(c, AdWatcher.class))) {
                if (Hush.SPOTIFY.equals(mc.getPackageName())) { mc.getTransportControls().pause(); return true; }
            }
        } catch (SecurityException ignored) {}   // no notification access
        return false;
    }

    private static PowerManager.WakeLock wakeLock(Context c) {
        if (lock == null) {
            lock = c.getApplicationContext().getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hush:sleep-timer");
            lock.setReferenceCounted(false);
        }
        return lock;
    }

    private static void cancelQuietly(Context c) {
        new Prefs(c).sleepClear();
        main.removeCallbacks(tick);
        if (lock != null && lock.isHeld()) lock.release();
        c.getSystemService(AlarmManager.class).cancel(alarm(c.getApplicationContext()));
    }

    private static PendingIntent alarm(Context c) {
        return PendingIntent.getBroadcast(c, 4, new Intent(c, Hush.Receiver.class).setAction(ACTION_SLEEP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
