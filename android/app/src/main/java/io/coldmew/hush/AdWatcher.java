package io.coldmew.hush;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
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
        if (keep.isEmpty()) { spotify = null; BackgroundAudio.idle(); }   // Spotify is gone
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

    // Spotify plays on another device (Spotify Connect or Cast: a speaker, TV or computer). Spotify
    // says so in its playback state ("com.spotify.music.extra.ACTIVE_PLAYBACK_LOCAL" = false, measured
    // with Spotify 9.1.94); sometimes its session also has remote volume (volumeType=REMOTE).
    static final String KEY_LOCAL = "com.spotify.music.extra.ACTIVE_PLAYBACK_LOCAL";

    static boolean remote(MediaController c) {
        if (c == null) return false;
        if (remoteVolume(c)) return true;
        PlaybackState ps = c.getPlaybackState();
        android.os.Bundle x = ps == null ? null : ps.getExtras();
        return x != null && x.containsKey(KEY_LOCAL) && !x.getBoolean(KEY_LOCAL, true);
    }

    // The session's volume is the other device's, and Android may set it.
    static boolean remoteVolume(MediaController c) {
        MediaController.PlaybackInfo i = c == null ? null : c.getPlaybackInfo();
        return i != null && i.getPlaybackType() == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
                && i.getVolumeControl() != android.media.VolumeProvider.VOLUME_CONTROL_FIXED && i.getMaxVolume() > 0;
    }

    // While an ad plays on another device: look again every second (for up to 3 minutes) whether
    // Spotify has moved it to this phone; no session callback says so. Only Spotify's own players
    // count (asked through the helper, off the main thread): another app playing (a video, say)
    // must neither be taken for the ad nor be muted with it. Each check that leaves the ad alone
    // schedules the next one (checkLocked -> watchCastAd).
    private static final java.util.concurrent.ExecutorService castProbe = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static volatile boolean spotifyHere;
    private static final Runnable castAdCheck = () -> castProbe.execute(() -> {
        if (!inBreak() || !Shell.ready()) return;
        String n = Shell.run(io.coldmew.hush.helper.Commands.SPOTIFY_HERE).trim();
        spotifyHere = !n.isEmpty() && !"0".equals(n);
        AdWatcher.main.post(AdWatcher::recheck);
    });
    private static int castAdChecks;
    private static boolean castBreak;   // guarded by LOCK, like inBreak
    private static boolean offlineMute;   // this break is muted only because there was no internet (LOCK)

    private static void watchCastAd(boolean newBreak) {
        if (newBreak) castAdChecks = 180;
        main.removeCallbacks(castAdCheck);
        if (castAdChecks-- > 0) main.postDelayed(castAdCheck, 1000);
    }

    // A network that can reach the internet, other than a VPN (Hush's own DNS filter is one, and
    // stays up when the real connection is gone). Not required to be "validated": a Wi-Fi with a
    // login page or a slow check is treated as online, as before.
    private boolean online() {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        if (cm == null) return true;
        try {
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && !nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (RuntimeException e) { return true; }   // unknown: skip as before
        return false;
    }

    // Spotify has a media session (playing or paused), as seen by the watcher.
    static boolean hasSpotify() {
        AdWatcher w = instance;
        return w != null && w.spotify != null;
    }

    // Spotify is playing on another device right now (see remote).
    static boolean spotifyRemote() {
        AdWatcher w = instance;
        return w != null && remote(w.spotify);
    }

    // Spotify's session while its volume is another device's (see remoteVolume), or null.
    static MediaController remoteSpotify() {
        AdWatcher w = instance;
        MediaController mc = w == null ? null : w.spotify;
        return remoteVolume(mc) ? mc : null;
    }

    // Android has connected this watcher (access granted is not enough: see ensureConnected).
    static boolean connected() { return instance != null; }

    private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile long lastRebind;

    // Notification access can be granted while the watcher is not connected: when a phone's own
    // cleaner kills Hush (MIUI does so when Hush is swiped away from recent apps), Android tries to
    // connect it again only once, about 10 s later, and MIUI refuses that unless Hush may autostart.
    // Opening Hush does not connect it either, so ads went unnoticed (not even muted) until Hush was
    // switched off and on. Called whenever Hush's process starts, its screen opens or its helper
    // connects: if the watcher is still missing a few seconds later (a normal connect takes well
    // under a second), Android is asked to connect it.
    static void ensureConnected(Context ctx) {
        Context c = ctx.getApplicationContext();
        main.postDelayed(() -> {
            if (connected() || !Hush.listenerEnabled(c)) return;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastRebind < 5000) return;   // a request is on its way
            lastRebind = now;
            ComponentName me = new ComponentName(c, AdWatcher.class);
            // requestRebind only undoes a requestUnbind; for a watcher the system dropped it does
            // nothing (measured on HyperOS). Switching the component off and on makes Android
            // treat Hush as changed and connect the watcher again, now that Hush's process runs.
            try { requestRebind(me); } catch (RuntimeException ignored) {}
            main.postDelayed(() -> {
                if (!connected()) reenable(c, me);
                main.postDelayed(() -> {
                    if (connected() && new Prefs(c).enabled())
                        Events.add(c, Events.INFO, "Ad detection had stopped (Android closed Hush in the background); it is back on");
                }, 3000);
            }, 1500);
        }, 4000);
    }

    private static void reenable(Context c, ComponentName me) {
        android.content.pm.PackageManager pm = c.getPackageManager();
        try {
            pm.setComponentEnabledSetting(me, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(me, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    android.content.pm.PackageManager.DONT_KILL_APP);
        } catch (RuntimeException ignored) {}
    }

    static boolean isAd(MediaController c) { return isAd(c.getMetadata()); }

    static boolean inBreak() { synchronized (LOCK) { return inBreak; } }

    static boolean spotifyPlaying() {
        AdWatcher w = instance;
        MediaController mc = w == null ? null : w.spotify;
        PlaybackState ps = mc == null ? null : mc.getPlaybackState();
        return ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
    }

    // A skip that could not run (Android Auto was using Spotify, see Skipper): mute this break
    // instead; the usual "ad over" check turns the sound back on.
    static void muteInstead(Context c) {
        synchronized (LOCK) {
            if (muting) return;
            muting = true;
        }
        Hush.muteAd(c);
        new Prefs(c).add(Prefs.MUTED, 1);
        Hush.refreshNotification(c);
    }

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
            // A speaker muted for an ad whose music was moved away meanwhile gets its volume back
            // the next time Spotify plays on another device (see Hush.restoreCastVolume).
            if (!ad && !muting && p.castVolume() >= 0) Hush.restoreCastVolume(this);
            if (muting) {   // ad over (or Hush switched off): sound back on
                muting = false;
                offlineMute = false;
                Hush.unmuteIfNeeded(this);
                Events.add(this, Events.MUTE, "Ad over, sound back on");
            }
            if (!ad && playing) inBreak = false;
            if (!playing && !starting) BackgroundAudio.idle();   // a reset left for when Spotify stops
            return;
        }
        if (Skipper.busy()) return;   // a restart is in progress; its own checks cover this
        if (!playing && !starting) return;   // a paused ad is left alone until it plays
        // "End of song" sleep timer: an ad means the song is over. Pause now instead of skipping the
        // ad and playing on (Spotify can report the ad before its ID, so onTrack may not have fired).
        if (SleepTimer.adAtEndOfSong(this)) return;

        boolean newBreak = !inBreak;
        inBreak = true;
        boolean recentFail = System.currentTimeMillis() - Skipper.lastFailAt < 120_000;
        boolean skipMode = Prefs.MODE_RESTART.equals(mode);
        // Spotify plays on another device (a speaker, TV or computer through Spotify Connect or
        // Cast): a restart brings the music back on this phone instead (issue #10), so the ad is
        // muted on that device.
        // A break that began on another device stays a "cast" break even if Spotify then reports the
        // ad as playing here: a restart would still end the cast.
        if (newBreak) { castBreak = remote(c); spotifyHere = false; }
        boolean remote = remote(c) || castBreak;
        // Muted only because there was no internet, and the connection is back while the break goes
        // on (Spotify reports it: the buffering ad starts, or the next ad comes): skip the rest of
        // the break now instead of playing it muted to its end.
        if (muting && offlineMute && skipMode && Shell.ready() && !remote && online()) {
            muting = false;
            offlineMute = false;
            Hush.unmuteIfNeeded(this);
            Events.add(this, Events.INFO, "Internet is back: skipping the rest of this ad break");
        }
        // A break already muted (a skip could not run) stays muted until it is over. The skip starts
        // before anything is written: every millisecond before Spotify stops is heard as the ad.
        boolean skip = skipMode && Shell.ready() && !recentFail && !muting && !remote;
        // No internet: a restarted Spotify cannot load the next song, so the music would not come
        // back (seen on Xiaomi with Wi-Fi off). Muting keeps Spotify as it is until the connection
        // returns. Checked last, only when a skip would otherwise start.
        boolean offline = skip && !online();
        if (offline) skip = false;
        if (skip) Skipper.start(this, false);
        if (newBreak) {
            p.add(Prefs.SEEN, 1);
            Events.add(this, Events.AD, "Ad break started: " + describe(m));
        }
        // On another device whose volume Android cannot set (Spotify keeps it to itself for some
        // devices). Spotify can play the ad itself on this phone, a moment after the break
        // starts, and moves the music back afterwards (seen with a computer through Spotify
        // Connect): then this phone is muted for the ad. Until that happens the ad is left alone
        // (a skip would move the music to this phone) and checked again every second.
        boolean here = remote && remoteSpotify() == null;
        if (here && !muting && !spotifyHere) {
            if (newBreak) Events.add(this, Events.INFO, "Ad left playing: Spotify plays on another device, and a skip would move the music to this phone");
            watchCastAd(newBreak);
            return;
        }
        if (!skip && !muting) {
            muting = true;
            // Only Spotify: other apps keep their sound. Offline the ad can stay "buffering" for as
            // long as the connection is gone, so the phone's volume is not touched then either.
            if (here || offline) Hush.muteSpotify(this);
            else Hush.muteAd(this);
            offlineMute = offline;
            p.add(Prefs.MUTED, 1);
            boolean noShizuku = skipMode && !Shell.ready() && !remote;
            Events.add(this, Events.MUTE, "Ad muted" + (here ? " on this phone (Spotify plays the ad here, then the music goes back to the other device)"
                    : remote ? " on the device Spotify plays on (a skip would move the music to this phone)"
                    : offline ? " (no internet, so a skip could not bring the music back)"
                    : !skipMode ? "" : noShizuku ? " (skipping not ready)" : " (skip failed)"));
            if (noShizuku) Hush.skipAlert(this);
            Hush.refreshNotification(this);
        }
    }
}
