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
//  - A time (a preset, or any custom time up to 23 h 59 min 59 s): for the last hour, an in-process
//    countdown held by a partial wake lock, plus an inexact system alarm as a backup in case Hush's
//    process is killed. Before that nothing keeps the phone awake: an alarm (and an in-process
//    callback) for the start of the last hour hands over to the countdown, so a long timer costs no
//    battery while the music is paused.
//  - End of track: Spotify is paused as soon as the current song changes (to the next song or to
//    an ad).
final class SleepTimer {
    static final int END_OF_TRACK = -1;
    static final int[] CHOICES = {5, 10, 15, 30, 45, 60, END_OF_TRACK};
    static final int MAX_SECONDS = (23 * 60 + 59) * 60 + 59;   // the custom time's limit
    static final String ACTION_SLEEP = "io.coldmew.hush.SLEEP";
    private static final long FINAL_MS = 60 * 60_000L;   // the precise, wake-locked last stretch

    private static final Handler main = new Handler(Looper.getMainLooper());
    @SuppressLint("StaticFieldLeak")   // the application context, never an activity
    private static Context app;
    private static PowerManager.WakeLock lock;
    // A pause is waiting for Spotify (main thread only); a new timer or "off" abandons it.
    private static boolean settling;
    private static int generation;
    private static final Runnable tick = () -> check(app);

    private SleepTimer() {}

    static boolean active(Context c) { Prefs p = new Prefs(c); return p.sleepAt() > 0 || p.sleepEndOfTrack(); }

    static void set(Context c, int minutes) {
        if (minutes != END_OF_TRACK) { setSeconds(c, minutes * 60); return; }
        cancelQuietly(c);
        new Prefs(c).sleepEndOfTrack(true, AdWatcher.lastTrack);
        Events.add(c, Events.INFO, "Sleep timer: pause at the end of this song");
        Hush.refreshNotification(c);
    }

    // A timer of any length from 1 second to 23 h 59 min 59 s.
    static void setSeconds(Context c, int seconds) {
        cancelQuietly(c);
        seconds = Math.max(1, Math.min(seconds, MAX_SECONDS));
        long at = System.currentTimeMillis() + seconds * 1000L;
        new Prefs(c).sleepAt(at);
        schedule(c, at);
        Events.add(c, Events.INFO, "Sleep timer: pause in " + span(c, seconds));
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

    // From AdWatcher on every Spotify change (main thread). Returns true if the song has changed;
    // the pause follows once the next song is playing (see pauseWhenPlaying).
    static boolean onTrack(Context c, String mediaId) {
        Prefs p = new Prefs(c);
        if (!p.sleepEndOfTrack() || mediaId == null || Skipper.busy()) return false;
        String start = p.sleepTrack();
        if (start == null) { p.sleepEndOfTrack(true, mediaId); return false; }   // no song was known yet
        if (start.equals(mediaId)) return false;
        fire(c.getApplicationContext(), "end of song");
        return true;
    }

    // From AdWatcher when an ad plays: with "end of song" set, the song is over. Returns true if the
    // pause was started (the ad is then paused rather than skipped).
    static boolean adAtEndOfSong(Context c) {
        if (settling) return true;   // the pause is on its way: leave the ad to it
        if (!new Prefs(c).sleepEndOfTrack()) return false;
        fire(c.getApplicationContext(), "end of song");
        return true;
    }

    // Spotify's playback state, or -1 without a Spotify session.
    private static int spotifyState(Context c) {
        try {
            for (MediaController mc : c.getSystemService(MediaSessionManager.class)
                    .getActiveSessions(new ComponentName(c, AdWatcher.class))) {
                android.media.session.PlaybackState ps = mc.getPlaybackState();
                if (Hush.SPOTIFY.equals(mc.getPackageName())) return ps == null ? -1 : ps.getState();
            }
        } catch (SecurityException ignored) {}
        return -1;
    }

    static void fireFromAlarm(Context c) { check(c); }

    static String describe(Context c) {
        Prefs p = new Prefs(c);
        if (p.sleepEndOfTrack()) return c.getString(R.string.sleep_desc_eot);
        if (p.sleepAt() > 0) {
            long left = Math.max(0, p.sleepAt() - System.currentTimeMillis());
            // Whole minutes as before; seconds only for the last 10 minutes.
            long seconds = left < 600_000 ? (long) Math.ceil(left / 1000.0) : (long) Math.ceil(left / 60_000.0) * 60;
            return c.getString(R.string.sleep_desc_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(p.sleepAt())),
                    span(c, seconds));
        }
        return c.getString(R.string.sleep_desc_off);
    }

