# Streaming test review

Routine checks should demonstrate that the viewer can find a title, play it,
control it and keep their data. A passing screenshot export, copied constant
or unchanged Java View instance is weak evidence for that experience.

## What runs

- `./streaming/simulator.sh test`: unchanged native JNI/Media3 and data suites,
  plus **10 active app tests**, down from 19. The app selection covers starter
  review/partial installation, store review/configuration, torrent playback
  before full download, bounded cache/evicted rewind, favourites, next episode,
  MP4/MKV/HLS/DASH, bounded failover and recovery, file/magnet playback, and
  real pointer/remote player controls including subtitles and timeline drag.
- `./streaming/simulator.sh smoke`: **one** catalogue-to-torrent playback test,
  down from seven app tests. It checks advancing frames, seeks, cache eviction
  and a saved favourite.
- `./streaming/simulator.sh acceptance`: the broader active app tests, including
  import rename/remove, settings persistence through UI rows, search/pagination
  and focused-card viewport checks. These four tests are now opt-in because
  their longer UI paths add breadth beyond the routine playback gate.
- JVM tests still cover protocol parsing, source sorting/filtering, catalogue
  filters and startup timeout behaviour without a device or public network.
  `FocusEffectsTest` (Robolectric, offline) focuses every control on browse,
  Search and Settings and fails when any ancestor clips a grown control; it
  also checks that a row's first tile grows from its leading edge without
  moving the shelf, and that the player's buttons grow whole and its skip
  label moves nothing. Its snapshots land in `app/build/focus-effects`.

Native cache/eviction tests, sanitizer builds, emulator identity refusals,
TLS rejection, encrypted persistence, migrations, request cancellation and
bounded transport concurrency remain enabled. CI still runs the separate
process-death/resume and release JNI probes. Live providers, thirty-minute soak,
profiling and screenshot rendering remain explicit diagnostic commands.

## Disabled checks

Twelve checks are retained with JUnit `@Ignore` and an inline reason, so they
are visible as skipped rather than silently deleted. `acceptance` does not
reactivate them.

| Test | Why disabled / retained evidence |
| --- | --- |
| `SubtitleSettingsTest` (3 methods) | Copies color/font constants, fixes preview heights and row order, and calls setters without checking rendered output. Player interaction tests retain real subtitle selection and persisted style. Visual preview rendering needs manual review. |
| `TorrentSourcesTest.twoHundredResultsRankQuickly` | One elapsed-time sample in Robolectric does not measure performance on a TV. Functional ranking tests remain. |
| `TorrentSourcesTest.fallbackTrackersAreAddedOnlyWhenNoneAreNamed` | Duplicates the tracker-content checks in `StoreProtocolTest`; checking Java object identity adds no user outcome. |
| `AppFlowTest.emptyResourceSetupRootRequiresConfiguredAddressBeforeInstallation` | Duplicates protocol setup validation and the store UI test's configured-address/review/install flow. |
| `AppFlowTest.firstRunCanSkipProvidersAndNavigateWithPointerAndRemote` | The harness disables starter defaults, so empty Home does not validate production first-run setup. Dedicated persistence and input tests remain. Real first-run default installation still needs its own useful test. |
| `AppFlowTest.backgroundReleasesPayloadAndResumeReconstructsPlayback` | Checks history/cache cleanup but never presses Resume or observes resumed frames. Durable progress and actual resume remain covered by store and process-death tests; ordinary background/foreground cleanup needs a replacement. |
| `AcceptanceTest.dialogFadeKeepsFocusedRoundedOptionVisible` | Saves an image and dismisses a dialog, without asserting its claimed visibility or fading. |
| `PlayerInteractionTest.playGlyphHasTheSameOpticalCentreAsPause` | Locks rasterized icon alignment rather than a functioning control. |
| `LoadingTest.favouriteUpdateRetainsDetailsAndNeverShowsPageSkeleton` | Couples the test to adapter events and View identity. The core flow checks that the favourite is saved; rendering continuity needs visual review. |
| `LoadingTest.sortingSourcesKeepsBackAndOnlyUpdatesSourceContent` | Checks selected flags and View identity, never displayed ordering. Source ordering remains covered by `SourceSortTest`; Back has navigation coverage. |

To run the retained loading/navigation checks explicitly:

```sh
./streaming/simulator.sh acceptance tv.daniil.stream.LoadingTest,tv.daniil.stream.NavigationChromeTest
```

Disabled tests should return only after their assertions prove the advertised
behaviour. Screenshot exports remain useful for design review, but should not
be reported as automated visual correctness checks.

## Focused startup/decoder work

`data-test` now forwards its optional class/method filter and runner arguments:

```sh
./streaming/simulator.sh data-test 'tv.daniil.stream.StartupPipelineTest'
./streaming/simulator.sh data-test 'tv.daniil.stream.ProgressiveRecoveryTest'
./streaming/simulator.sh data-test 'tv.daniil.stream.AcceptanceTest#pressuredPayloadResetKeepsTheChosenEpisodeAndPosition'
./streaming/simulator.sh data-test 'tv.daniil.stream.AcceptanceTest#activityRecreationRetainsPauseSpeedPositionAndNativeTelemetry,tv.daniil.stream.AcceptanceTest#manuallyChangingSourcesRetainsTheCurrentPausedPositionAndSpeed'
./streaming/simulator.sh data-test 'tv.daniil.stream.StartupTimingTest'
```

The timing test records 20 HTTP and 20 cold-engine torrent starts and individual
stage traces. `-e transport ui` or `-e transport http-mp4` selects one transport
when investigating a failure. Do not run other device tests or builds alongside
measurements. See [implementation and local evidence](../PLAYBACK_IMPLEMENTATION.md)
and [decoder coverage/builds](DECODERS.md). `decoder-test` generates the complete
synthetic codec matrix before running its focused class.

`StartupPipelineTest` covers claimed preparation ownership, cancellation,
explicit refresh, late inline episode sources, probe timeout/TLS/status handling,
redirected DASH and ranged reopens, and the normal progressive read timeout
after probing. `ProgressiveRecoveryTest` plays a late provider's source after the
first provider fails. The native `TorrentEngineTest` includes a fresh-engine
snapshot recovery with magnet bootstrap peers as its only discovery path.
The [verification report](../PLAYBACK_VERIFICATION.md) records the selected
checks; it does not imply that every existing suite was run.
