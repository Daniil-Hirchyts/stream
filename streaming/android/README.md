# Daniil TV Android

Java/XML, minimum API 26, compile/target API 36. Three modules:

- `:playback`: shared Media3 UI/session library from `android/playback`.
- `:torrent`: JNI engine and positional Media3 data source, built for
  armeabi-v7a, arm64-v8a and x86_64.
- `:app`: `tv.daniil.stream`, the standalone launcher, RecyclerView navigation,
  playback host, add-ons and local data. Installs separately alongside Daniil Cast.

## Run the app

From the repository root: `./streaming/simulator.sh up`. The launcher starts or
verifies `DaniilCast_Local_TV` / `emulator-5556`, builds the standalone app and
installs/opens it. No helper, extension, pairing, debrid account or home server
is needed. Development requires Java 21/17, Node, FFmpeg, a C++ toolchain and
Android SDK/NDK/CMake versions pinned in `streaming/native/deps.lock`.

The app is called Stream on the TV (launcher label, icon and banner); the project
and package keep the Daniil TV name. It opens straight on Home, with no
onboarding. While Home has nothing to show, it offers the store's starter page:
Cinemeta, Internet Archive and OpenSubtitles, installed together by its Install
button. Home, Library and Settings are tabs in a floating bar at the top; search
opens from the same bar, and the add-on store, add-on management and Imports
live under Settings. Details provide Play/Resume, Sources,
watched/favourite controls and seasons/episodes. Settings manage add-ons,
catalogue visibility, languages, subtitle style (size, font, colour,
background, position), cache size (128/256/512 MiB),
autoplay and app-cache cleanup. Versions, statistics and license texts live in
Developer views.

### Design

The browse UI follows tvOS. Artwork fills the screen behind a dark scrim. On
Home, a spotlight shows the focused card's logo or title, facts and
description, and the backdrop cross-fades to its art once focus settles.
Shelves scroll horizontally. Page titles scroll with their page.


- **First run:** no onboarding and no setup. On its first start the app
  installs the starter add-ons (Cinemeta, Internet Archive, OpenSubtitles)
  and the torrent sources Meteor, MediaFusion, Peerflix and TorrentsDB (their
  public, unconfigured addresses) in the background from their current
  manifests, and refreshes Home when they arrive. An add-on already installed
  with the viewer's own configuration is not added again. Each default is installed once (remembered in the encrypted store),
  so one the viewer removes stays removed; one that can't be reached is tried
  again on the next start. If Home is still empty it offers Get add-ons, the
  store's starter page. Test databases and local-only instrumentation install
  no defaults, so tests never reach public providers.
- **Tab bar:** Search, Home, Library and Settings sit in one floating pill. It
  slides away once content scrolls under it and returns when focus reaches it
  or the page is back at its top. Content scrolling up fades into the top edge.
- **Clock:** a small clock sits at the top right of the browse screens and,
  with the overlay, beside the player's title. Settings → Clock sets its
  format (Automatic, 24-hour, 12-hour or Off) and whether the player shows it.
- **Settings:** a split view. Seven categories sit on the left: Add-ons,
  Torrents, Languages, Subtitles, Playback, Clock and About.
  - The focused category's rows show on the right and change as focus moves.
  - Right enters the pane at its first row, and Left returns to the lit
    category. The route remembers which category was open.
  - Other lists and forms (Imports, the store, add-on pages, Sources) sit in a
    centred column; shelves and grids use the full width.
- **Details:** every button is labelled: Play or Resume (with the episode),
  Sources, Favourite, Mark watched, and More for refresh and removal.
- **Sources:** only sources this TV can play. Sort chips order them by Best,
  Quality, Size, Seeds or Played; choosing the lit chip again reverses it
  (the arrow shows which way), and sources that don't advertise a value stay
  at the end either way. Each row shows provider, kind, quality, size and seeds
  read from the add-on's text, and marks sources that played (the most recent
  as "Last played") or didn't play on this TV. The marks hold only hashed source
  keys, never addresses. When none can play, the page says how many were found
  and why.
