# Testing Stream

A test earns its place by proving something the viewer gets: finding a title,
playing it, controlling it, keeping their data. A screenshot export, a copied
constant or an unchanged View instance proves none of that; screenshots are for
design review, not correctness.

## Rules

- Required tests use only generated media, loopback add-ons on 58133, the real
  BitTorrent seeder on 58134 and a deliberately untrusted TLS fixture on 58135,
  on the verified emulator. No public providers, trackers, DHT or LAN. Piece
  hash checks and TLS verification stay on; the TLS test must fail
  verification.
- Every device step goes through `./simulator.sh`, which checks the
  serial, `ro.kernel.qemu=1` and the AVD name first. Never select a device with
  `connectedAndroidTest`.
- Instrumentation has passed only when the runner prints `OK (n tests)`;
  `am instrument` can exit zero on failure. Runner output is kept in the ignored
  `simulator/.cache/evidence/`.
- `AppHarness` gives each test its own `test-*.db` store and player preferences
  and local-only peers. Those never install the default add-ons or run the
  background new-episode checks, and `clear_test_mode` removes the overrides
  after every run. The owner's database is never opened.
- Don't run measurements (`StartupTimingTest`, `profile`, `soak`) beside other
  device tests or builds.

## Focused regressions

Screen-state and Compose migration checks (JVM, no public providers):

```sh
(cd android && ./gradlew :app:testDebugUnitTest \
  --tests tv.stream.app.BrowseViewModelTest --tests tv.stream.app.NavigationViewModelTest \
  --tests tv.stream.app.PlaybackViewModelTest --tests tv.stream.app.KotlinModelSnapshotsTest \
  --tests tv.stream.app.PlaybackPreparationTest --tests tv.stream.app.PageSnapshotTest \
  --tests tv.stream.app.ui.PageControlsTest --tests tv.stream.app.ui.ComposePlayerControlsTest \
  --tests tv.stream.app.ui.PageRowsTest --tests tv.stream.app.SubtitleOffersTest)
```

These cover cancelled/late provider deliveries, retained navigation without old
activity callbacks, source retry state, frozen protocol/playback/render snapshots
and native/Compose control geometry, accessibility, remote activation and focus
through rebinding. Instrumented interaction coverage uses generated media on the
verified local emulator.

`PageRowsTest` drives the declarative Settings rows and split view inside a real
RecyclerView: labels, focus by key, D-pad moves between blocks (dispatched as
ViewRootImpl does), OK/Enter and touch clicks after a rebind, category selection,
Right/Left steering and restoring a pane row by key.

`PageRowFocusTest` (JVM) walks eight shelves down and back up through the page's
real `PageAdapter.Layout` after the top ones have scrolled away: every Up reaches
the next shelf and only the first reaches Refresh. On the emulator,
`PageNavigationTest` repeats that on Home and on a catalogue grid (Up reaches each
row, then the filters, then Back), including quick presses mid-scroll; opens
Settings from the rail, after visiting Home and directly, and requires a
category drawn focused (its layer-scaled bounds), not only focused for
accessibility; and checks that after quick moves only the focused poster's
artwork is grown, with its caption below it:

```sh
(cd android && ./gradlew :app:testDebugUnitTest --tests tv.stream.app.ui.PageRowFocusTest)
./simulator.sh data-test tv.stream.app.PageNavigationTest
```

`ScreenStateInteractionTest` recreates Settings and Details, then exercises the
restored actions by remote and touch and checks that Back pops one screen.

```sh
./simulator.sh data-test tv.stream.app.VideoTrackEndTest
(cd android && ./gradlew :app:testDebugUnitTest --tests 'tv.stream.app.VideoFrameWatchdogTest')
```

The generated six-second video tracks have independent eighteen-second audio
and subtitle tails. The device tests exercise platform and FFmpeg renderers,
wait beyond the stall interval, seek back into video and require normal completion.
Run these device cases serially, without native compilation competing for graphics.

## Baseline and Startup Profiles

```sh
./simulator.sh baseline-profile
```

Runs `ProfileJourneyTest` twice on local fixtures, each time from an empty ART
profile (`cmd package compile --reset`): `journey=startup` (cold launch to Home)
and `journey=browse` (Home, Details, Browse, Library, Settings panes, Search and
fixture playback). The test asks ART to save its profile (SIGUSR1); the dumped
classes and methods become `android/app/src/main/baselineProfiles/startup-prof.txt` and
`baseline-prof.txt`. The journeys run on the debug build, so a few debug-only
methods may be listed; AGP rewrites the rules through R8 for release. Raw dumps
stay in `simulator/.cache/evidence/baseline-profile/`. Run it alone.

