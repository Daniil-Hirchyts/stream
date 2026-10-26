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

Eleven checks are retained with JUnit `@Ignore` and an inline reason, so they
are visible as skipped rather than silently deleted. `acceptance` does not
reactivate them.

| Test | Why disabled / retained evidence |
| --- | --- |
| `SubtitleSettingsTest` (3 methods) | Copies color/font constants, fixes preview heights and row order, and calls setters without checking rendered output. Player interaction tests retain real subtitle selection and persisted style. Visual preview rendering needs manual review. |
| `TorrentSourcesTest.twoHundredResultsRankQuickly` | One elapsed-time sample in Robolectric does not measure performance on a TV. Functional ranking tests remain. |
| `TorrentSourcesTest.fallbackTrackersAreAddedOnlyWhenNoneAreNamed` | Duplicates the tracker-content checks in `StoreProtocolTest`; checking Java object identity adds no user outcome. |
| `AppFlowTest.emptyResourceSetupRootRequiresConfiguredAddressBeforeInstallation` | Duplicates protocol setup validation and the store UI test's configured-address/review/install flow. |
| `AppFlowTest.firstRunCanSkipProvidersAndNavigateWithPointerAndRemote` | The harness disables starter defaults, so empty Home does not validate production first-run setup. Dedicated persistence and input tests remain. Real first-run default installation still needs its own useful test. |
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

Loading, episode completion and Search layout changes have focused loopback
emulator checks (see [the loading audit](LOADING_UI_REVIEW.md)):

```sh
./streaming/simulator.sh data-test 'tv.daniil.stream.SearchModesTest,tv.daniil.stream.LoadingSurfacesTest,tv.daniil.stream.LoadingTest#slowSourcesKeepHeaderAndBackAndCancelWithoutReplacingDetails,tv.daniil.stream.SeriesBrowseTest#episodeMenuUsesItsSavedPlaybackAndWatchedState,tv.daniil.stream.SeriesBrowseTest#seasonsFollowFocusAndKeepShowTitleAboveEpisodes'
```

`BrowseRecoveryTest` uses a loopback HTTP provider hosted inside the emulator.
Its six focused checks cover counted automatic retries, bounded failure and
inline Reload, saved-poster fallback, cancellation during backoff, no retries
for invalid/authentication responses, legitimate empty results, cached
pagination, and Back focus/viewport restoration in category grids, Library,
Search, Home shelves and Settings panes. Request counts verify that Back and
recent-page returns avoid network work. Aged Home/Search snapshots plus a
favourite mutation exercise returning after a long film without a real wait.

```sh
./streaming/simulator.sh data-test 'tv.daniil.stream.BrowseRecoveryTest'
```

`PlayerLifecycleTest` replaces the old ignored background-cleanup check. It
opens a generated film over real BitTorrent, starts/stops the system screensaver,
sleeps/wakes the emulator and leaves/returns through Home/the launcher. It
checks that the same Media3 media and native torrent retain the downloaded
payload, position and speed; Resume must render new frames, and closing the
player (including while stopped) must release its cache. No screensaver or
power settings are changed.

```sh
./streaming/simulator.sh data-test 'tv.daniil.stream.PlayerLifecycleTest,tv.daniil.stream.AcceptanceTest#activityRecreationRetainsPauseSpeedPositionAndNativeTelemetry'
```

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

## Popup, episode options and automatic playback changes

Focused offline checks (never the complete existing suite):

