# The Android app

Kotlin and Jetpack Compose, minimum API 26, compile/target API 36. Four modules:

- `:playback`: repository-owned Media3 UI/session library in `android/playback`.
- `:codecs`: bundled software decoder integration.
- `:torrent`: JNI engine and positional Media3 data source, built for
  armeabi-v7a, arm64-v8a and x86_64.
- `:app`: `tv.stream.app`, the standalone launcher, page navigation (Compose
  blocks in a RecyclerView page), playback host, add-ons and local data.

## Run the app

From the repository root: `./simulator.sh up`. The launcher starts or
verifies `Stream_Local_TV` / `emulator-5558`, builds the standalone app and
installs/opens it. No helper, extension, pairing, debrid account or home server
is needed. Development requires Java 21/17, Node, FFmpeg, Rust (rustup installs
the toolchain `native/core/rust-toolchain.toml` pins) and the Android SDK/NDK/CMake
versions pinned in `native/deps.lock`.

The launcher label is Stream; the application ID is `tv.stream.app`. It opens
straight on Home without onboarding. First launch installs the owner-approved
starter catalogues and sources once; a removed default is never installed again.
The store also offers a reviewed installation page for Cinemeta, Internet Archive
and OpenSubtitles. Home, Browse, Library and Settings sit in a centred vertical rail on the left; search
opens from the same rail, and the add-on store, add-on management and Imports
live under Settings. Details provide Play/Resume, Sources,
watched/favourite controls and seasons/episodes. Home also tells the viewer
about [new episodes](#new-episodes) of the series they follow. Settings manage add-ons,
catalogue visibility, languages, subtitle style (size, font, colour,
background, position), cache size (128/256/512 MiB),
autoplay, router port mapping (off by default) and app-cache cleanup. Versions, statistics and license texts live in
Developer views.

### New episodes

`NewEpisodes` announces episodes of followed series, then gets out of the way:

- **Followed:** a series in the library that the viewer finished an episode of
  in the last two years, or a favourite (even before its first episode). More
  → Turn off new episode alerts stops it, until turned back on.
- **New:** the series' next episode (after the furthest one finished, specials
  aside) aired since the viewer finished the one before it and hasn't been
  started. A viewer who is behind isn't told: they already know the later
  episodes exist. Episodes released together with the last one watched (a whole
  season at once) don't count; a date-only release on the day the viewer
  finished still does. Undated episodes are never "new".
- **Where:** a New Episodes row leads Home ("New season", "3 new episodes" or
  "New episode"), and its card opens the series on that episode, so Play starts
  it. The spotlight leads with the same words, and the series' posters
  (Favourites, Library) and the new episode cards carry a small NEW flag.
  Details' Play also defaults to the next episode rather than the first.
- **Not forever:** a notice ends when the episode is played (30 seconds or
  more), on More → Hide new episode, after Home has shown it on seven different
  days, or a month after it first appeared. It never comes back for that
  episode, so an ignored notice quietens the series until the viewer watches it
  again; once caught up, the next episode is new again.
- **Fresh lists:** in the background, at most twice a day per series and 24
  series per run (most recently watched first), the app fetches each followed
  series' metadata and saves it over the library's copy without reordering
  Recently played. Opening a series' Details does the same. Checks rank below
  every other provider request, Play cancels them, and a provider that fails is
  asked again next time. Test databases and local-only instrumentation never run
  them; `NewEpisodesTest` drives them against a loopback add-on.

The state (per series: last check, alerts off, the current notice's first day
and days shown) is one encrypted preference and forgets series that leave the
library.

### Error QR reports

Turn on **Settings → About → Developer mode** before reproducing a problem.
Playback errors then show a QR panel beside Retry/Sources. Back or Left exits playback failures. Browse and
settings failures offer **Error report**. **Settings → Developer → Last error
report** reopens the most recent report while this app process is alive.
Turning Developer mode off clears it and hides report controls.

Scan with a phone QR reader that supports plain text. The code contains a frozen
JSON report, not a web link: no server or upload is needed. Longer reports use
**Previous QR / Next QR**; scan each numbered part and join their payloads in
order (remove the two-line part headers). They share one report ID and UTC time.
Reports include app/device versions, the failed operation, original exception
types/messages and codes, up to eight stack frames per cause (omissions are
marked), source attempts, playback position/format and available torrent counters.
Addresses, credentials and private paths are redacted before encoding. Reports
are held only in memory; stream addresses are never saved for this feature.

