# Security

## Reporting a vulnerability

Please report security problems privately through GitHub:
**Security → Report a vulnerability** on this repository. Do not open a public issue for them.
You will get an answer as soon as possible, and a fix will be released before details are made
public.

## Design

Hush holds two powerful capabilities: adb-level rights (through its own helper, or Shizuku) and a
VPN. It is built so that neither can be turned against the phone.

**Hush's helper**
- Hush pairs with Wireless debugging like a computer would, with its own key. The private key is
  generated on the phone, stored only in Hush's private storage and encrypted with a key held by
  the Android Keystore. Pairing uses Android's own protocol (TLS 1.3 and SPAKE2 with the six-digit
  code); the code never leaves the phone.
- Hush connects only to `127.0.0.1`, starts the helper (`app_process` from Hush's own installed
  APK) and closes the connection once the helper has answered. The helper has the same rights as
  `adb shell`, no more.
- The helper hands its Binder to Hush through Hush's content provider together with a random
  token, and Hush accepts it only from the shell user with the token it stored. The helper in turn
  answers only calls from Hush's app uid.
- It has no "run a command" call. It knows a fixed list of actions (stop Spotify, start or reopen
  it, allow its background audio, press play, open a Spotify link, read which app is in front and
  whether Spotify is muted or frozen, grant Hush `WRITE_SECURE_SETTINGS`) and builds every command
  itself from an action number and validated arguments.
- `WRITE_SECURE_SETTINGS` is granted only to Hush, and Hush uses it only to switch Wireless
  debugging (`adb_wifi_enabled`) on after a reboot and, if you turned on automatic restart and
  the phone passed the test, off again. It never changes USB debugging or Developer options.
- The helper exits when Hush is uninstalled. You can revoke Hush's pairing at any time under
  Wireless debugging → Paired devices.
- Limitation: Android's adb daemon writes the command that starts the helper, token included, to
  the system log. Reading that log needs the same adb rights the helper already has, so this gives
  nothing to an app that does not already have them.

**Privileged shell (Shizuku or the helper)**
- The only commands Hush runs are fixed: force-stop and start Spotify, return to the previous app
  or the home screen, press play or pause, open a Spotify playlist or album link, allow Spotify's
  background-audio app-ops (reset to default when Hush is turned off), and read media, audio and
  task state.
- Every value inserted into a command (package and component names, Spotify URIs) is checked
  against a strict allow-list pattern and single-quoted. Anything else is rejected; unit tests
  cover injection attempts.
- No component accepts commands from other apps. Hush's broadcast receiver is not exported, and
  all `PendingIntent`s are immutable.

**DNS filter**
- The VPN applies to Spotify only and carries only DNS traffic.
- Queries are forwarded from a protected socket to one IPv4 resolver. A reply is accepted only if
  it comes from that resolver's address and port 53 and carries the query's ID, so other hosts
  cannot inject answers.
- Rules are validated when saved and again when loaded; invalid lines are ignored. The upstream
  must be an IPv4 literal, so no lookup happens while choosing it.
- Malformed or oversized DNS packets are dropped, and the number of lookups in flight is bounded.

**Data**
- Settings, DNS rules and the activity log are stored in the app's private storage and excluded
  from cloud backup and device transfer. The activity log is capped in size.
- Release builds contain no debug or info logging; nothing about what you play is written to the
  system log.
- Hush has no analytics, no crash reporting and no network access beyond forwarding Spotify's DNS
  lookups.

**Build**
- Release APKs are minified and signed with APK Signature Scheme v2 and v3. The signing key is not
  stored in this repository.
- The Gradle wrapper distribution is pinned by SHA-256 and validated in CI.

## Verifying a release

Each release lists the SHA-256 of the APK and of the signing certificate. Check them with:

```
sha256sum Hush-<version>.apk
apksigner verify --print-certs Hush-<version>.apk
```
