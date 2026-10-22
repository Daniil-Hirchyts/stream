# Daniil TV implementation and validation

All five Daniil TV phases are complete and locally verified on
`codex/streaming-app-plan`, including the full thirty-minute soak on 2026-10-22.
The [complete specification](STREAMING_APP_PLAN.md) remains the acceptance
baseline. The first two sections record earlier stages; final application
verification is recorded below. Remote CI and physical Fire OS performance
remain unverified.

| Phase | Delivery | Local verification |
| --- | --- | --- |
| 1 | Shared playback library | Receiver 9 tests and installed-helper casting smoke |
| 2 | Native torrent engine / bounded cache | 30 tests each in normal, ASan/UBSan and TSan builds; 16 invariant-check regressions; 12 JNI/Media3 tests |
| 3 | Add-ons / encrypted persistence | 16 provider, store and source-policy tests |
| 4 | Standalone screens / playback host | 11 application acceptance/profile tests |
| 5 | Complete emulator validation | Process death, shrunk release JNI, compatibility checks and 30-minute soak passed |

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
The final ordinary unsigned APK is 28,767,773 bytes (27.4 MiB).
Final `:app:assembleRelease`, `:app:lintDebug` and `:app:lintRelease` pass;
each lint variant reports zero errors and 22 advisory warnings.

The workflow now includes native sanitizer variants, safety/input tests,
debug/release ABI builds and deterministic Linux emulator runs for both player
consumers. A workflow-dispatch input enables the full thirty-minute soak.
Remote GitHub execution has not been performed in this local session.

`./streaming/simulator.sh test` passed all 39 tests: engine/JNI/Media3 12,
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
First-frame timing starts when the chosen source opens. Seek measurements
end after playback advances at least 250 ms beyond the target, including any
required native read/download recovery.

| Measurement | Observed |
| --- | --- |
| First rendered frame | 3,955 ms |
| Cached seek recovery | 1,226 ms |
| Uncached forward / backward seek recovery | 871 / 1,237 ms |
| Accumulated buffering during the short profile | 2,579 ms |
| PSS / RSS / peak RSS | 129.7 / 280.3 / 290.0 MiB |
| Native / Java allocated heaps | 32.4 / 30.3 MiB |
| Allocated payload cache | 15,732,736 bytes, below 16 MiB |
| Downloaded bytes / evictions / hash failures | 59,829,428 / 48 / 0 |
| Dropped video frames | 2 |

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
while the native variants were rebuilding. The repeated data suite passed all
16 tests after those builds finished, retaining the same deadline and recording
elapsed time on any failure. Interrupted/failed runs remain separate evidence;
they do not count as a completed thirty-minute soak.

### Completed thirty-minute soak

`./streaming/simulator.sh soak` passed (`OK (1 test)`) on the final native/app
sources, commits `e1e6dbe` and `970ba74`. The runner used 1,800,009 ms of real
elapsed time; it did not shorten the required duration. It completed 177
forward/backward seek and advancing-playback cycles through the real loopback
BitTorrent seeder, using a 16 MiB test cache and generated media larger than
that cache.

| Measurement | Observed |
| --- | --- |
| Duration / successful cycles | 30 minutes / 177 |
| Peak / final PSS | 144.8 / 129.3 MiB |
| Maximum allocated payload cache | 15,732,736 bytes; below 16 MiB throughout |
| Evictions / hash failures | 3,791 / 0 |
| Payload downloaded | 3,987,777,921 bytes |
| Seek-to-advancing p50 / p95 / maximum | 2,079 / 2,408 / 3,466 ms |
| Accumulated buffering / dropped frames | 175,948 ms / 342 |

The soak seek clock includes waiting for at least one second of advancing
playback beyond each target. Buffering and frame drops include all 177 seeks;
these values describe that workload, not uninterrupted movie playback.
Memory includes the app's instrumentation code; fixture services are separate
host processes. No native crash, stalled seek, failed hash, or cache-bound
violation occurred in the completed run. Physical-device performance remains
unverified.