    // "41 s", "2 min 34 s", "45 min", "1 h", "4 h 5 min" (seconds are left out from an hour up).
    static String span(Context c, long seconds) {
        long h = seconds / 3600, m = seconds / 60 % 60, s = seconds % 60;
        if (h > 0) return m == 0 ? c.getString(R.string.sleep_span_h, h) : c.getString(R.string.sleep_span_hm, h, m);
        if (m > 0) return s == 0 ? c.getString(R.string.sleep_span_m, m) : c.getString(R.string.sleep_span_ms, m, s);
        return c.getString(R.string.sleep_span_s, s);
    }

    // Arms the timer for "at". Within the last hour: the wake-locked countdown plus a backup alarm
    // for "at". Earlier: only a callback and an alarm for the start of the last hour, whichever
    // comes first calls check(), which arms the countdown.
    private static void schedule(Context c, long at) {
        app = c.getApplicationContext();
        long ms = Math.max(0, at - System.currentTimeMillis());
        main.removeCallbacks(tick);
        AlarmManager am = app.getSystemService(AlarmManager.class);
        if (ms <= FINAL_MS) {
            main.postAtTime(tick, SystemClock.uptimeMillis() + ms);
            wakeLock(app).acquire(ms + 60_000);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(app));
        } else {
            main.postAtTime(tick, SystemClock.uptimeMillis() + ms - FINAL_MS);
            if (lock != null && lock.isHeld()) lock.release();
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at - FINAL_MS, alarm(app));
        }
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
        if (settling) return;   // a pause is already on its way
        if (Skipper.busy()) { main.postDelayed(() -> fire(c, why), 1500); return; }
        cancelQuietly(c);
        settling = true;
        int gen = generation;
        wakeLock(c).acquire(20_000);   // covers the wait and the re-checks below
        pauseWhenPlaying(c, why, SystemClock.uptimeMillis(), gen);
    }

    // A pause that reaches Spotify while it is switching songs (buffering or connecting) leaves it
    // stuck: silent, but play no longer works until the song is skipped. So the pause waits until
    // Spotify is playing (at the end of a song: the first moment of the next one), and is sent at
    // the latest after 10 s. Nothing is sent if Spotify is already paused or stopped.
    private static void pauseWhenPlaying(Context c, String why, long since, int gen) {
        if (gen != generation) return;   // the timer was set again or turned off meanwhile
        long waited = SystemClock.uptimeMillis() - since;
        int st = spotifyState(c);
        boolean playing = st == android.media.session.PlaybackState.STATE_PLAYING;
        boolean switching = st == android.media.session.PlaybackState.STATE_BUFFERING
                || st == android.media.session.PlaybackState.STATE_CONNECTING;
        // Right after a song change the state may still be the old song's "playing" for a moment.
        boolean ready = playing && (waited >= 150 || !"end of song".equals(why));
        if (!ready && (switching || playing) && waited < 10_000) {
            main.postDelayed(() -> pauseWhenPlaying(c, why, since, gen), 100);
            return;
        }
        settling = false;
        if (playing || switching) {
            if (!pauseSpotify(c)) {
                AudioManager am = c.getSystemService(AudioManager.class);
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
            }
            // In case a song start overtakes the pause: pause again until Spotify has reported
            // "paused" once. After that, playing again is the user's choice and is left alone.
            main.postDelayed(() -> confirmPaused(c, SystemClock.uptimeMillis() + 4000, gen), 250);
        }
        Events.add(c, Events.INFO, "Sleep timer: Spotify paused (" + why + ")");
        Hush.refreshNotification(c);
    }

    private static void confirmPaused(Context c, long until, int gen) {
        if (gen != generation) return;   // a new timer was set meanwhile
        int st = spotifyState(c);
        if (st == android.media.session.PlaybackState.STATE_PAUSED
                || st == android.media.session.PlaybackState.STATE_STOPPED || st == -1) return;   // done
        if (st == android.media.session.PlaybackState.STATE_PLAYING) pauseIfPlaying(c);
        if (SystemClock.uptimeMillis() < until) main.postDelayed(() -> confirmPaused(c, until, gen), 250);
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
        generation++;
        settling = false;
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
