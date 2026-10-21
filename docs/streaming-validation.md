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
