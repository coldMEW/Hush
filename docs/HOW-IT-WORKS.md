# How Hush works

Measurements in this document were taken with Spotify 9.1 (free tier) on a Pixel 7, Android 17,
over Bluetooth headphones and the phone speaker.

## 1. Seeing ads

Hush is a notification listener, which is what Android requires for an app to read other apps'
media sessions. Spotify's session reports every ad in three ways:

| Signal | Example | Used |
| --- | --- | --- |
| Media ID | `spotify:ad:08457e80…` (songs are `spotify:track:…`) | primary |
| Metadata flag | `android.media.metadata.ADVERTISEMENT = 1` | primary |
| Label | title or artist `Advertisement`, or `Advertisement • 1 of 3` | fallback, several languages |

The first ad of a break is often labelled `Advertisement • 1 of 3` in the artist field with the
advertiser's name as the title, so an exact match on "Advertisement" misses it. The media ID and
the flag do not depend on the label or the language.

## 2. Skipping a break

Spotify keeps the current ad break in memory only. Force-stopping Spotify and starting it again
resumes the queue at the next song, without the break. Force-stopping another app needs adb-level
permissions, which Hush gets through Shizuku.

The order of steps matters:

1. Note which app is in front (from the recent-tasks list, which is also correct while the phone
   is locked) and which playlist or album is playing.
2. Force-stop Spotify and start its main activity. Starting an activity does not wake the screen;
   while the phone is locked it opens behind the lock screen.
3. Wait for Spotify's **new** media session. Force-stop cancels the media-button `PendingIntent`
   Spotify registered, so a play key sent before the new session exists is lost. This was the
   cause of skips that left Spotify silent over Bluetooth.
4. If Spotify was in the background, bring back the app (or home screen) that was in front.
5. When the session settles (paused), press play. Hush alternates three routes until playback
   starts: the system media-key dispatcher (which also gives Spotify a temporary foreground
   allowance and avoids Android muting background audio), a transport control, and a key event.
6. If Spotify came back on a different playlist or album, start the remembered one again with
   Spotify's `spotify:<playlist|album>:<id>:play` link. (Spotify's session ignores the standard
   play-from-URI command on the free tier.)
7. If Android still reports the player as muted, a pause/play through the dispatcher clears it.

A typical skip takes 4 to 5 seconds. If a restart brings the ad back or music does not resume,
Hush tries once more, then falls back to muting the rest of the break and shows a notification.

Hush never sends wake-up, power or sleep keys, so the phone is never woken or locked.

## 3. DNS filter

A `VpnService` that routes only DNS server addresses, and only for Spotify (`addAllowedApplication`).
Everything else, including all of Spotify's non-DNS traffic and every other app, bypasses it.

Default rules (`rules.txt`, editable in the app):

| Rule | Why |
| --- | --- |
| `block video-fa.scdn.co` | video ads |
| `block video-cf.spotifycdn.com` | video ads |
| `block s0.2mdn.net` | ad media |
| `redirect adstudio-assets.scdn.co misc.scdn.co` | ad assets; a plain block here freezes background playback, while an answer that leads to an HTTP 403 does not |
| `upstream 1.1.1.1` | resolver for everything else |

Audio ads cannot be removed by DNS: they are served from the same hosts as music.

## 4. Sleep timer

Spotify's sleep timer lives only in Spotify's memory: it is not a system alarm, it is not part of
the media session and it is not shown in Spotify's notification, so no other app can read it, and
it is lost when Spotify restarts. Hush therefore has its own timer. Timed choices are counted down
inside Hush under a partial wake lock, with an inexact system alarm as a backup in case Hush is
stopped; "end of song" pauses as soon as the media ID changes. A pause that lands while the next
song is loading is repeated a few seconds later if Spotify starts playing anyway.

## 5. Background audio after a restart

Android 15 and later silence a media app that plays from the background without a foreground
service ("AudioHardening"). The mute is applied through the `CONTROL_AUDIO` and
`CONTROL_AUDIO_PARTIAL` app-ops, whose default mode for apps is `foreground`
(`dumpsys audio` shows `mutedState:op` and `source:opControlAudio`).

Spotify registers its playback foreground service only when its screen goes to the background
while music plays. After a restart-skip that never happens in time: playback is allowed for the
10 seconds of the media key's temporary allowance, then the audio is muted. With the phone locked
it stays muted until the phone is unlocked, because unlocking brings Spotify's screen up.
Measured on Android 17: muted about 10.5 s after playback starts, screen on or off.

Since 1.0.1 Hush sets these app-ops (and `TAKE_AUDIO_FOCUS`) to `allow` for Spotify through
Shizuku while Hush is on, and back to `default` when Hush is turned off. With that, playback after
a screen-off restart stayed audible throughout (verified for 30 s, with Spotify promoting its own
service after about 29 s). A watchdog still re-applies the ops and cycles pause/play if a mute is
ever detected after a skip.

The same missing service has a second effect. Without it Android counts the restarted Spotify as a
cached background app, and the app freezer suspends its process about 10 seconds later. The media
session keeps reporting "playing", the notification shows the song, but no audio is produced; the
sound returns only when something thaws the process, such as opening Spotify. `am isfrozen
com.spotify.music` reports `true` in that state. Since 1.0.2 Hush runs `am unfreeze --sticky` on
the new Spotify process right after each restart (and on the running one when Hush is turned on).
The exemption lasts for that process only, so it ends with the next restart, and nothing else on
the phone is affected. The command exists on Android 14 and later; older versions ignore it.

## 6. Limits

- Shizuku without root stops at every reboot and must be started again.
- Spotify can change any of the above in an update. The metadata signals and the restart
  behaviour were verified on the version listed at the top.
