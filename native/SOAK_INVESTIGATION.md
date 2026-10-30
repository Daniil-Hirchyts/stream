# Soak timeout at seek 159: investigation

The thirty-minute emulator soak (`./simulator.sh soak`, `SoakTest.playbackAndSeekSoak`) once failed with
`Timed out: soak seek 159` after 1,616.396 s. This note records what the retained evidence proves, what it doesn't,
and what was checked on the host. Evidence paths are relative to `simulator/.cache/evidence/` (ignored, local only).

## Summary

| Question | Answer | Status |
| --- | --- | --- |
| Which engine produced the failing run? | The C++/libtorrent engine (before the Rust replacement) | Proven |
| Is the failing run's state at seek 159 retained? | No: only a progress snapshot at cycle 93 survives | Proven |
| Does the Rust engine reproduce it on the host? | No: 200 soak-shaped seeks (and the routine stress tests) never stalled | Proven for the host model only |
| Does the Rust engine pass the emulator soak? | One run passed (183 cycles, `OK (1 test)`) | Proven for that one run |
| Cause of the original timeout | Not established | Hypotheses below |

## The failing run

- The 2026-10-23 09:20:06 instrumentation record (under the app's former package name) and `current-run/soak-final.log`: `Timed out: soak seek
  159` at `SoakTest.java:30`, `Time: 1,616.396`, one failure.
- `current-run/final-sequence.json`: stage `soak-final` started at epoch 1792735141.7 (2026-10-23 07:59:01 on the
  host clock), ran 1,631.3 s, exit code 1. It followed `live-addons-final`, `live-torrents-final` and `live-film-final`,
  which used the internet (public providers and torrents) on the same emulator just before the soak.
- The log names the former package's `SoakTest`, the former local AVD on `emulator-5556`, the former `streaming` tree and
  "Native sources ready in .../native/.deps" (the libtorrent/Boost/OpenSSL source fetch).
- In git, the soak ran between commits `2e44bcd` (2026-10-23 08:15) and its predecessor; the failure was first written
  up in `0447e57` (2026-10-23 11:41). Both trees contain `streaming/native/src/engine.cpp`,
  `patches/libtorrent/0001-evict-pieces.patch` and `0002-pending-adjacent-hash.patch`, and no Rust engine. `dff58fb`
  (2026-10-30 07:20) first added `native/core`, `1a2cfa7` (2026-10-30 08:59) replaced libtorrent, `2c64cb6` added uTP,
  MSE and web seeds. **The failing run used libtorrent.**

### What the test was waiting for

`SoakTest` seeks every ~10 s, cycling targets 35 s, 1 s and 15 s of the 45-second film, and waits up to 30 s
(100 ms polls) for `playing && target + 1 s <= position < target + 10 s`. Seek 159 is `159 % 3 == 0`: the 35 s
target, so the window that passes is 36-45 s, ending at the end of the film. A seek that takes more than 30 s to play
fails; so would one where playback stopped (ended or stalled) before the poll saw 36-45 s.

### What was retained, and what wasn't

- `current-run/soak-progress.json` (written 08:14:27, during the run) is the failing run's own state at **cycle 93**
  (903,673 ms): every seek recovered within 2,011 ms, one peer and one seed throughout, 0 hash failures, 15 slots and
  15,732,736 allocated bytes, PSS 129-149 MiB with no upward trend, native heap flat at 30 MiB. Nothing was degrading
  half-way through.
- `current-run/soak.json` is **not** the failing run: it says `complete: true` with 177 cycles, and its numbers
  (1,800,009 ms, total download 3,987,777,921, last PSS 135,631,872) are those of `soak-summary.json`, written
  2026-10-22 09:03 by the earlier passing run. It was copied in afterwards (mtime 09:20:06, together with the
  instrumentation file), not pulled from the failed run.
- The failure screenshot the harness saves on the device (`validation-failure.png`) and any logcat for the run were
  not retained. There is therefore no record of the player, engine or swarm state at seek 159.
- Why: `simulator.sh soak` pulls `files/validation-soak.json` only after the tests succeed
  (`run_data_tests tv.stream.app.SoakTest || exit $?`, then the pull; the same in `2e44bcd`'s
  `streaming/simulator.sh`). The test rewrites that file after every seek, so a failed run's samples up to the
  failure exist on the device but are never copied.

### Other runs, for context

| Evidence | Engine | Result |
| --- | --- | --- |
| `partial-soak/v2-callback-abort.txt` | libtorrent | SIGABRT in the former native library (2026-10-21 23:07 in the torrent tests, 2026-10-22 08:04 in the app): the v2 callback abort fixed before the next runs |
| `partial-soak/before-v2-callback-fix.json` | libtorrent | 3 cycles, then that abort |
| `partial-soak/before-lifecycle-review.json` | libtorrent | 70 cycles over 693,660 ms, no `complete` flag; why it stopped isn't recorded |
| `soak-instrumentation.txt`, `soak-summary.json` (2026-10-22) | libtorrent | `OK (1 test)`, 1,813.183 s, 177 cycles, slowest seek 3,466 ms |
| The 2026-10-23 09:20:06 instrumentation record (former package name) | libtorrent | **Timed out at seek 159** |
| `soak-rust/runner.txt`, `soak-rust/soak.json`, `soak.json` (2026-10-30) | Rust | `OK (1 test)`, 1,813.923 s, 183 cycles, slowest seek 3,369 ms, 0 hash failures, max PSS 177,164,288 |

So the libtorrent engine passed once and failed once with the same test, and the Rust engine has passed once. One pass
is not proof that an intermittent failure is gone.

## Host reproduction attempt (Rust engine)

`native/core/tests/seek_stress.rs` models the soak on loopback: a generated film, 1 MiB pieces, a 16 MiB cache
(15 slots, as on the emulator), the app's bitrate (12 Mbit/s), Media3's access pattern (close the reader and open
another at every seek), the soak's 35/1/15 s targets alternating with pseudo-random positions, and every byte
checked. A seek fails if it delivers nothing within the soak's 30 s.

| Test | Seeks | Seeder | First verified byte after a seek | Result |
| --- | --- | --- | --- | --- |
| `soak_shaped_seeks_at_the_simulator_rate` (ignored; release build) | 200, 4.5 MB played after each, hybrid | 4 MiB/s, as `simulator.sh` | p50 1.23 s, p95 1.30 s, max 1.33 s; 1,477 evictions | Pass, 421 s |
| `many_seeks_through_a_small_cache_never_stall` (debug) | 40, v1 | 16 MiB/s | p50 0.34 s, max 0.58 s; 244 evictions | Pass |
| `many_seeks_in_v2_and_hybrid_torrents_never_stall` (debug) | 15 each | 16 MiB/s | max 1.95 s (v2), 0.39 s (hybrid) | Pass |

Run the long one with `./native/build-host.sh release --ignored soak_shaped` (`STREAM_SEEK_STRESS_SEEKS=n` changes
the count). The 200-seek run was made twice: with the engine as committed before this investigation's fix (the row above) and
after it (p50 1.23 s, p95 1.33 s, max 1.35 s, 1,482 evictions, 421 s). The routine tests pass before and after.

The host model leaves out the emulator, Media3's buffering and decoder, Android's scheduler and storage, and process
memory pressure. Not reproducing on the host does not clear the app.

## Defect found while investigating

**A newly opened reader pulled the read-ahead window back to the start of the file.** A reader started at position 0
with a fresh `last_read`, so it became the controller's primary reader before its first read. Media3 seeks by closing
its reader and opening a new one; until the new one read, the controller re-prioritised pieces from the start of the
file, queued requests for them ahead of the seek target's, and evicted verified pieces to make room.
`a_reopened_reader_keeps_the_window_until_it_reads` demonstrates it: after an unread reopen the old engine downloaded
11,534,336 bytes and evicted 8 pieces it had to fetch again. Readers now place the window only after their first read
or seek (`UNPOSITIONED` in `engine.rs`). The same logic existed in the libtorrent-era engine (`engine.cpp` in
`2e44bcd`: `open_reader` sets `last_read` without a position, `update` takes the newest reader's position).

The gap between Media3's open and first read is short, so this usually costs at most an extra request round and some
evictions; the host runs show it does not, alone, produce a 30-second stall. It is a plausible contributor to slow
seeks, not a proven cause of the timeout.

## Hypotheses for the original failure (not proven)

1. **libtorrent-era piece or request starvation.** With a single seeder, an urgent block whose request is lost or
   held waits for the request timeout. The work that followed (`f6a6153`, 2026-10-25) added `urgentReservationFailures`
   and `obsoletePartials` counters and a partial-piece pressure test, which suggests slot starvation by unfinished
   pieces was suspected then; no evidence from the failing run confirms it.
2. **The reopened-reader window jump above**, making the seek's first pieces wait behind start-of-file requests and
   evictions. Present in that engine; effect measured on the host as small.
3. **Player or test-side timing.** The 35 s target passes only while 36-45 s plays and the film ends at 45 s; a seek
   that recovered late, or playback that ended or paused before a poll, fails the same way. Not checkable without the
   run's samples or screenshot.
4. **Emulator or host load.** The soak followed internet-facing stages on the same emulator; nothing retained shows
   contention, and nothing rules it out.

## Remaining risk in the Rust engine

- A request is re-asked of another peer after 2 s only for urgent pieces and only when another peer has the piece;
  otherwise it expires after `REQUEST_TIMEOUT` (30 s, `bt/torrent.rs`), the same length as the soak's limit. With the
  simulator's single seeder, a request lost without a reject would stall that block for 30 s. Not observed in any host
  or emulator run.
- Requests for pieces that left the window are not cancelled; at 4 MiB/s with the 250-request cap they can delay a
  seek's pieces by up to about a second.

## To close this out

- Make `simulator.sh soak` pull `validation-soak.json`, `validation-failure.png` and logcat whether or not the test
  passed (outside `native/`; not changed here).
- Run the emulator soak several more times on the Rust engine with that evidence kept.
- On a failure, pull the files before re-installing the app (re-installing clears `files/`).