On 30 October 2026 the capture produced 3,607 startup and 21,553 baseline rules.
A release build merged them with the libraries' profiles (25,078 rules), kept
13,416 after R8 (`assets/dexopt/baseline.prof`), and placed the startup classes
in the first dex file (641 KB of 3.7 MB). No before/after startup time is
recorded: debuggable builds don't use AOT profiles, and a release build ignores
the local-only overrides, so launching it here would install the public
starter add-ons.

## Routine

```sh
(cd android && ./gradlew :app:testDebugUnitTest)   # JVM, no device or network
./simulator.sh test     # engine + data + core app tests (what CI runs)
./simulator.sh smoke    # one catalogue → torrent → player flow
```

`test` runs the torrent module's JNI/Media3 suite (`TorrentEngineTest`,
`TorrentPlaybackTest`), the data suite (`AddonTest`, `StoreTest`,
`SourcePolicyTest`) and the core app tests: `AppFlowTest`,
`PlayerInteractionTest` and four `AcceptanceTest` paths (MP4/MKV/HLS/DASH,
bounded failover, recovery after frames, file and magnet imports). `smoke` is
`AppFlowTest#catalogueFavouritesPlaybackControlsAndEvictedRewindWork`: frames,
seeks, a rewind into evicted cache and a saved favourite. `acceptance` adds
the rest of `AcceptanceTest` (imports, settings rows, search, pagination).

CI (`simulator/ci.sh` on a dedicated Linux emulator) runs `test`,
`process-death`, `release-test` and `profile`; GitHub Actions builds every
variant, runs the JVM tests and lint, and the native suites under each
sanitizer.

## JVM tests

| Area | Classes |
| --- | --- |
| Add-on protocol, store, built-in Internet Archive | `addon.StoreProtocolTest`, `addon.TorrentAddonStoreTest`, `addon.ArchiveItemTest`, `addon.ReleaseInfoTest`, `addon.ProviderExecutorTest`, `BrowseCataloguesTest` |
| New episodes: what Home announces, when it stops, background checks against a loopback add-on | `NewEpisodesTest` |
| Source ranking and torrent sources | `SourceSortTest`, `AutoSourceRankTest`, `TorrentSourcesTest` |
| Playback budgets and estimates | `StartupWatchdogTest`, `VideoFrameWatchdogTest`, `PlaybackDemandTest`, `PlaybackCoordinatorTest`, `NextEpisodeOfferTest`, `WatchCompletionTest`, `EpisodeDateTest` |
| Error QR reports (decodes every part, checks redaction) | `DiagnosticReportTest`, `ErrorQrPanelTest` |
| Subtitle fonts: saved values, kept emphasis, Cyrillic fallback (API 26 and 36) | `SubtitleFontsTest` |
| Focus growth never clipped; player timeline layout (Robolectric) | `FocusEffectsTest`, `PlayerTimelineLayoutTest` |
| Skeletons drawn in their page's shape (never the spinner), Search focus scrolling margin, focus shadow exit (Robolectric) | `ui.LoadingBlocksTest` |

Two are opt-in because they reach the internet: `-PuiSnapshots=true` renders
the real screens at 1920×1080 into `android/app/build/ui-snapshots/` and fails on any
control the D-pad can't reach (`UiSnapshotTest`, live Cinemeta and Internet
Archive), and `-PliveAddons=true` resolves eight public-domain films through
live add-ons, archive.org and Wikidata and decodes a frame 30 minutes in with
FFmpeg (`RealFilmsLiveTest`, evidence in `android/app/build/live-evidence/`).

## Focused device selections

`data-test` takes any class or method list and forwards runner arguments:

```sh
./simulator.sh data-test 'tv.stream.app.SeriesBrowseTest,tv.stream.app.NewEpisodesScreenTest'
```

