# How Hush works

Measurements in this document were taken with Spotify 9.1 (free tier) on a current Android
release, over Bluetooth headphones and the phone speaker.

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
permissions, which Hush gets through its own helper (section 6), or through Shizuku.

The order of steps matters:

1. Note which app is in front (from the recent-tasks list, which is also correct while the phone
   is locked; if a phone prints that list in a form Hush cannot read, from the resumed activity)
   and which playlist or album is playing.
2. Force-stop Spotify and start it again. Hush's helper reads the app in front and stops Spotify
   with two direct calls to Android's task and activity services, without starting a shell, so the
   ad stops 30 to 90 ms after it is detected. (Through Shizuku, both run in one shell call: about
   0.1 s.)
   - Hush sends a play key straight to Spotify's media-button receiver (since 1.0.3), the way a
     headset button reaches a stopped player. Spotify starts without any screen, resumes playback
     by itself and starts its playback service with its notification and lock-screen controls
     right away. Starting that service from the background needs a short temporary power
     allowlist entry (`cmd deviceidle tempwhitelist -d 30000`); without it Spotify plays with no
     notification. Hush finds the receiver at run time, so a renamed class in a Spotify update does
     not break skipping.
   - If Spotify was the app in front, the same shell call opens Spotify's screen right after the
     force-stop, so the home screen shows for only a moment and you stay in Spotify.
   - If the button does not bring music back, Hush tries once more (not while Android Auto is
     connected, see below): it stops Spotify again and starts its main activity (the 1.0.0 to 1.0.2
     method). Starting an activity does not wake the screen; while the phone is locked it opens
     behind the lock screen. On this second try Spotify stays in front until it plays, because some
     phones (reported on Xiaomi) do not let an app start playback from the background at all.
3. Wait for Spotify's **new** media session. Force-stop cancels the media-button `PendingIntent`
   Spotify registered, so a play key sent through the system before the new session exists is
   lost. This was the cause of skips that left Spotify silent over Bluetooth.
4. If Spotify's activity was started but Spotify was in the background, bring back the app (or
   home screen) that was in front (on the second try, only once the music plays): the exact task,
   as the recents screen does (same window, same state, e.g. a running navigation), or the app's
   launcher entry if that is not possible. Not while Android Auto is connected (see below).
5. When the session settles (paused), press play. Hush alternates three routes until playback
   starts: the system media-key dispatcher (which also gives Spotify a temporary foreground
   allowance and avoids Android muting background audio), a transport control, and a key event.
6. If Spotify came back on a different playlist or album, start the remembered one again with
   Spotify's `spotify:<playlist|album>:<id>:play` link. (Spotify's session ignores the standard
   play-from-URI command on the free tier.) Hush remembers every kind of source Spotify reports,
   including a song picked from search (`spotify:track:…`), an artist's Popular list or radio,
   and takes it as soon as Spotify reports it, also when the ad comes before the new song starts.
   Sources other than playlists and albums are left as Spotify brought them back: before 2.0.3
   Hush ignored them and could switch back to an older playlist.
7. If Android still reports the player as muted, a pause/play through the dispatcher clears it.

**Android Auto.** While a car screen is connected, a skip still restarts Spotify in the background
but never brings an app to the front on the phone: no app is restored, no playlist link. Switching
apps on the phone can restart the Android Auto session and reset navigation, and the phone's own
screen is not what the driver sees. Users can also choose Mute or Do nothing for the car. Hush
knows Android Auto is connected from Android's car mode or an active automotive projection (which
only the shell may read, so Hush asks its helper).

Android Auto reads Spotify's media items through Spotify's content provider, and holds a stable
connection to it for a fraction of a second whenever the item changes, so also when an ad starts.
Android kills an app that holds such a connection when the provider's app is stopped
("depends on provider"): stopping Spotify in that moment killed Android Auto's projection process,
the car screen restarted and navigation lost its route. In the car Hush therefore checks
`dumpsys activity providers` first and stops Spotify only once Android Auto holds no stable
connection to it (measured with real ads: held for up to 0.8 s, Spotify stopped within 0.15 s after
it let go). If it is still held after 3 s, that ad is muted instead of skipped.