- **Browse:** plain blocks with each catalogue's name inside, no artwork and
  nothing beneath, grouped Films, Series, then other kinds. A block names its
  add-on only when several add-ons supply catalogues, and says when it needs a
  filter first. A catalogue's listed filters (genre, year, …) are rows of chips
  on its page rather than a long list in a dialog; free-text filters ask in one.
- **Scrolling:** moving back up a long page returns it to its very top (Home's
  spotlight shows again), using each block's last measured height once the top
  blocks have been recycled. A block that fills the page (Search, Settings)
  scrolls inside itself and never moves the page.
- **Search:** typing replaces only the results beside the keyboard, so the
  focused key never moves. Results fade out at the column's top and bottom.
- **Alerts:** choices are filled platters; Cancel or Close sits apart under a
  rule, outlined and grey until focused.
- **Search keyboard:** a six-column grid with no gaps, on three pages
  (letters, digits and punctuation, accented letters); Space, Delete, the page
  switches and Clear each span exactly two keys. Results update as you type,
  with no heading or count. Before a search, and when nothing matches, a quiet
  line sits centred in the results area.
- **Corners:** one scale in `res/values/dimens.xml`, on this 960 dp canvas
  (half of tvOS points):
  - 10 dp for buttons, rows, fields and artwork; 8 dp for keys.
  - 16 dp for cards and 24 dp for sheets.
  - Capsules for floating and filter controls (tab bar, tabs, chips, search
    field); circles for icon buttons.
- **Type:** Inter, the open typeface closest to Apple's SF, bundled and
  subset to Latin, Greek and Cyrillic by `branding/make_fonts.sh` (other
  scripts fall back to the system font). Regular for text, Medium for
  headings and controls, SemiBold for emphasis, and the Display cut for
  titles from 24 sp. The player's type scale (`Text.*` styles) is overridden
  by name to use it, and its times use tabular figures.
- **Loading:** each screen's skeleton is built from the blocks of its page:
  title, rows, two-line rows, buttons, chips, shelves, grids, the details
  header and the Settings split view, at the same sizes and spacing. Each
  line of text is a bar inside an empty text view of that line's size, so the
  page replaces it without anything moving. Search shows the results grid's
  own columns. The activity indicator is an eight-spoke wheel after Apple's,
  an animated vector turning at a constant speed on the render thread, so it
  keeps moving while the main thread is busy.
- **Sounds:** made, not sampled, by `streaming/sounds/sounds.py` with Rauta's
  method (gym-lib): each moment is a short score rendered by a small synth and
  mastered to one A-weighted loudness scale, then chosen by ear. The app plays
  Glass, made dark and plain like a TV's own interface: low, damped single
  tones with a felt tap and no melodies; a small slide up or down says which
  way something went, and a completion is the same tone tapped twice.
  Navigation has quiet ticks: focus moving (in the player's controls and menu
  too), a row's end, typing; tabs, pages, chips, dialogs and the player's
  pause, play, skips and menu have small sounds; favourite, watched, an add-on
  installed, a failure, up next and a title's end have fuller ones. Ticks
  follow only the viewer's arrows and touches, never focus a page restores, and
  lean up to a semitone with their place across the screen. `ui.Sounds` plays
  them as sonification (never taking audio focus) with one voice: a new sound
  cuts a smaller or equal one, is dropped under a larger one, a held arrow ticks
  at most every 45 ms and quick presses are spaced out; OK's select gives way
  to a more particular sound in the same press. Off with Settings → Playback →
  Interface sounds, or the TV's own System sounds switch.
  `python3 streaming/sounds/sounds.py audition` writes a listening page with
  the app's set beside Bright glass, Pure and Marimba.
- **Text:** short. Player steps name the step and the source; errors say what
  happened in one sentence and leave the next step to their buttons.
- **Contrast:** small text is at least 4.5:1 on the canvas; tertiary grey is
  60% white. Facts over artwork use a brighter grey, and all text that can sit
  over artwork carries a soft shadow.