Ignored evidence under `streaming/simulator/.cache/evidence/` includes
`final-suite.txt`, `host-{dev,asan,tsan,checks}.txt`, `playback-profile.json/png`,
`process-death/`, `release/`, `receiver-final.txt`, `casting-smoke-final.txt`,
`soak.json`, `soak-summary.json` and `soak-instrumentation.txt`. Failed/partial
runs are retained separately. `EndTestMode` passed after the soak and removed
only the four harness overrides, restoring the normal database/configuration
selection without opening or changing owner data. The standalone app is left
available on the verified emulator.

## tvOS-style interface and source explanations, 2026-10-22

The browse UI was rebuilt in an Apple TV style:
- **Layout:** floating tabs, full-screen artwork that cross-fades behind a scrim, and a Home spotlight that follows the focused card (logo or title, facts, description).
- **Shelves:** horizontal rows with lifting cards.
- **Details:** a page over the backdrop with Play/Sources and round icon buttons.
- **Split layouts:** Settings, Imports and Developer.
- **Styling:** rounded dialog sheets throughout.

Screens are now `ui.Page`/`ui.Item` lists built on workers (`BrowseScreens`, `TitleScreens`, `SetupScreens`) and drawn by `ui.PageAdapter`. The 500-line `MainActivity` is now a shell. The shared player takes the same neutral palette through app resource overrides only, so Daniil Cast is unchanged.

Review problems fixed:
- **Duplicate Home rows:** a provider's film and series catalogues now read "Popular Films" and "Popular Series".
- **Clipped cards:** card text keeps a fixed one-line title and subtitle, and artwork keeps its aspect ratio. Grid cards are sized by weight, not by a possibly stale width.
- **Low storage:** the player offers to clear this app's own caches or switch to a 128 MiB cache, then retries.

"No playable sources" on every title came from the emulator profile having only Cinemeta, which supplies catalogues and metadata but no streams. Details and Sources now say "No source add-on installed" and offer to add one. The player reports the actual cause:
- no source add-on;
- named failing providers;
- only unsupported results (with reasons);
- no results.

No source provider is installed or suggested by the app. A profile that already has an add-on or import now skips onboarding.

The first run exposed one regression. A shelf heading equal to a card's title (case-insensitively) took the test's click. Headings now carry the provider in the same text.

Local results on `emulator-5556` / `DaniilCast_Local_TV`:
- **`./streaming/simulator.sh test`:** `OK (12 tests)`, `OK (16 tests)`, `OK (11 tests)`, exit 0.
- **Release and lint:** `:app:assembleRelease`, `:app:lintDebug` and `:app:lintRelease` pass with 0 errors and 16 warnings (previously 22).
- **Evidence:** screenshots and the runner log are in ignored `streaming/simulator/.cache/evidence/redesign/`.
- **Not re-run:** the soak, process-death and release-probe runs were not repeated for this UI-only change.

## Add-on store, Internet Archive and real films, 2026-10-22

Cinemeta supplies catalogues and metadata only, so a profile holding just
Cinemeta has nothing to play. This change adds the Add-ons tab (store), an
opt-in built-in Internet Archive add-on and discovery fixes for public
torrents; see the [app README](../streaming/android/README.md#store).

This work was done in a Linux cloud container without KVM, so **no emulator
suite ran here**: `./streaming/simulator.sh test`, `smoke`, `live-film` and the
new instrumentation (`AddonTest` store test, `AppFlowTest` store flow,
`LiveFilmTest`) are written but unexecuted. The container also has no
BitTorrent egress, so a public torrent swarm was not exercised. No physical TV
was contacted.

Executed here:

- With the pinned NDK 29.0.14206865 and CMake 3.31.6 (native OpenSSL and
  libtorrent built for all three ABIs), `:app:assembleDebug`,
  `:app:assembleDebugAndroidTest`, `:app:assembleRelease`, `:app:lintDebug` and
  `:app:lintRelease` pass. Each lint variant reports 0 errors and the same 16
  advisory warnings as before; none is in new code. The unsigned release APK
  is 28,805,651 bytes.
- After merging main's navigation, search and loading-state work (the store
  becomes the fourth tab beside Home, Library and Settings), the same builds,
  offline JVM tests and both lint variants pass again: 0 errors, 24 warnings,
  none in the store, listing or built-in add-on code. Main's existing
  instrumentation still taps the removed Imports, History and Search tabs and
  needs updating before `./streaming/simulator.sh test` can pass.