Muting in the car: Android Auto captures Spotify's audio itself, so the phone's music volume does
not reach the car (measured in the head unit: about the same level before and after). Hush mutes
Spotify's own playback instead, with the `PLAY_AUDIO` app-op (measured: the car goes silent), and
allows it again when the ad is over. Android keeps that app-op across a reboot, so Hush remembers
that it set it and undoes it the next time it can.

Music is typically audible again 2 to 3 seconds after the ad is stopped (3 to 4 with the activity
route); the "Avg. resume" statistic is a little higher because it counts until Spotify reports
that it is playing, which trails the actual sound by up to a second. If a restart brings the ad back or music does not resume,
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

Lookups go to the `upstream` resolver. If it does not answer within 1.2 s, Hush also asks the
network's own DNS servers and uses the first answer: some networks (for example a router running
AdGuard Home or Pi-hole) block DNS to outside resolvers. Edited rules take effect at once.

Audio ads cannot be removed by DNS: they are served from the same hosts as music.

## 4. Sleep timer

Spotify's sleep timer lives only in Spotify's memory: it is not a system alarm, it is not part of
the media session and it is not shown in Spotify's notification, so no other app can read it, and
it is lost when Spotify restarts. Hush therefore has its own timer: presets from 5 minutes to an
hour, any custom time up to 23 h 59 min 59 s, or the end of the current song.

- The last hour is counted down inside Hush under a partial wake lock, with an inexact system
  alarm as a backup in case Hush is stopped. Before the last hour nothing keeps the phone awake:
  an alarm for the start of the last hour hands over to the countdown, so a long timer costs no
  battery while the music is paused.
- A pause that reaches Spotify while it is switching songs (buffering or connecting) leaves it
  stuck: silent, but play no longer works until the song is skipped. The pause therefore waits
  until Spotify is playing. For "end of song" that is the first moment of the next song (about
  0.1 to 0.3 s of it is heard); the pause is sent after 10 s at the latest. If a song start
  overtakes the pause, Hush pauses again, but only until Spotify has reported "paused" once (at
  most 4 s): pressing play after that is never undone.

## 5. Background audio after a restart

Android 15 and later silence a media app that plays from the background without a foreground
service ("AudioHardening"). The mute is applied through the `CONTROL_AUDIO` and
`CONTROL_AUDIO_PARTIAL` app-ops, whose default mode for apps is `foreground`
(`dumpsys audio` shows `mutedState:op` and `source:opControlAudio`).

Spotify registers its playback foreground service only when its screen goes to the background
while music plays. After a restart-skip that never happens in time: playback is allowed for the
10 seconds of the media key's temporary allowance, then the audio is muted. With the phone locked
it stays muted until the phone is unlocked, because unlocking brings Spotify's screen up.
Measured: muted about 10.5 s after playback starts, screen on or off.

Since 1.0.1 Hush sets these app-ops (and `TAKE_AUDIO_FOCUS`) to `allow` for Spotify through
its helper (or Shizuku) while Hush is on, and back to `default` when Hush is turned off. With that, playback after
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

## 6. Hush's helper

Since 2.0 Hush does what Shizuku did for it by itself, with much less: it needs only a handful of
actions, not a general shell.

**Pairing (once).** Wireless debugging lets a paired device run `adb shell` over the network. Hush
pairs as such a device, from the phone itself: it finds the pairing port through mDNS
(`_adb-tls-pairing._tcp`, only addresses of this phone), takes the six-digit code from a reply
field in its notification (a pop-up notification, so it shows over Settings: leaving the pairing
popup in Settings ends pairing and makes its code invalid), and runs Android's pairing protocol: TLS 1.3, the code plus 64 bytes of
exported TLS keying material as the SPAKE2 password, HKDF-SHA256 to an AES-128-GCM key, then the
exchange of public keys. Hush's RSA key is generated on the phone and stored encrypted with an
Android Keystore key. No library from outside the platform is used; the SPAKE2 code is Hush's own,
uses the same constants as Android's (BoringSSL), is pinned by unit tests and was verified by real
pairing.

**Starting the helper.** Hush finds the debugging port (`_adb-tls-connect._tcp`), connects to
`127.0.0.1` with its key and runs one command:

    CLASSPATH=<Hush's APK> setsid app_process /system/bin --nice-name=hush_helper \
        io.coldmew.hush.helper.Helper --token=… --uid=… --apk=… --authority=…