Focused verification, without emulator or public providers:

```sh
cd android
./gradlew :app:testDebugUnitTest --tests tv.stream.app.DiagnosticReportTest --tests tv.stream.app.ErrorQrPanelTest
```

The tests decode every QR part, reconstruct Unicode reports, check redaction
and the Developer-mode gate, and render the TV error surface. Snapshots are
written to the ignored `android/app/build/error-report-snapshots/` directory.

## Add-ons

`AddonRepository` is the entry point for manifest preview/accepted installation,
catalogue rows, filtered/paginated catalogue queries, search, metadata, streams
and subtitles. It implements the supported HTTP resources in the
[Stremio protocol](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md).
No add-on JavaScript runs in the application. Constructing the repository
installs nothing; a new install's defaults come from `StarterAddons` (see First
run above).

### Store

The add-on store (`StoreScreens`, Settings → Add-on store) lists, by section: built-in add-ons, a curated
Torrent/P2P section (Torrentio, Comet, Meteor, MediaFusion, StremThru Torz,
Peerflix and TorrentsDB), the lists
installed add-ons publish through the protocol's `addon_catalog` resource, and
the default directory's Official and Community lists. The default directory is
Cinemeta's `addon_catalog` (Stremio's public lists); `AddonRepository.store()`
fetches it even when Cinemeta isn't installed, caches it like catalogues and
falls back to the saved lists, marked stale. Debug builds under local-only
instrumentation use only the fixture directory named in the instrumentation
preferences, never Stremio's. Listings skip other transports, legacy
non-manifest addresses, add-ons on loopback/private hosts (unless the list comes
from that host), adult add-ons and unreadable manifests; an add-on appears once,
at its shortest address, so a configured copy embedding someone's settings is
never offered in place of the plain add-on. Filters: All, Sources, Catalogues,
Subtitles, plus Torrent/P2P for torrent-capable source providers. The curated
section needs no directory response and remains listed when remote lists fail;
local-only/fixture directories exclude it. See [torrent add-on listings](addons.md)
for endpoints, setup requirements and the extent of provider verification.
Filter changes reuse the loaded lists for up to a minute. Refresh add-on lists
checks again immediately; installed add-on changes also invalidate this UI cache.

Choosing a listing opens a review built from the add-on's own current manifest:
what it supplies, its catalogues, its host, what it receives and whether its
sources are peer-to-peer. Install happens only from that review (or the
existing address dialog). Add-ons that need configuration show their
setup address and accept the configured manifest address; the store knows
providers with separate setup hosts (such as Peerflix). Open setup page launches
a browser when available or displays the address for a phone/computer. Optional
setup is also available for installed add-ons. `stremio://`
install links are accepted and fetched as `https://`. Anything else
Stremio-compatible installs from its address.

### Built-in add-ons

`BuiltinAddons` answers `stream://name/manifest.json` addresses inside the app,
using the same protocol, review, installation, ordering and removal as remote
add-ons; `AddonClient` routes them before taking a request permit, so their own
HTTPS requests can't deadlock the pool. Internet Archive (available to install from the store)
offers a "Public Domain Films" catalogue from the Archive's Feature Films
collection and streams for `tt` IDs. Items come from the collection's own IMDb
links and from Wikidata (IMDb ID P345 → Internet Archive ID P724). Withdrawn
items are skipped; items are ranked by running time, so a film split into
parts, or trailers and extras, rank below a complete copy; masters above
8 Mbit/s are dropped when a lighter copy exists. Files play directly over HTTPS
from archive.org. Wikidata needs an identifying User-Agent sent before
`Accept`/`Accept-Encoding` and briefly refuses bursts; the lookup retries once.

### Torrent sources from public add-ons

Many add-ons send only an info hash, with no `sources`. Outside local-only
tests, playback then announces to `MediaSource.PUBLIC_TRACKERS` as well as DHT
(`TorrentEngine.Source.withFallbackTrackers`); hinted trackers are used alone.
Metadata from a public swarm may take up to 60 seconds ("Finding peers").
Startup as a whole has a 60-second budget, pauses excluded, before the player
offers Retry or another source; when another candidate exists, a source with no
peers, no metadata or no frame is abandoned sooner (8, 12 and 25 seconds). See
[playback preparation and recovery](playback.md).