| Area | Classes |
| --- | --- |
| Browse, loading and recovery | `BrowseRecoveryTest` (counted retries, Reload, saved posters, Back viewports), `DiscoveryRecoveryTest`, `HomeSchedulingTest`, `LoadingSurfacesTest`, `LoadingTest`, `SearchModesTest` |
| Navigation and focus | `VerticalNavigationTest`, `NavigationChromeTest`, `ChromeContinuityTest`, `PageNavigationTest`, `TransitionFocusTest`, `InPlaceFocusTest` (toggles keep the same focused view), `BrowsePolishTest`, `InterfacePolishTest` (keyboard languages) |
| Series and new episodes | `SeriesBrowseTest` (seasons, episode menu, visible-season artwork), `NewEpisodesScreenTest` (Home row, spotlight, NEW flags, Details default, Hide) |
| Artwork | `TitleArtworkTest` (cold, missing and late logos) |
| Popups | `PopupInteractionTest`, `PopupMotionTest` (painted frames), `PlayerPopupTest` |
| Player | `PlayerPresentationTest`, `PlayerLifecycleTest` (screensaver, sleep, Home), `SubtitleBoxTest`, `SubtitlePagePreviewTest`, `SubtitleFontRenderingTest`, `AutoplayInteractionTest`, `WatchCompletionInteractionTest`, `InstalledTitlePlaybackTest` |
| Startup and recovery | `StartupPipelineTest`, `ProgressiveRecoveryTest` |
| Sounds | `ui.SoundAssetsTest`, plus `python3 sounds/verify.py` for the WAVs |

`VerticalNavigationTest` checks page-specific hidden/wrapped defaults (independent of scrolling), circular button geometry,
first-Left wrapped focus, second-Left expansion, wrapped Up/Down and held-key repeat handling,
painted overlay morph frames with fixed page/poster bounds, outside-click/Back dismissal, hidden-page remote and pointer behavior,
compact measured label widths and larger-text expansion without ellipses or content movement,
icon-only toolbar controls, focus restoration and Settings/Search handoffs,
clock formats, empty/error screens, subpages and menu clearance. Its PNGs are saved in
app-private `files/vertical-navigation/`.

`SearchModesTest` checks horizontal keyboard navigation, navigation remaining hidden for remote/pointer/touch,
plain result clipping, grid fades with no visible row crossings during column changes,
rapid reversals, activity recreation during both transition phases, selected-film restoration
and mode switches without repeating provider requests.
The reflow regression observes overlapping keyboard/grid fades, the keyboard's longer eased tail,
and position/opacity continuity when direction changes.
`InterfacePolishTest#keyboardLanguagesKeepTheQueryAndFillEveryRowWithoutVoiceInput` checks all eight
languages and three character pages, including equal panel insets on both axes, fixed query/key top anchors
and bottom safe-area clearance.
`BrowsePolishTest#keyboardRowsAlignAndEmptyScreensStayNavigable` also checks keyboard focus/hover growth,
equal visible width growth across letter/medium/full-width keys, return to resting scale,
stable row geometry and pointer hover preserving the remote's focused key.
`FilmFocusNavigationTest` checks adjacent Library grid cards, column-preserving vertical moves,
Home shelf transitions and movement back through a scrolled shelf before entering navigation.

`DesignAuditCapture` is a screenshot utility, not a test: `after`,
`otherStates` and `providerFailures` write PNGs to app-private
`files/design-audit-*/` for manual review.

## Measurements and lifecycle

```sh
./simulator.sh data-test 'tv.stream.app.StartupTimingTest'   # 20 HTTP + 20 cold torrent starts
./simulator.sh decoder-test     # generates the codec matrix, then DecoderPlaybackTest
./simulator.sh process-death    # kills playback after durable progress; checks recovery
./simulator.sh release-test     # shrunk release JNI probe; restores the debug APKs
./simulator.sh soak             # thirty real minutes of playback and seeks
./simulator.sh profile          # dumpsys memory/graphics and a screenshot
./simulator.sh data-test tv.stream.app.NavigationPerformanceTest  # isolated navigation timings and frame metrics
./simulator.sh software-profile  # a minute each of generated 1080p 8-bit and 10-bit software video
```

The navigation performance test records ten Home → Details → Home journeys,
per-open latency, process PSS samples and window frame durations, and a
`settings` journey (six category moves down and up, three times, by remote:
per-move focus latency, frame durations and PSS) to
`simulator/.cache/evidence/navigation-performance.json`. Run it alone; its
debug-build emulator numbers are not a real-device benchmark, and debuggable
builds run the app's Compose code without AOT compilation while View toolkit
code is precompiled in the platform.