- `:app:testDebugUnitTest`: 6 offline JVM tests pass (store protocol, listing
  rules, built-in add-on, torrent fallback trackers, Archive file selection).
- `:app:testDebugUnitTest -PliveAddons=true`: all 10 pass, including the 4
  live `RealFilmsLiveTest` cases against Stremio's directory, Cinemeta, Public
  Domain Movies, OpenSubtitles v3, archive.org and Wikidata.
- `node --test streaming/simulator/tests/*.test.mjs` (2) and
  `streaming/simulator/tests/identity.sh` (11) pass; the fixture service's new
  `addon_catalog` routes answer as the instrumentation expects.

Live results (FFmpeg seeks to 30:00 over HTTPS and decodes a frame from the URL
the app's add-on code resolved):

| Film | Internet Archive (direct HTTPS) | FFmpeg | Public Domain Movies (torrent) |
| --- | --- | --- | --- |
| Night of the Living Dead (tt0063350) | 360p, 271 MB | H.264 Baseline 496×360, 97 min | 1080p, 1.51 GB |
| The Kid (tt0012349) | none linked | — | 1080p, 859 MB |
| Nosferatu (tt0013442) | 240p, 384 MB | H.264 Baseline 320×240, 93 min | — |
| The General (tt0017925) | 480p, 933 MB | H.264 Main 720×480, 107 min | — |
| His Girl Friday (tt0032599) | 480p, 548 MB | H.264 Baseline 640×480, 92 min | — |
| Charade (tt0056923) | linked item withdrawn | — | — |
| The Last Man on Earth (tt0058700) | 720p, 3.7 GB | H.264 Main 1686×720, 87 min | 1080p, 1.23 GB |
| House on Haunted Hill (tt0051744) | 720p, 3.5 GB | H.264 High 1280×720, 119 min | 1080p, 1.24 GB |

Night of the Living Dead also resolved from Cinemeta search and metadata to
the Public Domain Movies torrent (info hash, file 0, no tracker hints, so
playback adds the public trackers) and to 69 OpenSubtitles v3 tracks; the
English one has 1,035 SubRip cues. The Twilight Zone's 218 Cinemeta episodes
keep IDs such as `tt0052520:1:1`, encoded as one path segment.

Problems the real data exposed, now handled: Archive items that split a film
into 20-minute parts or include trailers (complete copies rank first);
11 GB masters near 15 Mbit/s (dropped when a lighter copy exists);
withdrawn items linked from Wikidata (skipped); Wikimedia refusing a
User-Agent sent after `Accept`/`Accept-Encoding` (caller headers now come
first) and throttling bursts (one retry); archive.org answering 5xx under load
(the live test retries FFmpeg once).

## tvOS interface pass and responsiveness, 2026-10-22

**Freezes.** Every button, chip, row and card was its own BlurView. Below
Android 12, BlurView blurs on the CPU before every frame, once per control,
so a Fire TV (Android 9) stalled while scrolling. Fixes:
- **Blur:** only the floating tab bar and search field blur, and only live on
  Android 12+ (RenderEffect). Earlier versions draw a static material.
  Controls are plain platters.
- **Blocked screen loads:** `AddonRepository.newRequest()` runs on the UI
  thread for every screen, but it locked the monitor that catalogue workers
  hold while writing the SQLite cache. Request bookkeeping now has its own lock.
- **Main-thread read:** the player's low-storage prompt read its cache
  preference from SQLite on the main thread; it now reads it on the loader.
- **Repeated work:** artwork is cached on disk (40 MiB, LRU) as well as in
  memory, and off-screen shelves keep their views, so scrolling back no longer
  rebuilds rows or downloads art again.

**Look.** The interface follows tvOS:
- **Focus:** focused controls turn white with dark text, grow and cast a
  shadow. Others rest on translucent platters; list rows are transparent.
- **Tab bar:** Search sits inside the tab pill, which hides as content
  scrolls under it.
- **Spacing:** page titles scroll with the page. List headings are small and
  grey and align with row labels. Spacing, sizes and grid gutters are evened
  out, and logos are trimmed of transparent margins so they align with text.
- **Alerts and backdrops:** alerts have a centred title and stacked
  full-width buttons. Sources shows blurred artwork. HTML entities in add-on
  text are decoded.

**Bugs the snapshot harness exposed:**
- **Choice lists:** a long list in an alert drew over Cancel and past the
  alert's edge. The alert surface does not clip, so focused buttons can grow;
  that also left the ScrollView unclipped. A clipping frame with room for the
  focus lift fixes it. The licence alert and the search results grid had the
  same flaw; scrolled results drew over the heading and search field.
- **Long messages:** an alert's message did not scroll, so a long one (such as
  playback diagnostics) pushed its buttons off screen. The old AlertDialog
  scrolled; the message now scrolls inside the alert once it would overflow.
