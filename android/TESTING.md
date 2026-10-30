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
  --tests tv.stream.app.ui.ComposePageBlockTest --tests tv.stream.app.ui.ComposePlayerControlsTest)
```

These cover cancelled/late provider deliveries, retained navigation without old
activity callbacks, source retry state, frozen protocol/playback/render snapshots
and native/Compose control geometry, accessibility, remote activation and focus
through rebinding. Instrumented interaction coverage uses generated media on the
verified local emulator.

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

Two are opt-in because they reach the internet: `-PuiSnapshots=true` renders
the real screens at 1920×1080 into `app/build/ui-snapshots/` and fails on any
control the D-pad can't reach (`UiSnapshotTest`, live Cinemeta and Internet
Archive), and `-PliveAddons=true` resolves eight public-domain films through
live add-ons, archive.org and Wikidata and decodes a frame 30 minutes in with
FFmpeg (`RealFilmsLiveTest`, evidence in `app/build/live-evidence/`).

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
./simulator.sh software-profile  # one minute of generated 1080p software video and memory samples
```

The navigation performance test records ten Home → Details → Home journeys,
per-open latency, process PSS samples and window frame durations to
`simulator/.cache/evidence/navigation-performance.json`. Run it alone; its
debug-build emulator numbers are not a real-device benchmark. Dropped frame
metric reports indicate missing measurements, not necessarily dropped frames.
The software profile forces FFmpeg video on a looped generated 1080p H.264
fixture and samples process CPU, PSS, playback and dropped-frame telemetry every
five seconds for a minute. Its local evidence is `software-performance.json`;
process CPU includes the whole app and instrumentation, not only conversion.

`StartupTimingTest` targets, on fixtures: Details controls p95 ≤ 500 ms, HTTP
first audio and video p95 ≤ 3 s, cold torrent p95 ≤ 5 s; traces go to
app-private `files/validation-startup-timing.json`. They say nothing about public
providers or swarms. See [playback preparation](../PLAYBACK_IMPLEMENTATION.md)
and [decoders](DECODERS.md).

`release-test` builds with `-PreleaseValidation=true`, signing with the local
debug key for validation only (ordinary release output is unsigned), and runs
`ReleaseProbeInstrumentation`, a small platform runner that keeps AndroidX test
code out of the shrunk app. It opens no owner database and uses local-only
peers.

An earlier soak failed after about 27 minutes (`Timed out: soak seek 159`); its
cause has not been established. A subsequent Rust-engine emulator run completed
in 1,813.923 seconds with `OK (1 test)`; its local evidence is retained under
`simulator/.cache/evidence/soak-rust/`. See [Known gaps](README.md#known-gaps).

## Live checks (optional, internet)

Never required, never against a LAN TV, only with public-domain films. Each uses
an isolated database and installs nothing into owner data; none supplies
accounts or credentials.

```sh
./simulator.sh live-film       # films installed through the store play on the emulator
./simulator.sh live-addons     # every current store entry: manifest, resources, review install
./simulator.sh live-torrents   # one public-domain torrent per sampled provider
./simulator.sh live-torrents Torrentio,Comet   # a subset; names without spaces
./simulator.sh requested-title tt1234567       # the accepted providers, for one authorised title
```

`live-addons` also covers the earlier Nuvio list in the test-only asset
`app/src/androidTest/assets/nuvio-addon-audit.json`; source counts there are
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