- **Remote:** every control is reachable with the D-pad. Up from the first
  row of any page reaches the tab bar, and the Home spotlight is a touch-only
  shortcut that never takes focus.
- **Focus:** the focused control turns white with dark text. Artwork grows,
  gains a white rim and a deep shadow while its caption keeps its size; buttons
  grow slightly; list rows and tabs don't grow, so their text never shifts.
  Left and right never leave the content, and shelves hold focus at their ends.
- **Alerts:** `Tv.Alert` sheets of translucent glass over a blurred copy of
  the screen, with a centred title and stacked full-width buttons. A long
  choice list or message scrolls, clipped, above the buttons.

Glass comes from cheap blurs:
- **Artwork:** each title's artwork also has a softened layer, blurred and
  fading in towards the bottom where text, buttons and shelves sit, so
  translucent controls over it read as glass. Sources shows the artwork fully
  blurred.
- **Alerts:** an alert copies the screen at an eighth of its size, blurs that
  once on a worker and fades it in.
- **Live, on Android 12+ only:** the tab bar and search field, and the
  buttons, chips and notices on a page, blur what is behind them
  (`GlassView`: a small RenderEffect GPU pass per surface while the picture
  behind it changes, none while the screen is still). Earlier versions,
  including Fire TV's Android 9, use a static material for the bar and plain
  platters for buttons, because BlurView there blurs on the CPU before every
  frame, which is what made the UI stall. Rows, tiles and keys never blur:
  they are many, and rows and keys sit on the plain canvas.
- **Player:** the video plays on a SurfaceView, which the system composites
  outside the view tree, so nothing in the app can blur it. The options sheet
  and the error alert are denser glass over the darkened frame instead.

Artwork is cached on disk (40 MiB, least recently shown evicted) as well as in
memory. Off-screen shelves keep their views, so scrolling back neither rebuilds
a row nor reloads its art.

The shared player is restyled only through app resource overrides, so Daniil
Cast keeps its own look:
- **Palette:** `res/values/colors.xml`, `bg_panel`, `pill` and `bg_card_soft`.
- **Overlay:** `layout/playback_layers.xml`, with the same IDs and the view
  types the library reads:
  - The title and track details sit at the top, with the clock and two round
    buttons beside them: Audio and subtitles (the subtitle list, then Audio)
    and Player settings (quality, speed, subtitle style). Menu on the remote
    still opens everything. There is no "Now playing" label.
  - A skip shows its total (+30 s) as plain text between the times, in the
    times' own row, so nothing moves. The player's buttons grow while focused
    or under the pointer; the overlay doesn't clip them.
  - Subtitles are chosen in the player. With subtitles off, nothing downloads
    before the video starts; the add-ons' subtitles are then fetched. The
    Subtitles list always shows Off, the loaded tracks and every add-on file
    not yet loaded (marked Download), so other choices stay in reach while a
    track shows. A download reloads the video where it was, keeps the loaded
    tracks, shows the new one and remembers its language.
  - The timeline sits low, with the elapsed and remaining times under its
    ends. There are no transport buttons: OK plays and pauses, left and right
    skip, Menu opens the options; a pointer clicks the centre glyph or drags
    the timeline. The library binds Rewind, Play and Forward, so they stay in
    the layout, hidden.
  - The centre play/pause glyph is a small glass disc.
  - Errors are a centred glass alert like the app's, with stacked full-width
    buttons. It opens with its first button focused, and OK presses the
    focused one (`WatchActivity.onKey`); the library alone would retry.
- **Options menu:** a floating glass sheet (`layout/playback_menu.xml`,
  `bg_panel`) without Back and Close buttons: Back and Menu close it, and a
  pointer uses the header's chevron or clicks outside. Rows use
  `layout/row_menu.xml`, `bg_option_row`, `option_label` and `menu_icon`; the
  focused row turns white with dark text, as everywhere else.
- **Subtitle font & style:** Standard, Bold, Condensed, Serif, Monospace,
  Casual, Cursive or Small caps, with expanded colors (White, Yellow, Cyan,
  Green, Magenta, Blue, Amber, Red), from the shared player's subtitle style
  (system families every Android TV has), with a live preview card in
  Settings → Subtitles and choice dialogs, plus the player's Subtitle style menu.
  Daniil Cast's receiver offers the same choice on the TV; the Chrome extension
  doesn't send fonts.