- **Search results:** they stopped 52 dp above the screen. They now run to
  the bottom edge, and the focused row stays inside the safe area.
- **Focus jumps:** right on a shelf's last card, or on the last button of a
  details page, jumped to a tab. Left and right now stay in the content, a
  shelf holds focus at its ends, and so do the tab bar's ends.

**Tests.** `AppFlowTest` still tapped the removed History tab; it now taps
Library. `AcceptanceTest`'s search step still uses the old search row and
dialog rather than the on-screen keyboard. It needs rewriting against the
current Search screen before `./streaming/simulator.sh acceptance` can pass.

**Results in this environment** (Linux container, no emulator or device):
- **Builds:** `:app:assembleDebug`, `:app:assembleRelease` and
  `:app:assembleDebugAndroidTest` pass.
- **Lint:** `:app:lintDebug` and `:app:lintRelease` report 0 errors and 20
  warnings.
- **Unit tests:** `:app:testDebugUnitTest` passes 10 offline JVM tests.
- **Snapshots:** `UiSnapshotTest` with `-PuiSnapshots=true` renders 17
  screens and alerts in Robolectric's native graphics at 1920×1080. These are the
  renders the fixes above were checked against. Robolectric does not run
  RenderEffect, so live blur and the dialog's window blur are not shown.
- **Not run:** emulator instrumentation, smoke, soak and on-device frame
  timing. They need the verified AVD.

## Simpler screens, glass and torrent add-on checks, 2026-10-22

What changed, following the owner's review:
- **First run:** one screen and one button. Start watching installs the listed
  starter add-ons (Cinemeta, Internet Archive, OpenSubtitles v3) from their
  live manifests, then opens Home. That button is the viewer's acceptance.
  Local-only instrumentation lists none (`RuntimeOptions.starterAddons`).
- **Navigation:** the Add-ons tab is gone. Settings lists installed add-ons
  first, then the add-on store. Side panels (large icon and text) are removed.
  Lists and forms sit in a centred 620 dp column with plain rows and less text.
- **Details:** every button is labelled (Play/Resume with the episode,
  Sources, Favourite, Mark watched, More).
- **Sources:** only playable sources are listed, best first. When none can
  play, a count and the reason are shown instead.
- **Keyboard:** a gap-free six-column grid with letter, symbol and accent
  pages. The Search key is gone because results update as you type.
- **Focus:** list rows and tabs no longer scale, so their text no longer
  shifts sideways. Posters grow alone with a white rim and deep shadow, and
  their captions keep their size.
- **Glass and blur:** artwork gains a softened layer, blurred towards the
  bottom. Alerts sit over a blurred copy of the screen on every Android
  version. Content scrolling under the top edge fades out.

Problems found and fixed while checking the renders:
- **Alert blur never ran:** a dialog's context is a theme wrapper, not the
  activity, so the screen copy was skipped; the code now unwraps it.
- **Ranking cost:** `SourcePolicy.ranked` re-assessed each source on every
  sort comparison. It now assesses each source once, which matters for a
  hundred torrent results.

**Torrent add-ons.** `TorrentSourcesTest` (Robolectric, no network) sends a
Torrentio/Comet/Peerflix-shaped answer with made-up hashes through the app's
parser and source policy. The fake TV has an H.264 hardware decoder and no
HEVC decoder. The test checks that:
- the info hash, file index, file name and tracker hints are parsed;
- a result naming no trackers falls back to the public trackers;
- 1080p H.264 comes first, then 720p, then the result whose codec is unknown;
- the 4K Dolby Vision and HEVC results are hidden;
- 200 results rank in well under a second.

