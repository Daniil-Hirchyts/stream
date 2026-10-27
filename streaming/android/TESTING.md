# Testing Daniil TV

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
- Every device step goes through `./streaming/simulator.sh`, which checks the
  serial, `ro.kernel.qemu=1` and the AVD name first. Never select a device with
  `connectedAndroidTest`.
- Instrumentation has passed only when the runner prints `OK (n tests)`;
  `am instrument` can exit zero on failure. Runner output is kept in the ignored
  `streaming/simulator/.cache/evidence/`.
- `AppHarness` gives each test its own `test-*.db` store and player preferences
  and local-only peers. Those never install the default add-ons or run the
  background new-episode checks, and `clear_test_mode` removes the overrides
  after every run. The owner's database is never opened.
- Don't run measurements (`StartupTimingTest`, `profile`, `soak`) beside other
  device tests or builds.

## Routine

```sh
(cd streaming/android && ./gradlew :app:testDebugUnitTest)   # JVM, no device or network
./streaming/simulator.sh test     # engine + data + core app tests (what CI runs)
./streaming/simulator.sh smoke    # one catalogue → torrent → player flow
```

`test` runs the torrent module's JNI/Media3 suite (`TorrentEngineTest`,
`TorrentPlaybackTest`), the data suite (`AddonTest`, `StoreTest`,
`SourcePolicyTest`) and the core app tests: `AppFlowTest`,
`PlayerInteractionTest` and four `AcceptanceTest` paths (MP4/MKV/HLS/DASH,
bounded failover, recovery after frames, file and magnet imports). `smoke` is
`AppFlowTest#catalogueFavouritesPlaybackControlsAndEvictedRewindWork`: frames,
seeks, a rewind into evicted cache and a saved favourite. `acceptance` adds
the rest of `AcceptanceTest` (imports, settings rows, search, pagination).

CI (`streaming/simulator/ci.sh` on a dedicated Linux emulator) runs `test`,
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
./streaming/simulator.sh data-test 'tv.daniil.stream.SeriesBrowseTest,tv.daniil.stream.NewEpisodesScreenTest'
```

| Area | Classes |
| --- | --- |
| Browse, loading and recovery | `BrowseRecoveryTest` (counted retries, Reload, saved posters, Back viewports), `DiscoveryRecoveryTest`, `HomeSchedulingTest`, `LoadingSurfacesTest`, `LoadingTest`, `SearchModesTest` |
| Navigation and focus | `NavigationChromeTest`, `ChromeContinuityTest`, `PageNavigationTest`, `TransitionFocusTest`, `BrowsePolishTest`, `InterfacePolishTest` (keyboard languages) |
| Series and new episodes | `SeriesBrowseTest` (seasons, episode menu, visible-season artwork), `NewEpisodesScreenTest` (Home row, spotlight, NEW flags, Details default, Hide) |
| Artwork | `TitleArtworkTest` (cold, missing and late logos) |
| Popups | `PopupInteractionTest`, `PopupMotionTest` (painted frames), `PlayerPopupTest` |
| Player | `PlayerPresentationTest`, `PlayerLifecycleTest` (screensaver, sleep, Home), `SubtitleBoxTest`, `SubtitlePagePreviewTest`, `AutoplayInteractionTest`, `WatchCompletionInteractionTest`, `InstalledTitlePlaybackTest` |
| Startup and recovery | `StartupPipelineTest`, `ProgressiveRecoveryTest` |
| Sounds | `ui.SoundAssetsTest`, plus `python3 streaming/sounds/verify.py` for the WAVs |

`DesignAuditCapture` is a screenshot utility, not a test: `after`,
`otherStates` and `providerFailures` write PNGs to app-private
`files/design-audit-*/` for manual review.

## Measurements and lifecycle

```sh
./streaming/simulator.sh data-test 'tv.daniil.stream.StartupTimingTest'   # 20 HTTP + 20 cold torrent starts
./streaming/simulator.sh decoder-test     # generates the codec matrix, then DecoderPlaybackTest
./streaming/simulator.sh process-death    # kills playback after durable progress; checks recovery
./streaming/simulator.sh release-test     # shrunk release JNI probe; restores the debug APKs
./streaming/simulator.sh soak             # thirty real minutes of playback and seeks
./streaming/simulator.sh profile          # dumpsys memory/graphics and a screenshot
```

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

The last soak failed after about 27 minutes (`Timed out: soak seek 159`); it
hasn't been investigated. See [Known gaps](README.md#known-gaps).

## Live checks (optional, internet)

Never required, never against a LAN TV, only with public-domain films. Each uses
an isolated database and installs nothing into owner data; none supplies
accounts or credentials.

```sh
./streaming/simulator.sh live-film       # films installed through the store play on the emulator
./streaming/simulator.sh live-addons     # every current store entry: manifest, resources, review install
./streaming/simulator.sh live-torrents   # one public-domain torrent per sampled provider
./streaming/simulator.sh live-torrents Torrentio,Comet   # a subset; names without spaces
./streaming/simulator.sh requested-title tt1234567       # the accepted providers, for one authorised title
```

`live-addons` also covers the earlier Nuvio list in the test-only asset
`app/src/androidTest/assets/nuvio-addon-audit.json`; source counts there are
discovery, not playback. `live-torrents` and `requested-title` turn on an
instrumentation-only public-network mode: no LAN discovery or port mapping, and
peers, trackers and DHT nodes on private or reserved addresses are filtered
(host and JNI regressions prove a loopback seeder can't be reached in it).
Reports land in `streaming/simulator/.cache/evidence/live-*.json`. Public
services change, so a result describes that run only.

If public IPs work but DNS fails inside the emulator, start the stopped AVD with
`DANIIL_CAST_DNS_SERVERS=1.1.1.1,8.8.8.8 ./streaming/simulator.sh start`.
