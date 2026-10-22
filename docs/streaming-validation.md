# Daniil TV implementation and validation

The standalone application is implemented on `codex/streaming-app-plan`.
The [complete specification](STREAMING_APP_PLAN.md) remains the acceptance
baseline. The first two sections record earlier stages; final application
verification is recorded below and is currently in progress.

## Native engine, 2026-10-21

Verified locally on macOS ARM64, then Android TV 16/API 36 on
`emulator-5556` / `DaniilCast_Local_TV`:

- `./streaming/native/build-host.sh dev`: 29 passed.
- `./streaming/native/build-host.sh asan`: 29 passed (ASan/UBSan).
- `./streaming/native/build-host.sh tsan`: 29 passed.
- `./streaming/native/build-host.sh checks`: 15 cache/eviction regressions passed
  under libtorrent's invariant checks. The upstream magnet invariant limitation
  is documented in the [engine README](../streaming/native/README.md).
- `sh streaming/simulator/tests/identity.sh`: 11 passed.
- `./streaming/simulator.sh test`: `OK (12 tests)` through JNI and Media3 with
  CheckJNI explicitly enabled for the run and the prior emulator setting restored,
  including v1/v2/hybrid byte hashes after cache turnover, cancellation, Unicode
  names, multi-file selection, container-end indexes and first-frame/advancing
  playback followed by a rewind into evicted data, and repeated close/reopen
  while payload writes and hash jobs are in flight.
- Gradle built JNI libraries for armeabi-v7a, arm64-v8a and x86_64.

The first actual device run exposed ext4 extent-block overhead beyond the
configured cache budget and a loopback fixture that finished downloading before
the playback assertion. Cache sizing now reserves filesystem overhead, and the
fixture seeder has a 4 MiB/s rate limit. The passing run checks allocated bytes
against the original budget, without relaxing that assertion.
An additional repeated run exposed late disk/hash completions outliving the
session's peer allocator. Disk workers now hold an Asio work guard until their
completion callbacks are posted, drain cancelled work during asynchronous abort,
and keep the hash worker alive until the disk worker finishes submitting jobs.
Dedicated host and Android regressions cover shutdown during transfer.

At this stage, release shrinking, resource profiling and standalone-app
acceptance were still pending; their later results appear below. GitHub CI,
physical Fire OS performance and ARM32 runtime behavior remain unverified.
No physical TV was contacted or modified.

## Add-ons and persistence, 2026-10-21

This stage added the `tv.daniil.stream` data layer in `streaming/android/:app`,
before the launcher and playback activities were implemented. The engine remains
in `:torrent`; the app uses the same shared playback dependency as Daniil Cast.

Local checks on the same API 36 AVD:

- `./streaming/simulator.sh test`: engine/playback `OK (12 tests)`, then
  add-on/persistence `OK (13 tests)`.