Screens are built off the main thread as `ui.Page` lists of `ui.Item` blocks
(`BrowseScreens`, `TitleScreens`, `SetupScreens`), and `ui.PageAdapter` turns
them into views. Every focusable view is tagged with a stable key. That lets
Back restore focus and lets the instrumentation find controls by label.
`MainActivity` only owns the back stack, chrome, dialogs and worker
generations.

Catalogue add-ons such as Cinemeta describe titles but supply no video.
Details and Sources say so ("No source add-on installed") and open the store on
its Sources filter. The player names the actual cause when nothing can play: no
source add-on, failing providers (by name and reason), only unsupported
results, or no results. Nothing is installed without the user's review.

`WatchActivity` re-resolves sources and uses the shared player. `SourcePolicy`
checks advertised hints and actual Media3 formats against the 1080p profile.
Automatic failover is limited to three candidates before first frame; explicit
choices are preserved. Ambiguous multi-file torrents require a file choice.
Leaving/backgrounding playback saves progress and releases Media3, native
sessions and payload files. Restart reconstructs playback from descriptors;
startup recovers disposable payload/subtitle files left by a killed process.
When the cache and the 512 MiB free-space reserve don't fit, the player offers
two fixes and retries the same source: clearing this app's own payload,
subtitle and metadata caches, or switching to a 128 MiB cache.

File imports accept Android `content://` grants through Open/Share as well as
`ACTION_OPEN_DOCUMENT`. This TV image has no system document picker; in that
case the app explains how to open/share a `.torrent` from a TV file manager.
It never requests broad storage access. Instrumentation supplies actual
generated metainfo through a separate test-APK content provider, then exercises
the production private-copy and native playback paths.

## Add-ons

`AddonRepository` is the entry point for manifest preview/accepted installation,
catalogue rows, filtered/paginated catalogue queries, search, metadata, streams
and subtitles. It implements the supported HTTP resources in the
[Stremio protocol](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md).
No add-on JavaScript runs in the application. Cinemeta's address is an onboarding
offer constant; constructing the store/repository installs nothing.

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
local-only/fixture directories exclude it. See [ADDON_STORE.md](ADDON_STORE.md)
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

`BuiltinAddons` answers `daniiltv://name/manifest.json` addresses inside the app,
using the same protocol, review, installation, ordering and removal as remote
add-ons; `AddonClient` routes them before taking a request permit, so their own
HTTPS requests can't deadlock the pool. Internet Archive (opt-in like any other)
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
Metadata from a public swarm may take up to 60 seconds ("Finding peers");
after loading, a torrent has a minute to show its first frame before the
player offers Retry or another source.

Call blocking APIs from an application worker, never the UI or the repository's
internal request pool. Create a `Cancellation` with `newRequest()` for each
navigation/search generation and close it when obsolete. Configuration mutations
cancel existing scopes. The UI checks the scope/generation again before showing
worker results. Close the repository when its owning application scope ends.

The repository uses four request workers and a bounded queue; transport also
limits sockets to four. Requests get ten seconds including redirects, and
discovery gets fifteen seconds including queued providers. Results retain
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
`WatchActivity` handle ranking, file selection and native session ownership.

## Local data

`TvStore` uses SQLiteOpenHelper migrations (currently version 2) for configured
add-ons, catalogue visibility, library/favourites, per-video progress/history,
preferences, imported descriptors and metadata cache. Call it from workers.
Continue Watching starts at 30 seconds and excludes watched/completed videos;
natural completion and explicit watched/unwatched actions are distinct. The
playback host saves progress periodically and on lifecycle, seek and episode
transitions.

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

