# Stream project guide

## Scope and devices

This repository contains only Stream. Work directly on `main`. The development
target is the local Android TV emulator: `Stream_Local_TV`, `emulator-5558`,
Android TV 16/API 36. Every device operation must specify the serial and verify
`ro.kernel.qemu=1` and the expected AVD name through `simulator/emulator.sh`.
Custom targets require both `STREAM_AVD` and `STREAM_PORT`. Never use unqualified
ADB, `adb -d`, discovery or saved physical-TV pairings as a fallback. Do not
connect to, install on, reboot or control a physical TV without explicit user
authorization. Preserve other AVDs, owner data, ADB preferences and TLS checking.

## Source ownership

- `android/app`: Kotlin/XML/RecyclerView UI, add-ons, encrypted SQLite library,
  history and private imports; application ID `tv.stream.app`.
- `android/playback`: this repository's Media3 player, subtitle compatibility,
  menus, overlays and resources; package `tv.stream.playback`.
- `android/torrent`: JNI/Media3 data source, package `tv.stream.torrent`.
- `native`: C++17 engine, Rust core (`native/core`: bounded piece cache, pinned
  toolchain and Cargo.lock) and pinned libtorrent 2.1.2.
- `android/codecs` and `codecs`: pinned software decoder integration/build tools.
- `sounds`: deterministic generated UI sounds; regenerate rather than hand-edit.
- `simulator.sh` and `simulator`: generated local media, real loopback BitTorrent
  seeder (58134), fixture service (58133), untrusted TLS fixture (58135).

The Android build includes its own `:playback`, `:torrent`, `:codecs` and `:app`.
Use Kotlin for Android UI/Media3. Style the app player through app resource
_overrides_; do not alter reusable playback behavior just to restyle it.
A new name must not reintroduce personal branding into source, paths or comments.

## Verification and implementation

Run focused checks needed for the change; do not run every suite routinely.
See `android/TESTING.md` and `android/README.md` for device tests. Typical commands:

```sh
sh simulator/tests/identity.sh
node --test simulator/tests/*.test.mjs
./native/build-host.sh dev
./simulator.sh smoke
(cd android && ./gradlew :app:testDebugUnitTest --tests 'tv.stream.app.ExampleTest')
```

Every required fixture test uses generated media, loopback peers and the verified
emulator: no public torrents, trackers, DHT, LAN peers or physical TV. Keep piece
hash checks enabled and never replace BitTorrent with HTTP in a torrent test.
Native dependencies are pinned in `native/deps.lock` and fetched into ignored
`native/.deps`; change libtorrent only through `native/patches` (its checkout is
reset). Keep eviction regressions passing under dev/asan/tsan/checks variants
before changing the patch or moving the upstream dependency. Do not disable TLS
to make the untrusted certificate regression pass. Android instrumentation may
exit zero on failure: retain and check the runner's `OK (...)` result.

Data APIs block and belong on application workers. Every navigation/search owns
a cancellable provider scope. Native create/select/close operations are ordered
on the application-owned playback worker. Never move session cleanup to a new
activity's worker or clear an active session's payload directory. Preserve
progress through process death and configuration changes. Test stores/imports
and player preferences must stay isolated; release builds ignore test overrides.

First launch installs only the owner-approved starter providers once:
Cinemeta, Internet Archive, OpenSubtitles and `StarterAddons.SOURCES` (Meteor,
MediaFusion, Peerflix, TorrentsDB). Removed defaults stay removed. Additional
providers require explicit acceptance in Settings or the store review page.
Local-only tests never install defaults, use the public directory or run
background new-episode checks. New-episode checks only refresh followed series
from installed providers at most twice a day each, below foreground work.
Built-in add-ons use `stream://`. Never persist playable stream addresses for
resume or log configured add-on URL paths/queries. Metadata/import/preference
values are Keystore encrypted; backup and device transfer are disabled.

Keep visible/accessibility control labels exact for instrumentation (Play,
Resume, Sources, Add favourite, Install, Hide new episode and tab names). Every
action works by click/touch and remote. Preserve timeline dragging. Back uses
the platform callback on Android 13+ and `onBackPressed` on older versions;
do not consume key-down and pop twice. Caption clearance includes the complete
toolbar and its timeline's 48 dp touch target. Keep SubtitlePlayback and
PausedSubtitleCues compatibility until real seek, resumed/late subtitle
selection regressions pass: cues beginning before a seek target must appear.
Renderer state is read on the playback thread. An ended video track must not
trigger a stall error while longer audio/subtitle tracks continue.

## Git and privacy

Inspect dirty work before staging; preserve other edits. Commit coherent source,
docs, tests, lockfiles, wrappers and CI. Never commit APKs, build/target folders,
node_modules, `.deps`, simulator caches, local.properties, pairings, secrets,
private logs, owner data or device backups. Keep TLS and piece verification on.
The local SDK is `~/Library/Android/sdk`; Java 21 is under
`~/Library/Java/JavaVirtualMachines`. Do not claim physical-TV or all-platform
validation from local emulator success.
