# Changelog

All notable changes to Hush are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/).

## [2.0.1] - 2026-10-26

### Fixed
- Switching to another playlist could bring you back to the previous one, when an ad came before
  the new playlist's first song. Hush now remembers the new playlist right away.
- With the sleep timer set to **End of song**, an ad right after the song was skipped and the next
  song played. Hush now pauses at the end of the song as expected.
- If Hush's helper stopped while the phone was not on Wi-Fi, skipping stayed paused after Wi-Fi came
  back. Hush now tries again by itself as soon as Wi-Fi is back.
- Pairing: if Hush did not notice the pairing code dialog right away, the **Pair** button stayed
  disabled until the search gave up. Pair now stays available (tapping it searches again), the
  search retries by itself, and it always ends after 3 minutes.
- After pairing, opening Hush from its icon could show the Settings page instead of Hush.
- A brief connection hiccup right after Wireless debugging was switched on could make Hush ask to
  pair again. Hush now tries once more before deciding the pairing is gone.
- After a lost pairing, the "ads are muted" notice now says that the pairing has to be renewed.

### Changed
- Clearer wording: Wi-Fi is needed only to pair and to start Hush's helper; skipping also works on
  mobile data.

## [2.0.0] - 2026-10-26

### Added
- Hush no longer needs Shizuku. It pairs with Android's Wireless debugging by itself (you type the
  six-digit code into Hush's notification, once) and starts its own small helper. The helper
  answers only Hush and can do only the few things skipping needs. Android 11 or newer; Wi-Fi is
  needed only to pair and to start the helper, skipping then also works on mobile data.
- Guided setup for pairing, with the one extra switch some phone makers need shown only on those
  phones (Xiaomi/Redmi/POCO, OPPO/OnePlus/realme, Meizu, Huawei/Honor).
- Self-healing: if the helper stops, Hush starts it again. If it can't (for example Wireless
  debugging was turned off), Hush mutes ads and shows one "Skipping paused" notification that says
  why; a tap resumes. Skipping never stops without you being told.
- After a reboot, one tap on Hush's notification turns Wireless debugging back on and resumes
  skipping. Optional: **Restart automatically after a reboot** does this by itself (Android 13+).
  With it on, Hush tests once whether the phone keeps its helper running with Wireless debugging
  off, keeps it off if so, and shows the result in settings. USB debugging and Developer options
  are never touched.
- **Copy diagnostics** for bug reports (nothing about what you listen to).

### Changed
- Ads stop sooner: Spotify is stopped 30 to 90 ms after an ad is detected (was 50 to 105 ms),
  because the helper calls Android directly instead of starting a shell for the first step.
- Shizuku is still supported: Hush uses it when its own helper is not set up, and on Android 8 to 10.
  Updating from 1.0.x keeps skipping through Shizuku; an optional **Skip without Shizuku** step
  switches to Hush's own helper, after which Shizuku can be uninstalled.
- Switching "When an ad plays" back to **Skip** starts the helper right away, so the next ad is
  skipped, not muted.
- If Hush's helper can't start, the reason is shown in **Recent activity**.
- The DNS filter (**Block ad servers**) is off for new installs: skipping doesn't need it, and it
  no longer asks for VPN permission during setup or takes the place of another VPN. Turn it on in
  settings to block video ads. Updating from 1.0.x keeps it as it was.

### Fixed
- On some Android versions the Developer options step of the setup never got ticked, because
  Android hides that setting from apps.
- Hush could crash if notification access was turned off at the moment it connected.
- Custom sleep timer: a number typed into the hours, minutes or seconds field was ignored if Start
  was pressed while the keyboard was still open.
- Sleep timer: pressing play within about 4 seconds after the timer paused Spotify could be undone
  by Hush. Hush now stops checking as soon as Spotify has paused.
- The status notification said "1 ad breaks skipped".

## [1.0.3] - 2026-10-24

### Changed
- Spotify no longer shows on screen during a skip. When Spotify is in the background, Hush now
  starts it the way a headset play button does (through Spotify's media-button receiver), so it
  resumes on its own and whatever you were doing stays in front. If you are in Spotify, its screen
  is reopened in the same moment, so the home screen only flashes briefly. If the button route
  does not bring Spotify back, Hush falls back to the previous method automatically.
- Spotify's notification and lock-screen controls are back with the music (about 1 s after the
  skip instead of about 20 s).
- Ads stop sooner (within about 0.1 s of being detected, usually before they are heard) and music
  comes back sooner: about 2 to 3 s of silence in real use, down from 3 to 5 s.

### Added
- Custom sleep timer: any time from 1 second to 23 h 59 min 59 s, picked as hours, minutes and
  seconds. Long timers do not keep the phone awake until their last hour.

### Fixed
- "End of song" could leave Spotify stuck loading the next song, so pressing play did nothing
  until the song was skipped. The pause now waits until the next song is playing.
- After the sleep timer paused Spotify, pressing play within 10 seconds could be undone by Hush.

## [1.0.2] - 2026-10-24

### Fixed
- Music could stop a while after a skip although Spotify still showed it as playing, and came back
  only on its own or after opening Spotify. Android froze the restarted Spotify as an idle
  background app. Hush now exempts the running Spotify process from the app freezer after each
  restart (Android 14+), and checks again once the skip is done.

### Changed
- PLAY is sent the moment the restarted Spotify is ready, and the background-audio setup now runs
  while Spotify starts instead of before it, so music comes back a little sooner.
- The reported "music back in" time is when the music actually started.
- Slow or failed skips show their timeline in the activity log (when Spotify started, was ready,
  and played), to make reports easier to act on.

## [1.0.1] - 2026-10-23

### Fixed
- Music no longer goes silent after a skip, in particular with the phone locked, where it stayed
  silent until the phone was unlocked. Android 15+ mutes media apps that play from the background
  before they register their playback service (enforced through the `CONTROL_AUDIO` app-ops);
  Hush now allows background audio for Spotify while Hush is on and restores Android's default
  when Hush is turned off.

### Removed
- The 1.0.0 workaround that briefly brought Spotify to the front after a skip.

## [1.0.0] - 2026-10-23

First public release.

### Added
- Ad-break skipping by restarting Spotify in the background; music is back in about 4 seconds.
- Ad detection from Spotify's ad media ID and the standard advertisement flag, which also catches
  the first ad of a multi-ad break; label matching in several languages as a fallback.
- The phone is left as it was: no screen wake-up, a locked phone stays locked, and the app in
  front stays in front.
- Restoring the previous playlist or album when Spotify resumes somewhere else.
- Sleep timer (5 minutes to 1 hour, or end of song) that survives ad skips.
- Mute mode, and automatic muting with a reminder when Shizuku is not running.
- Guided Shizuku setup.
- Spotify-only DNS filter with editable rules.
- Activity log, statistics, light and dark theme.

### Known issues
- A short audio gap can occur about 10 seconds after a skip while Android re-classifies the
  restarted Spotify as a background-playback app. Hush repairs it; a complete fix is planned for
  1.0.1.

[2.0.1]: https://github.com/coldMEW/Hush/releases/tag/v2.0.1
[2.0.0]: https://github.com/coldMEW/Hush/releases/tag/v2.0.0
[1.0.3]: https://github.com/coldMEW/Hush/releases/tag/v1.0.3
[1.0.2]: https://github.com/coldMEW/Hush/releases/tag/v1.0.2
[1.0.1]: https://github.com/coldMEW/Hush/releases/tag/v1.0.1
[1.0.0]: https://github.com/coldMEW/Hush/releases/tag/v1.0.0