```sh
./streaming/simulator.sh engine-test   # 10 JNI/Media3 tests, real BitTorrent
./streaming/simulator.sh data-test     # add-ons, encryption, persistence, imports
./streaming/simulator.sh test          # engine, data and 10 core app tests on the verified AVD
./streaming/simulator.sh smoke         # one visible catalogue → torrent/seek/cache/favourite flow
./streaming/simulator.sh soak          # 30 real minutes of playback/seek cycles
./streaming/simulator.sh site          # real screen and bounded input, port 58133
./streaming/simulator.sh profile       # dumpsys memory/graphics and screenshot
./streaming/simulator.sh acceptance    # extended UI, HTTP formats, tracks and failover
./streaming/simulator.sh process-death # real process termination and resumed playback
./streaming/simulator.sh release-test  # shrunk release JNI probe; restores debug app/test APKs
./streaming/simulator.sh live-film     # optional: real public-domain films through the store (internet)
./streaming/simulator.sh live-addons   # optional: current store + earlier Nuvio list, manifests/resources/reviews
./streaming/simulator.sh live-torrents # optional: public-domain torrent playback samples
./streaming/simulator.sh live-torrents Torrentio,TorrentClaw,Pipe # optional subset; names without spaces
cd streaming/android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:testDebugUnitTest :app:lintDebug :app:lintRelease
./gradlew :app:testDebugUnitTest -PliveAddons=true  # optional: real films via live add-ons, archive.org and FFmpeg
./gradlew :app:testDebugUnitTest --tests '*UiSnapshotTest' -PuiSnapshots=true  # optional: screen renders (internet)
```

`UiSnapshotTest` renders the real screens at 1920×1080 with Robolectric into
`app/build/ui-snapshots/` for design review. It uses live Cinemeta and
Internet Archive data.
- **Screens:** the empty Home and the starter install, Home, details,
  Sources, Library, the store, Settings with an alert, Search, Imports, and
  the player overlay, error alert and options menu.
- **Skeletons and branding:** every screen's loading skeleton (`sk-*`), to
  compare with its page, and the launcher icon and banner as Android draws
  them (`00-*`).
- **Remote check:** on each page it walks D-pad focus from the focused
  control and fails if a visible control is unreachable or touch-only.
- **Limits:** it doesn't replace instrumentation on the emulator, and it
  can't show live blur.

`branding/make_brand.py` regenerates the launcher icon and the TV banner from
one geometry: a graphite field and a frosted-glass play mark. The icon is an
adaptive vector with a monochrome layer; the banner is PNGs at five densities
with the wordmark in Inter Display SemiBold. `branding/make_fonts.sh`
regenerates the bundled Inter subsets.

`:app:testDebugUnitTest` runs JVM tests of the store protocol, listing rules,
built-in add-on, torrent discovery and source sorting without a device or network.

The [test review](TESTING.md) records the 12 disabled low-value checks and the
routine/extended split. Disabled checks carry JUnit `@Ignore` reasons; broader
UI/settings/import coverage remains available with `acceptance`. Rewinding into evicted data is proved
end to end by `AppFlowTest` (the engine suite keeps one whole-file read through
a turning-over cache; metadata is checked for each hash version), and playback
measurements run only under `profile`, not `test` or `acceptance`. Source
ranking, retry identity and the startup deadline are unit-tested
(`TorrentSourcesTest`, `SourcePolicyTest`, `StartupWatchdogTest`), so the
acceptance suite keeps only the failover paths that need a real player: bounded
automatic failover with manual choices left alone, and recovery after frames
(position and speed kept). With
`-PliveAddons=true` it also runs `RealFilmsLiveTest` against Stremio's
directory, Cinemeta, Public Domain Movies, OpenSubtitles v3, archive.org and
Wikidata with eight public-domain films; FFmpeg (when installed) checks codec,
resolution and running time and decodes a frame 30 minutes into each film.
Frames and a results table go to `app/build/live-evidence/`. Neither live suite
is required: public services and swarms change.

