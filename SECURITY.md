# Security

## Reporting a vulnerability

Please report security problems privately through GitHub:
**Security → Report a vulnerability** on this repository. Do not open a public issue for them.
You will get an answer as soon as possible, and a fix will be released before details are made
public.

## Design

Hush holds two powerful capabilities: an adb-level shell (through Shizuku) and a VPN. It is built
so that neither can be turned against the phone.

**Privileged shell**
- The only commands Hush runs are fixed: force-stop and start Spotify, return to the previous app
  or the home screen, press play or pause, open a Spotify playlist or album link, and read media,
  audio and task state.
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