`./streaming/native/build-host.sh dev` passed all 30 engine tests over
loopback BitTorrent. They cover v1, v2 and hybrid streaming, magnet metadata,
seeks and rewinds into evicted data, bounded cache turnover, corrupt-peer
recovery, offsets beyond 4 GiB and concurrent readers.

**Tests.** The instrumentation now reaches the store through Settings → Add-on
store and types searches on the on-screen keyboard ("Type g", …).

**Results here** (Linux container, no emulator or device):
- **Builds:** `:app:assembleDebug`, `:app:assembleRelease` and
  `:app:assembleDebugAndroidTest` pass.
- **Unit tests:** `:app:testDebugUnitTest` passes 14 tests.
- **Lint:** `:app:lintDebug` and `:app:lintRelease` report 0 errors and 20
  warnings, after removing the icons the old rows and buttons used.
- **Snapshots:** `UiSnapshotTest -PuiSnapshots=true` renders 25 screens. It
  includes the real first run, whose live installs succeeded for Cinemeta,
  Internet Archive and OpenSubtitles v3.
- **Not run:** emulator instrumentation, smoke and soak. They need the
  verified AVD.

## Stream: no onboarding, split Settings, player, contrast and remote checks, 2026-10-22

**What changed:**
- **Name and onboarding:** the TV now shows the app as Stream: launcher
  label, new adaptive icon, and a banner at five densities from
  `branding/make_brand.py`. Onboarding is gone. An empty Home offers Get
  add-ons, which opens the store's starter page; its Install adds Cinemeta,
  Internet Archive and OpenSubtitles. Explicit acceptance still happens on a
  store page (AGENTS.md updated to say so).
- **Settings:** a split view, with categories on the left and the focused
  category's rows on the right. Row labels are unchanged; the instrumentation
  now opens the category first.
- **Search:** the results area has no heading or count, and its empty and
  no-results states are centred.
- **Corners:** one radius scale in `dimens.xml`: 8/10/16/24 dp, capsules
  and circles. Buttons and rows went from 12 to 10 dp, about tvOS's
  proportion; the unused tile-button style was removed.
- **Contrast:** tertiary text rose from 40% to 60% white (3.4:1 → 6.3:1 on
  the canvas). Facts over artwork use 86% white, text over artwork has a soft
  shadow, and the scrim is a little stronger where text sits.
- **Player:** restyled through app overrides of `playback_layers.xml` and
  `row_menu.xml`, with the same IDs and the view types the library reads.
  - The "Now playing" label is gone.
  - Audio & subtitles and Close are round buttons at the top right.
  - Rewind, a fixed-width Play/Pause and Forward sit between the times.
  - The centre glyph is a smaller glass disc.
  - The options menu's focused row is a white platter with dark text.
  - The casting receiver keeps the library's own layouts.

**Problems found and fixed:**
- **Up on Home:** the spotlight was clickable for touch, which also made it
  focusable, so Up from the first shelf moved focus onto it invisibly. It
  could never reach the tab bar. The spotlight no longer takes focus, and Up
  from the first control of any page goes to the tab bar.
- **Opening a Settings category:** a tap or OK on a category whose pane
  wasn't showing yet lost focus. The pane now takes focus once its rows are
  laid out.
- **Skeletons:** loading placeholders now take their page's final width, so
  nothing jumps sideways when the page arrives.

**Remote check.** `UiSnapshotTest` walks D-pad focus on 14 pages and fails if
a visible control can't be reached or works only by touch. Every page passes.
It also renders the player overlay, its error card and its options menu over
a real film still.

**Results here** (Linux container, no emulator or device):
- **Builds:** `:app:assembleDebug`, `:app:assembleRelease` and
  `:app:assembleDebugAndroidTest` pass.
- **Unit tests:** `:app:testDebugUnitTest` passes 14 tests, plus 2 render
  tests with `-PuiSnapshots=true`.
- **Lint:** both variants report 0 errors and 18 warnings (previously 20).
- **Not run:** instrumentation on the emulator, so the updated Settings and
  search flows in `AcceptanceTest` and `AppFlowTest` are unverified.
