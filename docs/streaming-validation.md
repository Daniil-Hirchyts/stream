# Daniil TV implementation and validation

Work continues on `codex/streaming-app-plan`. The complete specification remains
[STREAMING_APP_PLAN.md](STREAMING_APP_PLAN.md); the standalone UI and full-app
smoke/soak verification are still unfinished.

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

These are local host/emulator results. GitHub CI, physical Fire OS performance,
ARM32 runtime behavior, release shrinking, resource profiling, and the complete
standalone-app acceptance matrix have not yet been verified. No physical TV was
contacted or modified.

## Add-ons and persistence, 2026-10-21

`streaming/android/:app` now builds the `tv.daniil.stream` data layer. It has no
launcher or playback activity yet. The engine remains in `:torrent`; the app
uses the same shared playback dependency as Daniil Cast.

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

Still required: the complete UI and playback host/lifecycle wiring, source
ranking/failover and ambiguous file-selection flow, full-app live test room,
smoke/30-minute soak/profiling, shrunk release verification, and emulator CI.
Data-layer tests do not establish those unfinished application behaviors.
