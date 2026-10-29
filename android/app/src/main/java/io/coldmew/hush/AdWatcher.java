package io.coldmew.hush;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Watches Spotify's media session (notification access is what lets an app read other apps'
// sessions). When Spotify reports an ad, the break is skipped by restarting Spotify (Skipper), or
// muted when skipping is not ready (no helper or Shizuku), the mode is "mute", or a skip just failed.
public class AdWatcher extends NotificationListenerService {
    // Every Spotify ad carries a media ID of this form, whatever its title or language.
    static final String AD_ID = "spotify:ad:";
    static final String KEY_CONTEXT = "com.spotify.music.extra.CONTEXT_URI";
    static final String KEY_ADVERTISEMENT = "android.media.metadata.ADVERTISEMENT";
    // Fallback: the label Spotify shows on ads ("Advertisement", or "Advertisement • 1 of 3").
    private static final String[] AD_WORDS = {
            "advertisement", "anuncio", "anúncio", "publicidad", "werbung", "publicité", "pubblicità",
            "advertentie", "reklam", "reklama", "mainos", "annonse", "annons", "iklan", "quảng cáo",
            "реклама", "広告", "광고", "广告", "廣告", "विज्ञापन", "إعلان", "פרסומת", "โฆษณา",
    };

    private static volatile AdWatcher instance;
    // The last song that played and the playlist/album it came from, so that a restart that comes
    // back somewhere else can be corrected (see Skipper).
    static volatile String lastTrack, lastContext;

    private final Map<MediaSession.Token, MediaController.Callback> watched = new HashMap<>();
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private volatile MediaController spotify;
    // Ad-break state is shared: Android can connect a new listener instance before the old one
    // is disconnected (e.g. after access is revoked and granted again), and a break must still be
    // handled only once.
    private static final Object LOCK = new Object();
    private static boolean inBreak, muting;

