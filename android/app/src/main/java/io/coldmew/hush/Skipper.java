package io.coldmew.hush;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Restart-skip: force-stopping Spotify drops the ad break it holds in memory; a cold start then
// resumes the queue without it. What this relies on (see docs/HOW-IT-WORKS.md):
//  - force-stop cancels Spotify's media-button PendingIntent, so a PLAY key sent before Spotify has
//    re-created its media session goes nowhere. We wait for the new session, then send PLAY.
//  - If Spotify was in the background, it is started through its media-button receiver and never
//    shows on screen. If it was in front (or the button does not work), its activity is started;
//    that does not wake the screen, and whatever was in front is brought back as soon as Spotify's
//    new session exists. The phone is left as it was: no wake-up, no sleep key, no lock.
//  - A cold start occasionally resumes an older playlist. The playlist that was playing before the
//    ad is remembered and started again if Spotify comes back somewhere else.
final class Skipper {
    private static final AtomicBoolean busy = new AtomicBoolean();
    static volatile long lastFailAt;
    // Only the skip thread uses these (see busy): when music started, and the timeline of the
    // current skip ("session 0.6 s, ready 2.9 s, playing 3.3 s") for the activity log.
    private static long playingAt;
    private static final StringBuilder steps = new StringBuilder();
    // Debug builds only: run the playlist correction even when nothing changed, to test it.
    static volatile boolean debugForcePlaylistFix;
    // Debug builds only: skip the media-button start, to test the activity-start fallback.
    static volatile boolean debugNoButton;
    private static final String DEBUG_OTHER = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M";
    private static String debugPrev;

    private enum Result { OK, AD, NO_SESSION, NOT_PLAYING }

    private Skipper() {}

    static boolean busy() { return busy.get(); }

    static boolean start(Context ctx, boolean test) {
        if (!busy.compareAndSet(false, true)) return false;
        Context c = ctx.getApplicationContext();
        new Thread(() -> {
            try { run(c, test); }
            catch (Throwable e) { Events.add(c, Events.FAIL, "Skip error: " + e.getClass().getSimpleName()); }
            finally { busy.set(false); AdWatcher.recheck(); }
        }, "hush-skip").start();
        return true;
    }

    private static void run(Context c, boolean test) {
        long t0 = SystemClock.elapsedRealtime();
        steps.setLength(0);
        Prefs prefs = new Prefs(c);
        Hush.unmuteIfNeeded(c);   // in case the mute fallback was active
        String context = AdWatcher.lastContext;   // the playlist/album to come back to

        boolean screenOn = c.getSystemService(PowerManager.class).isInteractive();
        boolean unlocked = screenOn && !c.getSystemService(KeyguardManager.class).isKeyguardLocked();
        String stop = "am force-stop " + Shell.arg(Hush.SPOTIFY);
        String openSpotify = "am start -n " + Shell.arg(launchComponent(c)) + " >/dev/null 2>&1";
        // The temporary allowlist lets the new Spotify start its playback service from the
        // background; without it Spotify plays with no notification or lock-screen controls.
        String allowlist = "cmd deviceidle tempwhitelist -d 30000 " + Shell.arg(Hush.SPOTIFY) + " >/dev/null 2>&1";
        // Spotify is started through its media-button receiver, the way a headset button starts it:
        // it resumes playback and posts its notification at once, and in the background it never
        // comes to the screen. The plain activity start (and PLAY once its session settles) is the
        // fallback when the button does not bring Spotify back.
        boolean noButton = BuildConfig.DEBUG && debugNoButton;
        debugNoButton = false;
        ComponentName button = noButton ? null : mediaButtonReceiver(c);

        // Everything above is prepared before Spotify is stopped: the ad is audible until then.
        // One shell call reads the app in front (known even while the phone is locked) and stops
        // Spotify right after. If Spotify was the app in front, its screen is opened in the same
        // instant, so the home screen does not show while Spotify restarts, and the shell reports
        // that with OPENED. Otherwise Spotify was playing in the background and stays there.
        Set<Object> old = tokens(c);
        String out = Shell.sh("R=$(" + RECENT_TOP + "); echo \"$R\"; " + stop + "; case \"$R\" in *=" + Hush.SPOTIFY
                + "/*) " + openSpotify + "; echo " + OPENED + ";; esac; " + allowlist);
        boolean spotifyInFront = out.contains(OPENED);
        String prev = spotifyInFront ? Hush.SPOTIFY : frontPackage(out);
        String where = !screenOn ? "screen off" : !unlocked ? "locked" : spotifyInFront ? "in Spotify" : "in " + label(c, prev);

        Result r = Result.NO_SESSION;
        String audio = "", note = "";
        long musicAt = 0;   // when music was back; what the user waits for
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (attempt > 1) old = tokens(c);
            boolean viaButton = attempt == 1 && button != null;
            if (viaButton) {   // Spotify plays as soon as it starts here, so the app-ops come first
                Shell.sh(BackgroundAudio.ALLOW);
                pressPlay(c, button);
            } else if (attempt == 1) {   // the app-ops overlap Spotify's start here (~0.1 s)
                Shell.sh((spotifyInFront ? "" : openSpotify + "; ") + BackgroundAudio.ALLOW);
            } else {
                Shell.sh(stop + "; " + allowlist + "; " + openSpotify + "; " + BackgroundAudio.ALLOW);
            }
            MediaController mc = waitSession(c, old, viaButton ? 3000 : 8000);
            log(t0, "attempt " + attempt + (viaButton ? " (button)" : "") + " session=" + (mc != null));
            step(t0, (attempt == 1 ? "" : "retry ") + (mc != null ? "session" : "no session") + (viaButton ? " (button)" : ""));
            if (mc == null) { r = Result.NO_SESSION; continue; }
            BackgroundAudio.unfreeze();   // the new process exists now
            if (!viaButton && !spotifyInFront) restore(c, prev);   // before PLAY: Spotify is only briefly in front
            r = resume(c, old, 9000, t0);
            log(t0, "resume " + r);
            if (r == Result.OK) {
                musicAt = playingAt;
                note = fixPlaylist(c, old, context, spotifyInFront, prev, t0);
                audio = keepAudible(c, old, t0);
                break;
            }
        }
        // Spotify may have come to the front again after a failed attempt.
        if (!spotifyInFront && Hush.SPOTIFY.equals(frontPackage())) restore(c, prev);

