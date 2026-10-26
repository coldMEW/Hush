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