Call blocking APIs from an application worker, never the UI or the repository's
internal request pool. Create a `Cancellation` with `newRequest()` for each
navigation/search generation and close it when obsolete. Configuration mutations
cancel existing scopes. The UI checks the scope/generation again before showing
worker results. Close the repository when its owning application scope ends.

The repository uses four request workers and a bounded queue; transport also
limits sockets to four. Requests get ten seconds including redirects, and
discovery gets fifteen seconds including queued providers. Home/Search catalogue
loads allow fifteen seconds per four admitted resources in the aggregate; each
catalogue's request budget starts when its worker runs, so queued shelves do not
time out before they reach their provider. Results retain
provider errors alongside valid responses in installation order. Catalogue/meta
requests always try the network, then may return cached metadata marked stale.
Stream/subtitle addresses are resolved afresh and never stored as resumable
playable URLs.

Configured URL paths/escapes and query parameters remain intact. Resource IDs
and extras are encoded individually. Titles use provider-scoped identities;
only an explicit common IMDb identifier permits search deduplication. Episodes
retain their exact video IDs and need no pre-existing playable URL.

`MediaSource` separates HTTP, magnet, info-hash and unsupported candidates.
Invalid explicit indexes remain unsupported; file zero stays zero. Source
headers belong only to that HTTP candidate, and are never applied to add-on or
subtitle discovery. `notWebReady` does not make an Android source unsupported.
`torrentSource()` maps an explicitly chosen torrent and its supplied trackers
to the JNI input without starting a session. Discovery DHT hints remain
available on the model; native DHT is a session setting. `SourcePolicy` and
`PlaybackViewModel` handle ranking and source selection; `WatchActivity` and its
`PlaybackCoordinator` own the active Media3/native session.

## Screen state and Compose

The Kotlin protocol and playback models expose immutable values and defensive
collection snapshots. `MediaSource.Location` distinguishes supported transports
and rejected sources; `PlaybackRequest` accepts named Kotlin arguments while
keeping its builder for existing callers. Torrent JNI fields and exports retain
their required JVM names.

`NavigationViewModel` owns the back stack and remembered tab roots, publishing
navigation identities through `StateFlow`. Focus, category and scroll restoration
stay on each route. Bundle state contains navigation identity and metadata, never
resolved playable addresses. Recreation detaches routes from outgoing workers and
drops activity-bound page callbacks.

`BrowseViewModel` publishes sealed loading/content/failure states for Home,
Search, Browse, Details and management pages. Blocking builds use application
workers. A navigation or search cancels all work in its generation and closes its
provider scope; late progress, results and errors cannot update a newer screen.
Published pages copy nested render values so workers cannot mutate a visible
partial page. `MainActivity` renders states and owns the current view tree;
`BrowseDialogs` presents its native popups.

`PlaybackViewModel` owns source discovery, selection/retry snapshots and sealed
loading/ready/failure presentation. Each attached activity has its own cancellable
work owner. Native create/select/close stays ordered on the application's
`playbackLoader`; outgoing session cleanup cannot close the next activity's
session. Pause, speed and position still use lifecycle saves and the encrypted
progress store.

Every page block is drawn by Compose inside the page's RecyclerView, which keeps
page identity and vertical scrolling. `ComposePageBlock` hosts each block and
republishes its model on rebinding; `PageAdapter` only publishes blocks and
retains route scroll and focus. Each block's composables own its layout:

- **Text and controls:** headings, text and messages (`TextBlocks`); Settings and
  add-on rows and the Settings split view (`PageRows`); actions, chips,
  pagination and notices (`PageControls`). Rows keep the platter look, fixed
  growth, sounds, exact accessibility labels, click by remote (confirm keys press
  on down and click on up), pointer and touch, focus after a touch, and popups
  that grow from the row's platter (`Tv.Region`). The split view selects a
  category on focus, enters its pane on Right/OK/tap, returns to the lit category
  on Left, restores a pane row by key and keeps each column's scroll.
- **Titles and artwork:** Details, the series header, intro and page titles
  (`TitleBlocks`); the Home spotlight, shelves, grids and the episode browser with
  its seasons (`TileBlocks`); skeletons and loading (`SkeletonBlocks`). Shelves
  keep their scroll in the route's memory. Search restores results when returning
  from a title; a newly typed query starts at the top. Editing the opening query
  also clears its restoration target, so cached results cannot revive an old position.
  Up and Down move between blocks in page order: Down from the actions reaches
  the seasons. `TitleScreens.seasonOrder` puts Specials (season 0) after the
  last numbered season and `defaultSeason` opens on the selected episode's
  season, else the first numbered one; without progress Play starts the first
  numbered episode (`NewEpisodes.order`), never a special listed first.
  Icon-button captions (`PageControls.Caption`) are placed from the focused
  button's coordinates. `PageControls.Icon` plays the `avd_tv_*` morph when the
  same icon slot flips between a toggle's two glyphs (favourite, watched), on the
  title page and anywhere else that glyph pair is shown through it.