```sh
(cd streaming/android && ./gradlew :app:testDebugUnitTest --tests tv.daniil.stream.AutoSourceRankTest --tests tv.daniil.stream.NextEpisodeOfferTest --tests tv.daniil.stream.SourceSortTest)
./streaming/simulator.sh data-test 'tv.daniil.stream.PopupInteractionTest,tv.daniil.stream.AutoplayInteractionTest,tv.daniil.stream.SeriesBrowseTest#episodeMenuUsesItsSavedPlaybackAndWatchedState,tv.daniil.stream.SeriesBrowseTest#seasonsFollowFocusAndKeepShowTitleAboveEpisodes,tv.daniil.stream.InterfacePolishTest#keyboardLanguagesKeepTheQueryAndFillEveryRowWithoutVoiceInput,tv.daniil.stream.PlayerInteractionTest#pointerMenusTracksStyleAndTimelineWorkInTheAppPlayer,tv.daniil.stream.WatchCompletionInteractionTest#nearEndExitCanKeepThePlaceCancelOrMarkWatched'
```

Popup checks assert one window across three levels, rendered morphs from the actual
opener at all four screen corners, safe final bounds, aligned icon rows, parent
focus restoration, no dim/blur/backdrop layer and no Close/Cancel/Back row. Both
remote exit keys cancel long lists, forms and message-only panels, including the
first arrow when switching from pointer input. Playback failures retain Retry and
Choose source and exit with Left. Episode tests assert no child option buttons and
exercise the card's contextual menu and its single watched/unwatched action. Keyboard checks cover every language and
character page. Autoplay tests resolve advertised choices and play real generated
BitTorrent episodes over loopback, verify the selected source mark, test a manual
pre-ending offer with autoplay both on/off, dismissal, advancing to the correct
next episode and no offer on the last one. Peer counts and sizes are advertised
fixture hints; these checks do not claim internet throughput measurements.

For the popup-placement and dismissal work alone, run:

```sh
./streaming/simulator.sh data-test 'tv.daniil.stream.PopupInteractionTest,tv.daniil.stream.SeriesBrowseTest#episodeMenuUsesItsSavedPlaybackAndWatchedState,tv.daniil.stream.SeriesBrowseTest#seasonsFollowFocusAndKeepShowTitleAboveEpisodes,tv.daniil.stream.WatchCompletionInteractionTest#nearEndExitCanKeepThePlaceCancelOrMarkWatched'
```

## Shared app/player popup consistency

A focused set for button/popup changes (13 checks, not the full existing suite):

```sh
./streaming/simulator.sh data-test 'tv.daniil.stream.PopupInteractionTest,tv.daniil.stream.PlayerPopupTest,tv.daniil.stream.PlayerInteractionTest#pointerMenusTracksStyleAndTimelineWorkInTheAppPlayer,tv.daniil.stream.PlayerInteractionTest#arrowsReachOptionsAndSelectSubtitlesWithoutAMenuKey,tv.daniil.stream.PlayerPresentationTest#logosReplaceTitlesAndTheFirstActionTogglesWatchedBothWays,tv.daniil.stream.AutoplayInteractionTest#manualNextWorksWithAutoplayOffAndUsesTheNextEpisodesSource'
```

`PlayerPopupTest` verifies that the native menu is never visible, focusing a font
only previews it, selection commits to both preferences stores, Back/Left cancels
nested and fallback source choices without recreating the player/torrent or
resetting download counters, and actual source selection retains position, pause
and speed. The source checks use the real generated torrent and loopback seeder.

`DesignAuditCapture` is an explicitly selected diagnostic utility, not part of the
normal regression set. `after`, `otherStates` and `providerFailures` write PNGs to
app-private `files/design-audit-{after,other}/` for the main flows, empty states,
store and local HTTP-503 failures. `before` is for capturing a baseline before
editing. Use the verified emulator wrapper when pulling evidence. The review is
in `UI_CONSISTENCY_REVIEW.md`; screenshots stay in the ignored simulator cache.

## Findings follow-up: recovery, content sizing and the page subtitle preview

Select only the relevant checks:

