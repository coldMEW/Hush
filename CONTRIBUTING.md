# Contributing

Thanks for helping improve Hush.

## Reporting bugs and ideas

Bugs, questions and feature ideas go in
[GitHub Issues](https://github.com/coldMEW/Hush/issues/new/choose), in public, so everyone can find
them and follow along. The bug form asks for:

- Hush's **Copy diagnostics** report (in the Settings section of Hush): phone model, Android
  version and how Hush skips, nothing about what you listen to
- Spotify version
- What you expected and what happened
- The relevant lines from **Recent activity** in Hush (they contain no personal data beyond ad
  and song titles; remove anything you prefer not to share)

The only exception is a security vulnerability: report it privately as described in
[SECURITY.md](SECURITY.md), so it can be fixed before it is public.

## Building from source

Requires JDK 17 and the Android SDK (API 35).

```bash
git clone https://github.com/coldMEW/Hush.git
cd Hush/android
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # debug build (installs alongside a release build)
./gradlew assembleRelease        # release build, see signing below
```

Release builds are signed with a key that is not in this repository. To sign your own, create
`~/.gradle/hush-signing.properties` (or set `HUSH_SIGNING` to another path):

```properties
storeFile=/path/to/keystore.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without it, `assembleRelease` produces an unsigned APK.

## Project layout

```
android/app/src/main/java/io/coldmew/hush/
├── AdWatcher.java          notification listener: reads Spotify's media session, detects ads
├── Skipper.java            the restart-skip: force-stop, restart, resume
├── SleepTimer.java         Hush's own sleep timer
├── DnsVpnService.java      Spotify-only DNS filter (VpnService)
├── Dns.java, Rules.java    DNS packet handling and rules
├── Shell.java              one entry point: Hush's helper, else Shizuku
├── HelperControl.java      starts and watches the helper, reboot and per-phone test
├── HelperSetupActivity     guided pairing with Wireless debugging (or Use root)
├── Root.java               starts the helper with root on rooted phones
├── PairingService.java     finds the pairing port, takes the code from the notification
├── adb/                    small adb client: key, pairing (SPAKE2), TLS connection, mDNS
├── helper/                 the helper process and its fixed command table
├── MainActivity.java       main screen
├── ShizukuSetupActivity    guided Shizuku setup (optional)
└── Hush.java, Prefs.java, Events.java, …
docs/HOW-IT-WORKS.md        design notes and measurements
```

## Pull requests

1. Fork and create a branch from `main`.
2. Keep changes focused; one topic per pull request.
3. Match the existing style: plain Java, no new dependencies without discussion, comments that
   explain *why*.
4. Run the checks before opening the pull request:

   ```bash
   cd android
   ./gradlew lintRelease testDebugUnitTest assembleDebug
   ```

5. If you change how skips work, test on a real device in each case: screen off, phone locked,
   in another app, and in Spotify.

Privileged actions are only added to the fixed command table (`helper/Commands.java`); every value
passed into a command goes through `Commands.arg` or another allow-list check, with a unit test.

By contributing you agree that your contributions are licensed under the GPL-3.0.