On 30 October 2026 two runs each on the verified emulator compared the View
Settings (commit b9528ec) with the Compose Settings: median category move 46 and
48 ms vs 60 and 55 ms, p95 271 and 321 ms vs 140 and 281 ms, frame p95 67 and
67 ms vs 84 and 197 ms over 126–130 vs 94–95 frames; final PSS 140 and 133 MiB vs
133 and 120 MiB. Every frame exceeded 16.7 ms in both
on this emulator. The Compose pane is slower to move between categories in this
debug build; a release or physical-TV measurement has not been made. Dropped frame
metric reports indicate missing measurements, not necessarily dropped frames.
After the remaining page blocks moved to Compose (same emulator, debug build, one
run each, measured alone): Settings category moves median 55 → 54 ms, p95 281 →
319 ms, frame p95 197 → 167 ms over 94 frames, final PSS 120 → 121 MiB; Home →
Details median 49 → 57 ms (max 64 → 60), frame p95 41 → 47 ms, final PSS 126 →
124 MiB. Single debug runs on a shared host are within run-to-run noise; they show
no large regression, not a speed-up, and say nothing about release builds or TVs.
`COMPILE_SPEED=1` compiles the debuggable app ahead of time before a data test.
With it, the same journeys measured Settings median 67 ms, p95 389 ms, frame p95
163 ms over 113 frames, final PSS 95 MiB, and Home → Details median 57.5 ms (max
72), frame p95 50 ms, PSS 86 MiB: no faster than JIT, with every frame still over
16.7 ms, so this emulator's latency is not explained by JIT warm-up and gives no
evidence for a Compose change. A non-debuggable build would lose `run-as`, which
the evidence copies and test-store cleanup rely on, so none is measured here.
The software profile forces FFmpeg video on a looped generated 1080p H.264
fixture and samples process CPU, PSS, playback and dropped-frame telemetry every
five seconds for a minute. Its local evidence is `software-performance.json` and `software-performance-10bit.json`;
process CPU includes the whole app and instrumentation, not only conversion.

`StartupTimingTest` targets, on fixtures: Details controls p95 ≤ 500 ms, HTTP
first audio and video p95 ≤ 3 s, cold torrent p95 ≤ 5 s; traces go to
app-private `files/validation-startup-timing.json`. They say nothing about public
providers or swarms. See [playback preparation](playback.md)
and [decoders](decoders.md).

`release-test` builds with `-PreleaseValidation=true`, signing with the local
debug key for validation only (ordinary release output is unsigned), and runs
`ReleaseProbeInstrumentation`, a small platform runner that keeps AndroidX test
code out of the shrunk app. It opens no owner database and uses local-only
peers.

An earlier libtorrent-era soak failed after about 27 minutes (`Timed out: soak
seek 159`). Two Rust-engine runs have since passed (the second, after the
scheduling and eviction fixes, in 1,829.992 s with `OK (1 test)`). A failing soak
now writes a `failure` record with the player state and engine diagnostics; see
[the soak investigation](soak-investigation.md).

## Live checks (optional, internet)

Never required, never against a LAN TV, only with public-domain films. Each uses
an isolated database and installs nothing into owner data; none supplies
accounts or credentials.

```sh
./simulator.sh live-film       # films installed through the store play on the emulator
./simulator.sh readme-media    # README screenshots and GIFs (docs/media), public-domain films only
./simulator.sh live-addons     # every current store entry: manifest, resources, review install
./simulator.sh live-torrents   # one public-domain torrent per sampled provider
./simulator.sh live-torrents Torrentio,Comet   # a subset; names without spaces
./simulator.sh requested-title tt1234567       # the accepted providers, for one authorised title
```

`live-addons` also covers the earlier Nuvio list in the test-only asset
`android/app/src/androidTest/assets/nuvio-addon-audit.json`; source counts there are
discovery, not playback. `live-torrents` and `requested-title` turn on an
instrumentation-only public-network mode: no LAN discovery or port mapping, and
peers, trackers and DHT nodes on private or reserved addresses are filtered
(host and JNI regressions prove a loopback seeder can't be reached in it).
Reports land in `simulator/.cache/evidence/live-*.json`. Public
services change, so a result describes that run only.

If public IPs work but DNS fails inside the emulator, start the stopped AVD with
`STREAM_DNS_SERVERS=1.1.1.1,8.8.8.8 ./simulator.sh start`.

`./simulator.sh start` draws with the host GPU. On a machine without a usable GPU,
start it with `STREAM_GPU=swiftshader ./simulator.sh start` (software rendering, slower).