`setsid` detaches the helper from the adb session, so it keeps running when the connection
closes: its parent becomes init and, like Shizuku's server, it inherits adbd's `oom_score_adj`
of -1000, so Android's low-memory killer leaves it alone. The helper hands its Binder to Hush
through Hush's content provider with the token; Hush accepts it only from the shell user. The
connection stays open until that handshake, because closing it earlier could kill the helper
while it starts. Typical times: ready about 1 s after the pairing code is entered, 1 to 2.5 s after
Hush is updated (the old helper exits when its APK is replaced), and 4 to 5 s after the phone is
unlocked following a reboot (Android starts no apps before the first unlock).

**Self-healing.** Hush watches the helper's Binder. If the helper dies, Hush starts it again
(up to 3 times in 10 minutes). If it can't (Wireless debugging off, no Wi-Fi, pairing forgotten),
ads are muted and one notification says why. Tapping it resumes: on Android 13+ Hush turns
Wireless debugging on itself (with `WRITE_SECURE_SETTINGS`, which the helper grants it once), so
skipping is back in about 1 s (up to about 6 s if Wireless debugging takes a moment to start).
Without Wi-Fi, Hush waits and tries again by itself as soon as Wi-Fi is back (about 4 to 7 s);
Recent activity says so.

**Updates.** Once a day (unless turned off) Hush asks GitHub's public API for the latest release;
nothing about the user or the phone is sent. A newer version is announced once, with the first line
of its release notes. **Update Hush** downloads the APK and installs it only if it matches the
SHA-256 GitHub publishes, is Hush, is newer and is signed with the same key. With the helper it is
streamed into `pm install -r` (no prompt; settings and pairing are kept), and the new version starts
its helper by itself; without the helper Android's installer asks once.

**Mobile data.** The helper runs in the process group of Android's debugging service (adbd), and
the shell user cannot move it out. Android switches Wireless debugging off when Wi-Fi drops; if USB
debugging is off too, init stops adbd and kills its whole group, the helper included, and it can
only be started again on Wi-Fi. (Shizuku has the same limit and asks users to keep USB debugging
on.) So while Skip is selected, Hush turns USB debugging on through its helper and logs it; the
helper then keeps running on mobile data.
A connection refused right after Wireless debugging was switched on is tried once more before
Hush concludes that the pairing is gone.

**After a reboot.** Android turns Wireless debugging off when the phone restarts (some versions
turn it back on by themselves shortly after boot; Hush then resumes silently). By default Hush
leaves the setting as you left it, the same as Shizuku, and shows the notification above. With
**Restart automatically after a reboot** (opt-in, Android 13+) Hush waits for Wi-Fi, turns
Wireless debugging on, starts the helper and resumes, without any notification.

**Per-phone test.** Only with automatic restart on, and once: after starting the helper, Hush
turns Wireless debugging off (never USB debugging or Developer options) and checks for 10 s that
the helper still answers. If it does, the phone is "safe" and from then on Wireless debugging is
on only for the few seconds Hush needs at boot. If the helper is killed, Hush turns it back on,
starts the helper again, remembers that this phone needs it on, and never tries again. Settings
show the result.

**Phone makers.** Some systems need one more switch before adb may start apps or grant
permissions: Xiaomi/Redmi/POCO "USB debugging (Security settings)", OPPO/OnePlus/realme "Disable
permission monitoring", Meizu "Flyme payment protection" off, Huawei/Honor "Allow ADB debugging in
charge only mode". Setup shows only the step for your phone.

## 7. Limits

- Android turns on Wireless debugging only on Wi-Fi (no internet needed), so Wi-Fi is needed to pair
  and to start the helper. A running helper needs no network and keeps skipping on mobile data as
  long as USB debugging is on, which Hush turns on while Skip is selected (see Mobile data above).
  If the helper has to start without Wi-Fi (e.g. after a reboot), Hush mutes ads until Wi-Fi is back.
- Android forgets a pairing that has not been used for 7 days, unless "Disable adb authorization
  timeout" is on in Developer options. Hush then asks to pair again.
- Shizuku without root stops at every reboot and must be started again.
- Spotify can change any of the above in an update. The metadata signals and the restart
  behaviour were verified on the version listed at the top.