- After adding catalogue-to-native source integration coverage,
  `./streaming/simulator.sh data-test`: `OK (14 tests)`.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:lintDebug`
  pass. Lint retains advisory dependency-version, HTTP-support and missing-icon
  warnings; launcher/icon work belongs to the unfinished standalone UI.

Coverage includes configured paths and escaped query values, provider episode
IDs, resource/type/prefix filtering, source-less episodes, explicit file zero
and invalid indexes, HTTP/magnet/info-hash candidates, unsupported results,
source-only headers, subtitle matching hints, partial search, pagination, stale
metadata fallback, decompressed response limits, certificate rejection, four
concurrent requests, request/discovery deadlines and configuration cancellation.
The catalogue-to-source test obtains torrent metadata from the actual loopback
seeder and reads verified payload through JNI; the app contains no fixture
catalogue or fake engine implementation.

Storage coverage includes Keystore encryption/authentication and ciphertext in
SQLite/WAL, disabled backup, add-on order/enabling/removal/catalogue visibility,
favourites/history and resume across reopen, watched/unwatched state, language
preferences, version-1-to-2 migration, metadata cache bounds, removal of playable
URLs from saved metadata, encrypted imported descriptors, private copied torrent
files, traversal refusal and metadata-only imports without payload downloads.

Runner evidence is local and ignored:
`streaming/simulator/.cache/evidence/tv.daniil.torrent.test-instrumentation.txt`
and `tv.daniil.stream.test-instrumentation.txt`. Public providers, public peers,
trackers, DHT and the physical TV are not used by these tests. The deliberately
untrusted TLS fixture on emulator loopback port 58135 is rejected without
modifying certificate trust.

These data-layer results alone did not establish application behavior. The
standalone implementation and final acceptance work are described below.

## Standalone application, 2026-10-22

`tv.daniil.stream` installs separately from Daniil Cast. Its Java/XML and
RecyclerView screens provide onboarding, enabled catalogue rows, search,
details, seasons/episodes, source selection, library/favourites/history,
imports, settings and Developer views. Cinemeta is an explicit onboarding
offer; skipping providers leaves manual imports available.

The playback host resolves fresh sources, preserves provider episode IDs,
validates hints and actual Media3 formats against the 1080p profile, and limits
automatic pre-frame failover to three candidates. Explicit choices remain
explicit. Ambiguous multi-file sources show a file chooser. The host saves
progress and language selections and releases Media3/native work when hidden.
An application-owned serial worker orders an outgoing native close before a
new player can create its engine. Startup removes disposable files left by a
killed process. Shared subtitle folders are isolated by session and load;
late cancelled downloads cannot recreate retired files.

The independent launcher now starts/installs/opens the app and serves a live
test room on 58133, with the real emulator image and bounded mouse/touch/remote
input. Controls require same-origin JSON and verify the emulator identity.
The seeder remains real BitTorrent on 58134. App tests use isolated databases,
player preferences and loopback-only engine settings. Production release
configuration has no fixture preferences or built-in catalogue data.

The API 36 TV image has no system document picker. The app handles that absence
and supports opening/sharing `.torrent` content URIs from a file manager. The
test APK's separate content provider supplies actual generated metainfo; the
production importer copies/encrypts it and the player obtains payload from the
real seeder. No broad storage permission is requested.

Release builds use R8/resource shrinking and the pinned NDK for final native
symbol stripping. The APK contains armeabi-v7a, arm64-v8a and x86_64 libraries
and full native dependency notices. The local release probe explicitly signs
with the Android debug key for validation; ordinary release output is unsigned.
It opens no owner database and checks native loading, verified torrent bytes
and the exception constructor called from JNI.

The workflow now includes native sanitizer variants, safety/input tests,
debug/release ABI builds and deterministic Linux emulator runs for both player
consumers. A workflow-dispatch input enables the full thirty-minute soak.
Remote GitHub execution has not been performed in this local session.

The final combined debug run passed all 39 tests: engine/JNI/Media3 12,
add-ons/storage/source policy 16, and application flows/acceptance/profiling 11.
The application suite covers onboarding and all navigation/settings actions,
real pointer/touch/remote input, search/pagination/focus restoration,
favourites/history, URL/magnet/content-URI imports, explicit multi-file choice,
HTTP MP4/MKV/HLS/DASH, track/language selection and persistence, bounded
automatic failover and explicit-source failures. Separate series playback
resolves two provider episode IDs independently. Leaving playback releases
payload resources and preserves resume state.

The real process-death test also passed (`OK (1 test)`). The host terminated
the process only after rendered, advancing playback and durable progress at
31 seconds; the new process cleared orphan payload and resumed playback from
the saved descriptor/progress. The prepare runner's process-crashed output is
expected at the deliberate kill; the resumed runner must report success.

### Controlled playback measurements

These are one API 36 ARM64 emulator run with generated 1080p media, a 16 MiB
test cache and the local BitTorrent seeder. They are not physical Fire OS
performance guarantees or latency percentiles.

| Measurement | Observed |
| --- | --- |
| First rendered frame | 4,012 ms |
| Cached seek recovery | 1,106 ms |
| Uncached forward / backward seek recovery | 931 / 1,346 ms |
| Accumulated buffering during the short profile | 2,818 ms |
| PSS / RSS / peak RSS | 130.0 / 276.9 / 286.7 MiB |
| Native / Java allocated heaps | 31.2 / 30.6 MiB |
| Allocated payload cache | 15,732,736 bytes, below 16 MiB |
| Downloaded bytes / evictions / hash failures | 50,375,028 / 46 / 0 |
| Dropped video frames | 1 |

Image: `sdk_google_atv64_arm64`, Android TV 16/API 36, arm64-v8a,
`google/sdk_google_atv64_arm64/emu64a:16/BT2A.260319.001/15058170:user/dev-keys`.
The AVD has 4 GiB RAM; this does not establish operation on a 1 GiB stick.
The first-frame sample downloaded 35,651,584 bytes of the larger generated
movie and recorded advancing playback before the entire payload arrived.
Profile JSON and a rendered-frame screenshot remain in ignored local evidence.

### Release and repository compatibility

`./streaming/simulator.sh release-test` passed (`OK (1 test)`) against the
R8/resource-shrunk release APK. The probe uses Android platform instrumentation
and the fixed JNI entry points: R8 is allowed to merge Java-only wrappers,
without preserving extra test-runner dependencies in the application. It
checks the pinned engine version, real loopback BitTorrent bytes including
Unicode paths, native invalid-file exception construction, and released cache
files. The script restores the debug app/test APKs and the prior CheckJNI setting.

The receiver regressions passed (`OK (9 tests)`), including real pointer input
and long subtitle cues across seeks/selection. The installed helper smoke
passed MP4/HLS/DASH, episodes, four subtitle tracks, audio switching, seeks,
speed/style, DASH quality, AV1 conversion and casting from Home. Side-panel
TypeScript checks, all five tests and the build passed with no committed-bundle
change. Rust fmt/clippy (warnings denied) passed; tests passed nine with one
optional FFmpeg conversion test ignored. The actual AV1 conversion is covered
by the passing helper/receiver smoke. Android debug/release lint has no errors
and 22 advisory warnings. All three native ABIs build; runtime evidence is ARM64.

CLI casting does not establish a refreshed installed Chrome extension or a
new browser-interaction result. The existing unrelated Linux conversion CI
failure remains documented in [desktop validation](desktop-validation.md).
Remote CI, API 28 compatibility, ARM32/x86_64 runtime playback, physical Fire OS
performance and public-provider availability have not been established by this
local run. Production signing/distribution is separate release work.

### Extended verification

The first finalized soak exposed a v2 hash-callback ordering assertion after
three cycles. A new deterministic native regression reproduces the exact abort
before the fix: root-only metadata can validate an adjacent piece whose own
hash job is still outstanding. `0002-pending-adjacent-hash.patch` leaves that
piece's download state for its own completion callback. It preserves actual
Merkle verification and every picker assertion. With the patch, the native
host suites pass 30 tests each in normal, ASan/UBSan and TSan builds, plus 16
cache/eviction regressions with full libtorrent invariant checks. The native
fix is committed as `e1e6dbe`.

An emulator provider-deadline assertion exceeded its existing 17-second limit
while the native variants were rebuilding. The full emulator run is being
repeated after those builds finish, retaining the same deadline and recording
elapsed time on any failure. Interrupted/failed runs remain separate evidence;
they do not count as a completed thirty-minute soak.
