# Changelog

All notable changes to Hush are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/).

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

[1.0.1]: https://github.com/coldMEW/Hush/releases/tag/v1.0.1
[1.0.0]: https://github.com/coldMEW/Hush/releases/tag/v1.0.0