`live-addons` audits every entry returned by the current store on the verified
emulator, supplemented by the earlier Nuvio list in the test-only asset
`app/src/androidTest/assets/nuvio-addon-audit.json`. Personal-manifest services,
upstream indexes and unverified names have explicit coverage records. This
asset does not add providers to the production store.
The audit previews real manifests, samples up to two usable catalogue/search
resources per provider, and requests supported movie metadata, streams and
subtitles for the public-domain 1968 *Night of the Living Dead*.
It then opens each actual review screen and presses Install where setup is not
required, using an isolated database and removing each accepted provider before
the next. The report in `streaming/simulator/.cache/evidence/live-addons.json`
records external HTTP/certificate/network failures, setup requirements and
source counts separately from successful review installation. Source discovery
does not establish video playback; `live-film` supplies that separate check.
These live commands do not supply paid accounts or credentials.

`live-torrents` installs each sampled provider through its review screen in an
isolated database, then opens a supported torrent for the 1968 film. It prefers
the public-domain directory's known copy when an index offers it, handles an
explicit video-file choice, and records rendered playback, provider errors and
setup requirements in `streaming/simulator/.cache/evidence/live-torrents.json`.
One sampled torrent does not establish availability of every result from an index.

For an explicitly requested title, `./streaming/simulator.sh requested-title tt1234567`
copies only the currently accepted, enabled provider descriptors into an isolated
test database. It samples each provider and combined automatic playback; combined
playback also checks Up/OK options access and a seek to 30 minutes. It does not
install new providers into owner data. Internet access is opt-in; select only a
title authorized for this reproduction. Outcomes are in
`streaming/simulator/.cache/evidence/requested-title.json`; runner completion alone
does not mean every provider played. The normal fixture suites never run this probe.

If public IPs work but DNS lookups fail inside the emulator, restart that verified
AVD with an explicit resolver, for example
`DANIIL_CAST_DNS_SERVERS=1.1.1.1,8.8.8.8 ./streaming/simulator.sh start`.
The option applies when starting a stopped emulator; it does not reconfigure a
running instance, disable TLS verification or change the host/physical TV DNS.
Startup allows continued payload progress for up to three minutes after video
preparation begins, with a one-minute transfer-stall deadline. Playback stalls
after the first frame retain the existing deadline. Pausing before the first
frame suspends both startup deadlines. An expired automatic source follows the
same three-attempt failover limit as opening errors; explicit source choices
remain explicit. Back can cancel preparation.

The live torrent test uses an instrumentation-only public-network setting:
LAN discovery and port mapping are off, and native peers, trackers and DHT nodes
on private, loopback, link-local or other reserved addresses are filtered.
Host and JNI regressions verify that even an explicitly supplied loopback
seeder cannot be contacted in that mode. Release configuration does not read
this instrumentation setting. Required suites retain their loopback-only mode.

Tests use generated loopback fixtures on 58133, the real seeder on 58134, and a
deliberately untrusted TLS fixture on 58135. The TLS test must fail certificate
verification; no trust-store change or certificate bypass is used. Fixture code
lives outside the app. The launcher verifies serial, emulator flag and AVD name
before every device operation and preserves runner output in
`streaming/simulator/.cache/evidence/`.

The routine app selection excludes extended UI/settings/import paths, the long
soak and two-phase process-death test. CI still invokes process-death separately.
Debug app tests use isolated databases/player preferences and local-only peers.
After installing the current debug app/test APKs and starting services,
`streaming/simulator/process-death.sh` terminates actual playback after durable
progress, then checks orphan recovery and resumed playback in a fresh process.

The shrunk release JNI probe builds with `-PreleaseValidation=true
:app:assembleRelease :app:assembleReleaseAndroidTest`, using the local debug
signing key explicitly for validation; ordinary release output is unsigned.
The launcher invokes `tv.daniil.stream.ReleaseProbeInstrumentation`, a small
Android platform runner that does not retain AndroidX/Kotlin test dependencies
in the shrunk application. It opens no owner database and uses explicit
local-only native peers. Never use
`connectedAndroidTest` to select a device. Both app and library pin the NDK so
final release symbol stripping uses the intended toolchain.

See the [validation record](../../docs/streaming-validation.md) and the
[complete application specification](../../docs/STREAMING_APP_PLAN.md).