- **Search** (`SearchBlocks`): keyboard, query, results grid and the reflow
  between the split and full grid. Provider answers replace values in the live
  composition; the keyboard page and the last result survive Back.
- **Focus by key:** `PageFocus` finds, focuses and reports page controls by key.
  Controls register a `FocusRequester` (and their popup region) with their block;
  the focused key is mirrored onto the composition owner's tag, so route focus,
  `currentFocus.tag` and instrumentation keep working. Controls expose their key
  as an accessibility resource name for tests. A control draws focus from its
  focus target's own state (`onFocusChanged`), not from collected focus
  interactions, which miss a control focused in the frame it is composed (a
  page's first control on opening, a card a shelf brings into view).
- **Rows of controls:** `PageAdapter.Layout` moves Up/Down to the nearest block
  with controls by adapter position (`PageAdapter.controlRows`), laying it out
  first if it scrolled away, and enters it in the same column; only Up from the
  first such block reaches Back or Refresh. No extra rows are kept laid out off
  screen (their Compose labels would read as visible to accessibility); a row
  that is not attached is scrolled into layout when focus moves to it, and one
  whose controls are still composing is entered on the next frame rather than
  skipped. Geometric search from the window used to pick the top corner over a
  recycled row above.
- **Every page opens focused:** while a page loads, focus waits on the page list
  itself. When the page arrives, focus moves to its restored or initial control.
  This includes a page updated in place, such as Search, whose keyboard is
  already built while it loads. If focus is still on the bare list or nowhere
  once the page has settled, the first control takes it (`claimContentFocus`).
  A remote move into a partial page (a first launch's Home, whose slow starter
  providers keep it loading) ends that wait: the control the viewer reached keeps
  focus when the page arrives, and an update never scrolls a browsing viewer back
  to the top. An arrow that leaves focus on the bare list, after the control
  under it was replaced, hands focus to the remembered or first control.
  An empty Library offers Browse, so it has a control too; a page with no
  controls at all keeps focus on the list, so Right can still collapse the rail.
- **Platform views kept:** the Settings subtitle preview hosts the player's
  subtitle renderer through `AndroidView`; Media3 keeps its video surface. The
  player's toolbar, timeline and loading/error/up-next layers are composables
  (`ComposePlayerControls`, `ComposePlayerLayers`) bound to the shared
  `ControlsOverlay`/`PlayerScreen` state, which keeps transport, scrubbing and
  inactivity timers. The timeline keeps its 48 dp touch target and dragging.
  The loading layer shows a series episode's `S2 E7 · Name` line under the
  title (`loading_episode`); the Next episode shortcut shows only with the
  controls and only for a released next episode (see [playback](playback.md)).

New Compose work must preserve those interaction contracts rather than introduce
new styling.

## Local data

`TvStore` uses SQLiteOpenHelper migrations (currently version 2) for configured
add-ons, catalogue visibility, library/favourites, per-video progress/history,
preferences, imported descriptors and metadata cache. Call it from workers.
Continue Watching starts at 30 seconds and excludes watched/completed videos;
natural completion and explicit watched/unwatched actions are distinct. The
player's Mark watched button preserves the saved position and removes that
video from Continue Watching. Leaving an unwatched video in its final 10%
(capped at three minutes, after at least 30 seconds of progress) offers Keep
for later, Mark watched or Continue watching. Keep for later is the default;
remaining time never automatically marks a video watched. Live streams and
unknown durations do not offer this choice. The playback host saves progress
periodically and on lifecycle, seek and episode transitions.

SQLite URL/manifest, metadata, import and preference values are AES-GCM encrypted
with an Android Keystore key. Row identities are authenticated as additional
data. No plaintext fallback exists on key loss. Diagnostic URL representations
omit configured paths, queries, fragments and userinfo. Android backup and
device transfer are disabled. The metadata cache holds at most 256 entries and
8 MiB of encrypted content; clearing it preserves library/history.
The shared player's app-private compatibility preferences hold presentation and
language choices only; credential-bearing addresses never enter that store.

`ImportRepository` stores magnets/URLs as encrypted descriptors. A selected
`.torrent` is copied to app-private no-backup storage, capped at 32 MiB and
encrypted, so reopening does not depend on an expiring file permission. Private
UUID filenames prevent traversal. Each database has its own directory so test
cleanup cannot remove the user's descriptors. Crash recovery removes only
interrupted/orphaned descriptors in that directory. Native payload remains
separate and is never an import descriptor or library asset.

## Verification

See [testing](testing.md) for what each suite proves and the focused
commands. The routine gates, from the repository root:

```sh
(cd android && ./gradlew :app:testDebugUnitTest)  # JVM: protocol, store, new episodes; no device or network
./simulator.sh test       # engine, data and core app tests on the verified AVD
./simulator.sh smoke      # one catalogue → torrent → player flow
```

## Device target and budgets

The intended TV is a first-generation Fire TV Stick HD: 1 GB RAM, a 32-bit ABI,
Fire OS 7 (Android 9), H.264/HEVC/VP9 up to 1080p. Development and every test
run on the `Stream_Local_TV` emulator (Android TV 16, 4 GB, ARM64), so
results describe the emulator, not the stick. Defaults:

| Resource | Default |
| --- | --- |
| Active payload torrents | One |
| Payload disk cache | 256 MiB (128/256/512 in Settings) |
| Free-storage reserve | 512 MiB |
| Media3 compressed-data target | 24 MiB |
| Decoded artwork memory / disk cache | 8 / 40 MiB |
| Provider requests | 4 concurrent, 64 queued |
| Metadata cache | 256 entries, 8 MiB |

The total-app memory target for the stick is 250 MiB; it is a target to
measure (`./simulator.sh profile`), not a guarantee.

## Build variants and branding

`android/branding/make_brand.py` regenerates the launcher icon and the TV banner from
one geometry: a graphite field and a frosted-glass play mark. The icon is an
adaptive vector with a monochrome layer. Its field is a rounded square (22% corners)
over the 72 units launchers show, transparent around it: circular masks fall inside
it, and Fire OS's almost square mask shows those corners instead of the field's raw edges; the separate 16:9 banner is PNGs at five
densities with the wordmark in Geist Sans SemiBold. The application declares
the adaptive icon and TV banner separately; the launcher activity inherits them.
Do not wrap the banner in a padded square icon: that makes the artwork smaller.
`android/branding/make_fonts.sh` restores the bundled Geist Sans faces.

For a separate installation alongside Stream, build with
`./gradlew :app:assembleDebug -PseparateTvApp=true` from `android`.
This uses package `tv.stream.app.tvapp` and the launcher label **Stream TV**;
it has its own data and does not replace `tv.stream.app`.
Fire TV's choice of artwork can involve Appstore assets as well as APK resources.
Compare a working app on the same device before attributing a tile problem to
sideloading alone.

Video-track completion is checked on Media3's playback thread. Audio and subtitle
tracks may continue after the last video frame without triggering the frame watchdog;
seeking back into video re-arms stall detection. Generated fixtures cover both tails
with platform and FFmpeg video renderers.

## Known gaps

- **Endurance:** two thirty-minute Rust-engine emulator soaks passed. An earlier
  libtorrent-era run timed out at seek 159; the most likely mechanism (an
  eviction report that never arrived) is fixed in the current engine, but the
  run left no record to prove it. See [the soak investigation](soak-investigation.md).
- **Formats:** VC-1 has decoder-discovery coverage only. Small 10-bit fixtures
  prove decoding and seeking, not full-resolution throughput on a TV; HDR
  output, Dolby Vision, unsupported extractors, damaged files and DRM can fail.
- **Devices and providers:** nothing has run on the physical stick. Public
  provider latency, expiring links and swarm health are outside the fixtures.
- **Series loading:** Details still parses the provider's whole episode list,
  though it builds season card models on demand.
- **Metadata:** reopening Details fetches its metadata again after the 60-second
  preparation window; showing the cache first and revalidating would be faster.
  `Title` keeps no network/studio or person data, so the app can't show the
  original service (Netflix, Apple TV+) or person pages; add-ons such as
  AIOMetadata and the TMDB add-on publish those fields.
- **New episodes** rely on providers' `released` dates; a provider without dates
  never announces anything.