    @Override public void onListenerConnected() {
        AdWatcher old = instance;
        if (old != null && old != this) old.detach();
        detach();   // in case this instance was connected before
        instance = this;
        MediaSessionManager msm = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, AdWatcher.class);
        sessionsListener = this::onSessions;
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, me);
            onSessions(msm.getActiveSessions(me));
        } catch (SecurityException e) {
            // Notification access was turned off at this very moment: Android still delivers the
            // connect, but sessions may no longer be read. Not an error; the app shows the setup step.
            sessionsListener = null;
            if (instance == this) instance = null;
        }
    }

    @Override public void onListenerDisconnected() {
        if (instance == this) instance = null;
        detach();
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        detach();
        super.onDestroy();
    }

    // Stops watching: removes the sessions listener and every callback this instance registered.
    private synchronized void detach() {
        if (sessionsListener != null) {
            try { getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(sessionsListener); }
            catch (Exception ignored) {}
            sessionsListener = null;
        }
        for (Map.Entry<MediaSession.Token, MediaController.Callback> e : watched.entrySet()) {
            try { new MediaController(this, e.getKey()).unregisterCallback(e.getValue()); } catch (Exception ignored) {}
        }
        watched.clear();
        spotify = null;
    }

    private synchronized void onSessions(List<MediaController> list) {
        if (list == null) return;
        Map<MediaSession.Token, MediaController.Callback> keep = new HashMap<>();
        for (MediaController c : list) {
            if (!Hush.SPOTIFY.equals(c.getPackageName())) continue;
            spotify = c;
            MediaController.Callback cb = watched.remove(c.getSessionToken());
            if (cb == null) {
                cb = new MediaController.Callback() {
                    @Override public void onMetadataChanged(MediaMetadata m) { changed(c); }
                    @Override public void onPlaybackStateChanged(PlaybackState s) { changed(c); }
                };
                c.registerCallback(cb);
            }
            keep.put(c.getSessionToken(), cb);
            check(c);
        }
        watched.clear();   // sessions that went away (e.g. Spotify was restarted)
        watched.putAll(keep);
    }

    private void changed(MediaController c) {
        if (instance != this) return;   // a stale instance that was replaced
        if (BuildConfig.DEBUG) Trace.log(this, c);
        check(c);
    }

    // Re-evaluates the current Spotify state, e.g. after a skip finished or Hush was switched.
    static void recheck() {
        AdWatcher w = instance;
        if (w != null && w.spotify != null) w.check(w.spotify);
    }

    static boolean isAd(MediaController c) { return isAd(c.getMetadata()); }

    @SuppressLint("WrongConstant")   // Spotify-specific metadata keys
    static boolean isAd(MediaMetadata m) {
        if (m == null) return false;
        String id = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (id != null && id.startsWith(AD_ID)) return true;
        if (m.getLong(KEY_ADVERTISEMENT) != 0) return true;   // standard flag, set by Spotify on ads
        for (String k : new String[]{MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE}) {
            CharSequence v = m.getText(k);
            if (v != null && isAdLabel(v.toString())) return true;
        }
        return false;
    }

    // "Advertisement" alone, or followed by a separator: "Advertisement • 1 of 3". A song that
    // merely starts with the word ("Advertisement Song") does not match.
    static boolean isAdLabel(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        for (String w : AD_WORDS) {
            if (!t.startsWith(w)) continue;
            String rest = t.substring(w.length()).trim();
            if (rest.isEmpty() || rest.startsWith("•") || rest.startsWith("·") || rest.startsWith("|") || rest.startsWith("-"))
                return true;
        }
        return false;
    }

    private static String describe(MediaMetadata m) {
        if (m == null) return "";
        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_TITLE), a = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        return (t == null ? "" : t) + (a == null || a.length() == 0 ? "" : " · " + a);
    }

    private void check(MediaController c) {
        synchronized (LOCK) { checkLocked(c); }
    }

    @SuppressLint("WrongConstant")   // Spotify-specific metadata keys
    private void checkLocked(MediaController c) {
        MediaMetadata m = c.getMetadata();
        boolean ad = isAd(m);
        PlaybackState ps = c.getPlaybackState();
        boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
        boolean starting = ps != null && (ps.getState() == PlaybackState.STATE_BUFFERING
                || ps.getState() == PlaybackState.STATE_CONNECTING);
        if (m != null && SleepTimer.onTrack(this, m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))) return;
        if (m != null && !Skipper.busy()) {
            // The playlist/album to come back to after a skip. Taken as soon as Spotify reports it,
            // also while the first song is still loading or an ad plays: when you switch playlists
            // and an ad comes before the new one's first song, the new playlist is the right one.
            // Every kind of source counts (a song picked from search plays in a "spotify:track:"
            // context, radio in "spotify:station:", ...): remembering only playlists and albums
            // kept an older playlist here, and the skip then switched to that old playlist.
            String ctx = m.getString(KEY_CONTEXT);
            if (ctx != null && ctx.startsWith("spotify:")) lastContext = ctx;
            String id = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (!ad && (playing || starting) && id != null && id.startsWith("spotify:")) {
                lastTrack = id;
                if (ctx == null || !ctx.startsWith("spotify:")) lastContext = null;   // no source reported
            }
        }

        Prefs p = new Prefs(this);
        String mode = p.mode();
        // In the car, the user's choice for Android Auto replaces Skip (Mute and Do nothing stay).
        if (ad && Prefs.MODE_RESTART.equals(mode) && Car.connected(this)) mode = p.carMode();
        if (!ad || !p.enabled() || Prefs.MODE_OFF.equals(mode)) {
            if (muting) {   // ad over (or Hush switched off): sound back on
                muting = false;
                Hush.unmuteIfNeeded(this);
                Events.add(this, Events.MUTE, "Ad over, sound back on");
            }
            if (!ad && playing) inBreak = false;
            return;
        }
        if (Skipper.busy()) return;   // a restart is in progress; its own checks cover this
        if (!playing && !starting) return;   // a paused ad is left alone until it plays
        // "End of song" sleep timer: an ad means the song is over. Pause now instead of skipping the
        // ad and playing on (Spotify can report the ad before its ID, so onTrack may not have fired).
        if (SleepTimer.adAtEndOfSong(this)) return;

        if (!inBreak) {
            inBreak = true;
            p.add(Prefs.SEEN, 1);
            Events.add(this, Events.AD, "Ad break started: " + describe(m));
        }
        boolean recentFail = System.currentTimeMillis() - Skipper.lastFailAt < 120_000;
        boolean skipMode = Prefs.MODE_RESTART.equals(mode);
        if (skipMode && Shell.ready() && !recentFail) {
            Skipper.start(this, false);
        } else if (!muting) {
            muting = true;
            AudioManager am = getSystemService(AudioManager.class);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            p.mutedByUs(true);
            p.add(Prefs.MUTED, 1);
            boolean noShizuku = skipMode && !Shell.ready();
            Events.add(this, Events.MUTE, "Ad muted" + (!skipMode ? "" : noShizuku ? " (skipping not ready)" : " (skip failed)"));
            if (noShizuku) Hush.skipAlert(this);
            Hush.refreshNotification(this);
        }
    }
}