        double secs = ((musicAt > 0 ? musicAt : SystemClock.elapsedRealtime()) - t0) / 1000.0;
        String s = String.format(Locale.US, "%.1f s", secs);
        String tail = " (" + where + ")" + note + audioNote(audio)
                + (secs > 6 || r != Result.OK ? " · steps: " + steps : "");   // where a slow skip lost time
        if (r == Result.OK) {
            if (test) Events.add(c, Events.INFO, "Test restart OK: music back in " + s + tail);
            else {
                prefs.add(Prefs.SKIPPED, 1);
                prefs.add(Prefs.RESUME_MS, (long) (secs * 1000));
                Events.add(c, Events.SKIP, "Ad break skipped, music back in " + s + tail);
            }
        } else {
            if (!test) lastFailAt = System.currentTimeMillis();
            String why = r == Result.AD ? "the ad came back after restarting" : r == Result.NO_SESSION
                    ? "Spotify did not start" : "Spotify did not resume playback";
            if (!test) prefs.add(Prefs.FAILED, 1);
            Events.add(c, Events.FAIL, (test ? "Test restart failed: " : "Skip failed: ") + why + " (" + where + ")");
            Intent open = c.getPackageManager().getLaunchIntentForPackage(Hush.SPOTIFY);
            if (open != null && r != Result.AD)
                Hush.alert(c, c.getString(R.string.alert_resume_title), c.getString(R.string.alert_resume_text), open);
        }
        Hush.refreshNotification(c);
    }

    // Presses PLAY on Spotify's new session, alternating three delivery routes until it plays: the
    // system media-key dispatcher (which also grants Spotify a temporary foreground allowance,
    // avoiding Android's background-audio muting), a direct transport control, and an injected key
    // event. The first press goes out as soon as the session exists, while Spotify is still
    // connecting (it can take ~2 s); the next one the moment the session settles, then every 2 s.
    // PLAY is idempotent, so an extra press does no harm. Sets playingAt when music started.
    private static Result resume(Context c, Set<Object> old, long timeout, long t0) {
        long start = SystemClock.elapsedRealtime(), nextKick = 0, playingSince = 0;
        int kicks = 0, lastState = -1;
        while (SystemClock.elapsedRealtime() - start < timeout) {
            long now = SystemClock.elapsedRealtime();
            MediaController mc = latest(c, old);
            PlaybackState ps = mc == null ? null : mc.getPlaybackState();
            int st = ps == null ? PlaybackState.STATE_NONE : ps.getState();
            boolean changed = st != lastState;
            if (changed) { log(t0, "state " + st + " actions " + (ps == null ? 0 : ps.getActions())); lastState = st; }
            if (st == PlaybackState.STATE_PLAYING) {
                if (AdWatcher.isAd(mc)) return Result.AD;
                if (playingSince == 0) { playingSince = now; step(t0, "playing"); }
                else if (now - playingSince >= 600) { playingAt = playingSince; return Result.OK; }
            } else {
                playingSince = 0;
                boolean settled = st == PlaybackState.STATE_PAUSED || st == PlaybackState.STATE_STOPPED
                        || st == PlaybackState.STATE_ERROR || st == PlaybackState.STATE_NONE;
                if (settled && changed && mc != null) step(t0, st == PlaybackState.STATE_PAUSED ? "ready" : "state " + st);
                if (mc != null && (kicks == 0 || (settled && changed) || (now >= nextKick && (settled || now - start > 4000)))) {
                    log(t0, "kick " + kicks);
                    kick(mc, kicks++);
                    nextKick = now + 2000;
                }
            }
            sleep(100);
        }
        return Result.NOT_PLAYING;
    }

    private static void kick(MediaController mc, int n) {
        switch (n % 3) {
            case 0: Shell.sh("cmd media_session dispatch play"); break;
            case 1: mc.getTransportControls().play(); break;
            default: Shell.sh("input keyevent 126"); break;
        }
    }

    // If the restarted Spotify plays a different playlist/album than before the ad, start the one
    // that was playing again. Spotify (Free) ignores the session's play-from-URI command, but its
    // "<uri>:play" link works for playlists and albums. Returns a note for the activity log.
    @SuppressLint("WrongConstant")   // Spotify-specific metadata keys
    private static String fixPlaylist(Context c, Set<Object> old, String want, boolean spotifyInFront, String prev, long t0) {
        if (want == null) return "";
        MediaController mc = latest(c, old);
        MediaMetadata m = mc == null ? null : mc.getMetadata();
        String now = m == null ? null : m.getString(AdWatcher.KEY_CONTEXT);
        boolean force = BuildConfig.DEBUG && debugForcePlaylistFix;
        debugForcePlaylistFix = false;
        if (force && now != null && now.equals(want)) {   // test: switch to a public playlist and back
            want = now.equals(DEBUG_OTHER) && debugPrev != null ? debugPrev : DEBUG_OTHER;
            debugPrev = now;
        }
        log(t0, "context before " + want + " after " + now);
        if (now == null || now.equals(want)) return "";
        if (!Shell.playable(want)) return ", Spotify switched playlist";
        Shell.sh("am start -a android.intent.action.VIEW -p " + Shell.arg(Hush.SPOTIFY) + " -d "
                + Shell.spotifyUri(want) + " >/dev/null 2>&1");
        if (!spotifyInFront) { sleep(800); restore(c, prev); }
        long end = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < end) {
            sleep(250);
            mc = latest(c, old);
            m = mc == null ? null : mc.getMetadata();
            PlaybackState ps = mc == null ? null : mc.getPlaybackState();
            if (m != null && want.equals(m.getString(AdWatcher.KEY_CONTEXT))
                    && ps != null && ps.getState() == PlaybackState.STATE_PLAYING) return ", playlist restored";
        }
        return ", Spotify switched playlist";
    }

    // Android 15+ mutes an app that plays from the background without a foreground service, through
    // the CONTROL_AUDIO app-op. A restarted Spotify often has no such service yet, so Hush allows
    // the op for Spotify (BackgroundAudio). This watchdog only covers the rare case where the
    // system mutes Spotify anyway: it re-applies the op and cycles pause/play.
    private static String keepAudible(Context c, Set<Object> old, long t0) {
        long start = SystemClock.elapsedRealtime();
        int repairs = 0;
        String audio = "";
        while (SystemClock.elapsedRealtime() - start < 12000) {
            MediaController mc = latest(c, old);
            if (mc != null && AdWatcher.isAd(mc)) break;   // a new ad: let the watcher handle it
            audio = mutedState();
            if (audio.startsWith("mutedState:op") && repairs < 2) {
                log(t0, "muted by the system, repairing: " + audio);
                Shell.sh(BackgroundAudio.ALLOW + "; cmd media_session dispatch pause; sleep 0.3; cmd media_session dispatch play");
                repairs++;
            }
            sleep(1000);
        }
        if (BackgroundAudio.frozen()) {   // the unfreeze did not hold: try once more
            log(t0, "frozen by the system, unfreezing");
            Shell.sh(BackgroundAudio.UNFREEZE);
            audio = "frozen";
        }
        return audio;
    }

    private static String mutedState() {
        String pid = Shell.sh("pidof " + Shell.arg(Hush.SPOTIFY)).split("\\s+")[0];
        if (!pid.matches("[0-9]+")) return "";
        return Shell.sh("dumpsys audio | grep -E 'u/pid:[0-9]+/" + pid + " ' | grep 'state:started'"
                + " | grep -oE 'mutedState:[a-z_]+' | head -1");
    }

    private static String audioNote(String audio) {
        return audio.isEmpty() || audio.equals("mutedState:none") ? "" : " [" + audio + "]";
    }

    // Spotify's media-button receiver, looked up rather than hard-coded so a renamed class in a
    // Spotify update does not break skipping (the activity start is used if there is none).
    private static ComponentName mediaButtonReceiver(Context c) {
        List<ResolveInfo> r = c.getPackageManager().queryBroadcastReceivers(
                new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(Hush.SPOTIFY), 0);
        return r.isEmpty() ? null : new ComponentName(Hush.SPOTIFY, r.get(0).activityInfo.name);
    }

    // A PLAY key (down and up) to the stopped Spotify, which force-stop left in the "stopped" state.
    private static void pressPlay(Context c, ComponentName receiver) {
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            c.sendBroadcast(new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY)));
        }
    }

    private static String launchComponent(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(Hush.SPOTIFY);
        return i != null && i.getComponent() != null ? i.getComponent().flattenToShortString() : Hush.SPOTIFY + "/.MainActivity";
    }

    private static List<MediaController> sessions(Context c) {
        try {
            return c.getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(c, AdWatcher.class));
        } catch (SecurityException e) { return Collections.emptyList(); }
    }

    private static Set<Object> tokens(Context c) {
        Set<Object> s = new HashSet<>();
        for (MediaController mc : sessions(c)) if (Hush.SPOTIFY.equals(mc.getPackageName())) s.add(mc.getSessionToken());
        return s;
    }

    // Spotify's newest session that was not there before the restart.
    private static MediaController latest(Context c, Set<Object> old) {
        for (MediaController mc : sessions(c))
            if (Hush.SPOTIFY.equals(mc.getPackageName()) && !old.contains(mc.getSessionToken())) return mc;
        return null;
    }

    private static MediaController waitSession(Context c, Set<Object> old, long ms) {
        long end = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < end) {
            MediaController mc = latest(c, old);
            if (mc != null) return mc;
            sleep(100);
        }
        return null;
    }

    // In the top task's entry: its real activity, the component of its intent, or (home) its
    // activity. The task line's "A=" field is an affinity, which need not be a package name.
    private static final Pattern FRONT = Pattern.compile("(?:realActivity=|cmp=|I=)([A-Za-z0-9_.]+)/");

    // The most recent task's entry in the recents list: the app on screen (or behind the lock screen).
    private static final String RECENT_TOP = "dumpsys activity recents | sed -n '/Recent #0/,/Recent #1/p' | head -40";
    private static final String OPENED = "@@hush-opened-spotify";

    private static String frontPackage() { return frontPackage(Shell.sh(RECENT_TOP)); }

    // Package of the app in front, from RECENT_TOP's output.
    static String frontPackage(String recentTop) {
        Matcher m = FRONT.matcher(recentTop);
        return m.find() ? m.group(1) : null;
    }

    // Brings back the app that was in front. Starting an activity does not wake the screen, so this
    // also works while the phone is locked: Spotify is not left in front for the next unlock.
    private static void restore(Context c, String pkg) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo h = c.getPackageManager().resolveActivity(home, 0);
        Intent launch = pkg == null ? null : c.getPackageManager().getLaunchIntentForPackage(pkg);
        String goHome = "am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1";
        try {
            if (launch == null || launch.getComponent() == null || (h != null && pkg.equals(h.activityInfo.packageName)))
                Shell.sh(goHome);
            else   // NEW_TASK | RESET_TASK_IF_NEEDED: resumes the existing task instead of a fresh start
                Shell.sh("am start -f 0x10200000 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "
                        + Shell.arg(launch.getComponent().flattenToShortString()) + " >/dev/null 2>&1");
        } catch (IllegalArgumentException e) {   // unusual component name: use the home screen instead
            Shell.sh(goHome);
        }
    }

    private static String label(Context c, String pkg) {
        if (pkg == null) return "another app";
        try { return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return "another app"; }
    }

    private static void step(long t0, String what) {
        if (steps.length() > 0) steps.append(", ");
        steps.append(what).append(String.format(Locale.US, " %.1f s", (SystemClock.elapsedRealtime() - t0) / 1000.0));
    }

    private static void log(long t0, String msg) {
        if (BuildConfig.DEBUG) Log.d(Hush.TAG, "skip +" + (SystemClock.elapsedRealtime() - t0) + "ms " + msg);
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}
