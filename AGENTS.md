# Project guide for agents

## Scope and device rules

This repository contains Daniil Cast and separate, older Fire TV maintenance
scripts. The current development/test target is the **local Android TV emulator**.
Do not connect to, install on, send playback/control commands to, reboot, or
change the physical Fire TV unless the user explicitly authorizes that work.
A saved LAN pairing is not authorization. Preserve the owner's physical-TV data
and ADB preference (ADB stays enabled). Do not run cleanup/restore scripts during
casting development.

Every simulator operation must specify an emulator serial and verify
`ro.kernel.qemu=1` plus the expected AVD name. Never use unqualified `adb`,
`adb -d`, a global selected TV, or discovery results as a fallback test target.
Default AVD: `DaniilCast_Local_TV`; serial: `emulator-5556`; Android TV 16/API 36.
The older `DaniilCast_TV` on 5554 is preserved, but its Android 9 CA store cannot
validate some current video servers. Keep TLS verification enabled.

## Apps and source ownership

| Component | Sources | Role / build output |
| --- | --- | --- |
| Chrome extension | `casting/extension/background.js`, `manifest.json`, `sidepanel.html`, `icons/` | Detects page media/catalogues, controls native messaging; Chrome loads this directory unpacked. |
| Chrome side panel | `casting/sidepanel/src/` (React/TypeScript/CSS) | Builds `casting/extension/sidepanel/app.js` and `app.css`. Edit source, then rebuild; do not hand-edit generated bundles. Cast, Remote, TV are user-facing; versions, activity and diagnostics live in Developer. |
| Desktop helper | `casting/native/src/` (Rust) | Chrome native host, CLI, pairing, relay/conversion, packaging and installation. Replaces deleted Python helper/installer. `Cargo.lock` is committed. FFmpeg does encoding; Rust coordinates it. |
| Android receiver | `casting/receiver/app/src/main/` (Java/XML) | `tv.daniil.cast/.PlayerActivity`; HTTP control/pairing service, Chrome command/status adapter, idle/pairing screens, episodes, conversion waits. Debug APK in `app/build/outputs/apk/debug/`. Android 8+ minimum, target/compile 36. |
| Shared playback | `android/playback/` (Java/XML library, Media3) | `tv.daniil.playback`: `PlaybackSession`/`PlayerScreen`, subtitles, tracks, overlay, menu, errors. No Gradle build of its own: each app includes it as `:playback`. Changes must pass the receiver suite (and the standalone app's, once it exists). |
| Daniil TV engine | `streaming/native/` (C++17; libtorrent 2.1.2 + `patches/libtorrent`) | Bounded piece cache, libtorrent disk subsystem, read-ahead/eviction controller, JNI. Sources pinned in `deps.lock`, fetched and verified by `fetch-deps.sh` into ignored `.deps/`; change libtorrent only through `patches/` (the checkout is reset). Host `dtv-fixture`, `dtv-tests`. |
| Daniil TV Android | `streaming/android/` (Gradle: `:torrent`, shared `:playback`) | `tv.daniil.torrent`: `TorrentEngine` (JNI) and Media3 `TorrentDataSource`; `libdaniiltv.so` for armeabi-v7a, arm64-v8a, x86_64. The `tv.daniil.stream` app is planned. |
| Daniil TV simulator | `streaming/simulator.sh`, `streaming/simulator/` | Generated media and torrents, fixture service on 58133, real seeder on 58134 (adb reverse), device tests; emulator identity checks in `simulator/emulator.sh`. |
| Local simulator | `casting/simulator.sh`, `casting/simulator/`, `casting/Simulator.command` | Builds/starts the real components, generates local fixtures, hosts test room/live screen, tests playback. No mock Chrome APIs in the real test room. |
| Fire TV maintenance | `scripts/`, `profiles/`, `restore-firetv.sh` | Separate owner-specific cleanup/recovery tools using Python and CleanYourTV. Not a casting runtime dependency. |
| CleanYourTV | Git submodule `cleanyourtv/` | Pinned upstream maintenance dependency. Do not vendor its repository or modify it for casting work. |
| Evidence | `backups/` (private, ignored) | Owner recovery records and historical screenshots. Preserve locally; never use as runtime configuration. |

Use Java for Android UI/Media3 and TypeScript for browser APIs. End users run a
compiled Rust helper and do not need Rust, Python or Node. Simulator development
requires toolchains; public signed desktop installers/compatibility testing are
separate unfinished release work. See `casting/SYSTEM_REVIEW.md` and
`docs/desktop-validation.md` for verified checks and the open Linux conversion
CI failure. Do not label all desktop platforms verified based on local success.

## Local prerequisites and startup

On this Mac the SDK is `~/Library/Android/sdk` and Java 21 is installed under
`~/Library/Java/JavaVirtualMachines/`. `ANDROID_HOME` need not be set; the launcher
discovers the SDK and a Java 21/17 runtime. Need Node/npm, Rust/C linker, Android
SDK platform-tools/emulator/command-line tools, SDK licenses, SDK platform 36 and
build-tools for Gradle. `simulator/setup.sh` installs the TV image on first run.
`native/build.sh --install` builds/packages/installs the helper. FFmpeg/ffprobe
are bundled when available; use `DANIILCAST_TOOLS_DIR` to supply them. Fixtures
use the installed helper's bundled tools, with a PATH fallback.

Run from the repository root:

```sh
./casting/simulator.sh             # Same as up; complete startup + verified sample
./casting/simulator.sh menu        # Interactive utility menu
./casting/simulator.sh install     # Current receiver → verified emulator only
./casting/simulator.sh open        # Open receiver and repair bridges
./casting/simulator.sh site        # Start/open real test room
./casting/simulator.sh status
./casting/simulator.sh logs
./casting/simulator.sh stop
```

Startup builds the helper and side panel too (`npm ci` if dependencies are
missing). Chrome must have `casting/extension/` loaded unpacked once. On first
pairing the launcher asks for the visible six-digit emulator code. Subsequent
launches reuse/select the emulator pairing. Reopen the side panel to refresh
its connection; after changing extension background code use Developer → Reload
extension (or Chrome's extension reload). Do not claim an installed extension
has refreshed just because a bundle was rebuilt.

Networking:

- Mac `127.0.0.1:8765` → ADB forward → receiver API `8765`.
- Emulator `127.0.0.1:58132` → ADB reverse → local test media/test room.
- Emulator `10.0.2.2` reaches a desktop conversion server. `10.0.2.15` is an
  internal guest address, **not** a pairing address to enter on the Mac.
- Local test room: `http://127.0.0.1:58132/`; its image is the real emulator.
  Mouse/touch on it sends bounded input only to the verified AVD. Control POSTs
  require same-origin JSON. Port 58131 is an optional sample-data design preview;
  it does not prove playback, pairing or native messaging.

The emulator/window/server detach from the launching terminal and keep running.
A port owned by another AVD is an error, not an invitation to stop that AVD.
Custom AVD/port require both `DANIIL_CAST_AVD` and `DANIIL_CAST_PORT`.
The debug receiver receives the emulator-only overlay app-op so a cast can open
from Home. No global security, certificate or app-verification switch is disabled.
The TV hardware profile must have `hw.screen=touch` to test actual pointer input;
setup preserves userdata and enables this. Cold restart after changing hardware.

## Verification

```sh
./casting/simulator.sh test
./casting/simulator.sh smoke
(cd casting/sidepanel && npm run check && npm test && npm run build)
(cd casting/native && cargo fmt --check && cargo clippy --locked --all-targets -- -D warnings && cargo test --locked)
```

Daniil TV (see `docs/STREAMING_APP_PLAN.md` and `streaming/native/README.md`):

```sh
./streaming/native/build-host.sh dev     # also asan, tsan, checks (libtorrent invariants)
sh streaming/simulator/tests/identity.sh  # mocked wrong-device refusals
./streaming/simulator.sh test            # engine via JNI + Media3 on the verified emulator
```

Required streaming tests use only generated fixtures, loopback peers and the
emulator: no public torrents, trackers, DHT or LAN. Keep piece hash checks on,
never substitute HTTP for the BitTorrent transfer, and keep the eviction
regressions passing under every `build-host.sh` variant before touching the
patch or moving libtorrent.

Android instrumentation covers long VTT/SRT/ASS cues after forward/backward seeks,
resumed load and late selection; certificate vs network errors; injected real
mouse and touchscreen events for menus, play/pause, skip, timeline drag, subtitle
selection, speed, up-next cancellation/play, retry/close, and optional remote keys.
Keep testing runner results (`OK (...)`); `adb am instrument` can exit zero on
failure. Fixtures run inside emulator loopback without internet or a LAN TV.

Smoke tests use the actual installed Rust helper and receiver: MP4/HLS/DASH,
position/play/pause, subtitles/style, audio, episodes, speed, quality, AV1→H.264
conversion and a cast from Home. They copy **only** the verified emulator pairing
to a private ignored store, preventing fallback to a saved physical TV. Evidence
is under `casting/simulator/.cache/smoke/evidence/`. Demo startup likewise uses
an isolated emulator-only store and verifies rendered, advancing playback.
For browser integration, cast a local page through the real Chrome side panel;
CLI smoke alone does not prove browser detection or UI interaction.

Do not remove `SubtitlePlayback`/`PausedSubtitleCues` compatibility handling without
passing real seek/selection regressions; a cue beginning before the seek target
must still appear. Pointer controls belong in production receiver sources, not
an emulator-only screen. Every actionable UI row/button must work by click/touch
and retain remote shortcuts. Preserve progress dragging and accessible labels.
Back uses the platform callback on Android 13+ and `onBackPressed` on older
versions; do not also consume its key-down and pop twice. See the official
[Back dispatcher reference](https://developer.android.com/reference/android/window/OnBackInvokedDispatcher).
Caption clearance is measured from the complete playback toolbar, including the
timeline’s 48 dp touch target; changing control sizes must keep captions above it.

## Git, cleanup and privacy

Use a `codex/` branch for changes. Inspect existing dirty work before staging;
never overwrite another agent's/user's edits. Commit coherent source/docs changes
and push when requested. Never commit generated APKs, Rust target/build folders,
node_modules, simulator caches, local.properties, devices.json, pairing tokens,
conversion control secrets, Chrome profiles, private logs or new device backups.
The generated extension assets are committed because Chrome loads them directly.
Keep tests, lockfiles, Gradle wrapper and CI. Keep the pinned CleanYourTV submodule
and recovery tools because the restore script depends on them. Avoid running a
physical-TV restore/cleanup to verify these docs. See `docs/firetv-cleanup.md`.
