# Contributing

Thanks for helping improve Hush.

## Reporting bugs

Open an issue with:

- Android version, phone model, Spotify version and Hush version (shown at the bottom of Hush)
- What you expected and what happened
- The relevant lines from **Recent activity** in Hush (they contain no personal data beyond ad
  and song titles; remove anything you prefer not to share)

Security problems: please do not open a public issue; see [SECURITY.md](SECURITY.md).

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

Anything passed to `Shell.sh` must go through `Shell.arg` or another allow-list check.

By contributing you agree that your contributions are licensed under the GPL-3.0.