```sh
./streaming/simulator.sh data-test 'tv.daniil.stream.DiscoveryRecoveryTest,tv.daniil.stream.SubtitlePagePreviewTest,tv.daniil.stream.PopupInteractionTest#nestedPagesMeasureTheirContentAndMorphNarrowWideNarrow,tv.daniil.stream.PopupInteractionTest#longChoicesKeepTheSelectionAndBothRemoteExitKeysCancel,tv.daniil.stream.PopupInteractionTest#menusGrowFromTheirOpenerAndStayInsideEveryScreenEdge,tv.daniil.stream.PopupInteractionTest#textEntryValidatesCommitsAndCanBeLeftWithoutSaving,tv.daniil.stream.BrowseRecoveryTest#searchLibraryAndHomeRestoreTheirOwnFocusAndNestedViewports,tv.daniil.stream.BrowseRecoveryTest#transientCategoryFailuresRetryWithCountsAndRecover,tv.daniil.stream.SearchModesTest#remoteAndPointerSwitchModesAnimateAndPreserveTheQueryAndSelectedFilm,tv.daniil.stream.PlayerPopupTest#subtitleFocusOnlyPreviewsAndSelectionCommitsToBothSettingsStores'
```

These 12 checks cover healthy-provider request counts during failed-resource
retries, the three-attempt limit, HTTP 401 stopping without retry, cancellation
when leaving Search, pointer/remote Reload in both Search modes, failed versus
successful-empty results, and existing category retry behaviour. Popup checks
measure a narrow → wide → narrow stack, intermediate animated widths, exact parent
geometry/focus restoration, edge clearance, long selected lists and entry forms.
The page-preview check compares actual rendered preview bitmaps on focus, cancel
and selection, asserts no duplicate popup preview, and checks both saved stores.
Back-navigation and Search-mode checks guard the selected card, exact viewport
and provider-request reuse. The old `SubtitleSettingsTest` JVM class is ignored
upstream and is not counted as validation; these emulator checks cover the live UI.

Follow-up evidence is in ignored
`streaming/simulator/.cache/evidence/current-audit/findings-resolved/`.

## Soft sound set

Sound-only work uses the focused source/asset check:

```sh
streaming/sounds/build/refinement-venv/bin/python streaming/sounds/verify.py
```

It verifies all 28 app WAVs against the approved B · Soft audition, regenerates
them from source and checks formats, levels, endpoints and Android playback
windows. `tv.daniil.stream.ui.SoundAssetsTest` additionally loads every packaged
cue into Android SoundPool and starts/stops each at zero volume. It opens no
activities, owner database, providers or preferences. Build the debug app and
test APK, then run **only this class** through `device` after sourcing
`streaming/simulator/emulator.sh` and passing `require_emulator`. Require the
runner's `OK (1 test)` result; a zero ADB exit code alone is insufficient.

## Popup motion

`PopupMotionTest` checks actual painted frames: refreshing an unchanged menu
must keep its focused action visible, and rapid submenu/Back/reopen input must
settle on the right page with restored focus and no leftover transition layers.
The refresh regression failed before the fix (white focus briefly became the
dark panel colour); all sampled frames stay white after it. Its frame colours
and transition contact sheet are written to app-private `validation-popup-*`
files, exported locally under `streaming/simulator/.cache/evidence/popup-motion/`.

The focused animation selection is six checks:

```sh
./streaming/simulator.sh data-test 'tv.daniil.stream.PopupMotionTest,tv.daniil.stream.PopupInteractionTest#nestedPagesMeasureTheirContentAndMorphNarrowWideNarrow,tv.daniil.stream.PopupInteractionTest#menusGrowFromTheirOpenerAndStayInsideEveryScreenEdge,tv.daniil.stream.PopupInteractionTest#longChoicesKeepTheSelectionAndBothRemoteExitKeysCancel,tv.daniil.stream.PlayerPopupTest#subtitleFocusOnlyPreviewsAndSelectionCommitsToBothSettingsStores'
```

It covers content-sized geometry, every anchor edge, long selected lists,
remote/pointer navigation, interrupted motion and real player subtitle menus.
Contact sheets use software drawing and do not reproduce Android's hardware
outline clipping; review actual emulator screen captures for the panel corners.
