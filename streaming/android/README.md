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
watched/favourite controls and seasons/episodes. Home also tells the viewer
about [new episodes](#new-episodes) of the series they follow. Settings manage add-ons,
catalogue visibility, languages, subtitle style (size, font, colour,
background, position), cache size (128/256/512 MiB),
autoplay and app-cache cleanup. Versions, statistics and license texts live in
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
cd streaming/android
./gradlew :app:testDebugUnitTest --tests tv.daniil.stream.DiagnosticReportTest --tests tv.daniil.stream.ErrorQrPanelTest
```

The tests decode every QR part, reconstruct Unicode reports, check redaction
and the Developer-mode gate, and render the TV error surface. Snapshots are
written to the ignored `app/build/error-report-snapshots/` directory.

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
  again on the next start. If Home is still empty, a quiet text message points to Settings for
  catalogues and imports. Test databases and local-only instrumentation install
  no defaults, so tests never reach public providers.
- **Tab bar:** Search, Home, Library and Settings sit in one borderless floating pill.
  Search and pagination capsules use the same borderless treatment. It slides away once content scrolls under it and returns when focus reaches it
  or the page is back at its top. Content scrolling up fades into the top edge.
- **Clock:** a small clock stays in the same top-right corner on browse pages,
  paused player controls and player menus. It remains visible through browse
  reloads and navigation; startup, player loading and active playback keep the
  screen clear. Toolbar actions sit to its left. Refresh rotates its icon
  when clicked. Settings → Clock sets its
  format (Automatic, 24-hour, 12-hour or Off) and whether the player shows it.
- **Settings:** a split view. Seven categories sit on the left: Add-ons,
  Torrents, Languages, Subtitles, Playback, Clock and About.
  - The focused category's rows show on the right and change as focus moves.
  - Right enters the pane at its first row, and Left returns to the lit
    category. The route remembers which category was open.
  - Other lists and forms (Imports, the store, add-on pages, Sources) sit in a
    centred column; shelves and grids use the full width.
- **Details:** Play or Resume (with the episode), Sources, and More retain labels.
  Favourite and a film's Mark watched are circular icon buttons, with state highlights,
  accessible action names and labels that fade between separate text views on focus.
  In a series, focusing or hovering a season changes its episodes immediately.
  Entering seasons or episodes fades and shifts the overview into the show's
  title artwork, season strip and episode shelf. A text title is the fallback
  when no logo loads. Episode cards show Episode N, title and a description of
  at most three lines. Up from the season strip or Back restores the
  overview. Each season remembers its horizontal shelf position.
  Episode cards have no child option buttons. Selecting a card opens its attached
  menu with Play/Resume, Choose source and a single Mark watched/Mark unwatched
  action. Watched state is a small artwork checkmark; descriptions stay free of
  controls. Up returns to the season strip. Only the chosen episode is changed.
  Home, catalogue and Library cards omit years and type labels; details keep
  release information. Favourites and completed Watch history cards omit their
  redundant collection labels and badges.
- **Sources:** only sources this TV can play. Sort chips order them by Best,
  Quality, Size, Seeds or Played; choosing the lit chip again reverses it
  (the arrow shows which way), and sources that don't advertise a value stay
  at the end either way. Each row shows provider, kind, quality, size and seeds
  read from the add-on's text, and marks sources that played (the most recent
  as "Last played") or didn't play on this TV. The marks hold only hashed source
  keys, never addresses. When none can play, the page says how many were found
  and why.
- **Automatic Play:** filters unsupported sources first, then favours 720–1080p. Within
  that range a weighted score combines advertised peers/seeds (logarithmic, the
  largest influence), a modest 1080p preference and a diminishing file-size penalty.
  A compact encode can beat a huge remux with slightly more peers. Zero peers and
  recent failures lose priority; missing metadata stays a fallback rather than an
  invented speed estimate. If quality and availability are both absent, provider
  order is retained. Structured seed counts and common textual labels are supported.
  No torrent is opened just to rank it. Parallel discovery gets at most 750 ms after
  the first usable result to improve the initial choice; complete results and an
  explicitly chosen source proceed immediately. Recovery remains bounded.
- **Next Episode:** appears during the final 10%, capped at 90 seconds. It is a manual
  shortcut, also available with automatic next-episode playback disabled. It never
  skips the current ending on a timer. Dismissing it suppresses the end-of-episode
  autoplay countdown for that episode; otherwise that existing countdown starts only
  after completion. Last episodes have no successor prompt. Choosing another episode
  starts it playing while retaining speed and track preferences.

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
  Moving Right into results fades the keyboard out and expands the grid to six
  columns. Left from the leading column restores the editing layout; Right
  returns to the selected result. The header's keyboard icon also switches modes
  by mouse/touch. The 260 ms bounds/fade transition preserves the query and
  selection without another provider request, and Back retains the active mode.
- **Alerts:** compact action rows use meaningful icons and an inset white focus
  fill. There is no Close/Cancel footer; Back or Left pops a nested level or closes
  the root. A click outside also dismisses it. Text-entry cancellation keeps edits
  unsaved; cancellation of the exit prompt returns to the current playback state.
- **Search keyboard:** an aligned six-column grid with 4 dp gaps, on three pages
  (letters, digits and punctuation, language-specific accented letters). Settings
  → Languages → Keyboard languages selects English, French, German, Spanish,
  Italian, Portuguese, Russian and Ukrainian layouts. The globe key cycles the
  enabled languages; with one language it opens their settings. Its key fills the
  bottom row and shows the language name. Search uses typed input; there is no voice mode.
  Space and Delete fill unused cells in the last character row. A five-character
  tail spreads evenly across the row and puts Space/Delete on their own full row,
  so no language or character page adds blank spacer keys. Page switches and Clear
  span two cells; the language key spans all six. Cell and gap widths are rounded
  to pixels before calculating spans. White focus fill keeps keys aligned without
  scaling the wide keys. Results update as you type,
  with no heading or count. Before a search, and when nothing matches, a quiet
  line sits centred in the results area.
- **Corners:** one scale in `res/values/dimens.xml`, on this 960 dp canvas
  (half of tvOS points):
  - 10 dp for buttons, rows, fields and artwork; 8 dp for keycaps and 6 dp for the search keyboard.
  - 16 dp for cards and 24 dp for sheets.
  - Capsules for floating and filter controls (tab bar, tabs, chips, search
    field); circles for icon buttons.
- **Type:** Inter, the open typeface closest to Apple's SF, bundled and
  subset to Latin, Greek and Cyrillic by `branding/make_fonts.sh` (other
  scripts fall back to the system font). Regular for text, Medium for
  headings and controls, SemiBold for emphasis, and the Display cut for
  titles from 24 sp. The player's type scale (`Text.*` styles) is overridden
  by name to use it, and its times use tabular figures.
- **Loading:** skeletons cover unknown remote content after a 160 ms delay;
  local pages and cached returns have no ghost forms/grids. Known title headers
  and existing results remain visible; a refresh keeps usable content. Poster
  placeholders use the current one-line captions and Search's active column
  count. The activity indicator is an eight-spoke wheel after Apple's,
  with fixed bars and an opacity trail advancing clockwise in distinct 128 ms
  steps (1.024 seconds per cycle). Its animated vector stays on the render
  thread while the main thread is busy.
- **Sounds:** made, not sampled: **Soft**, 28 rounded plucks rendered by
  `streaming/sounds/sounds.py` (see [its guide](../sounds/README.md)). Navigation
  has quiet ticks (focus moving, a row's end, typing); tabs, pages, chips,
  dialogs and the player's controls have small sounds; favourite, watched, an
  add-on installed, a failure, up next and a title's end have fuller ones, and
  the start screen has a short cue timed to its animation. Ticks follow only the
  viewer's arrows and touches, never focus a page restores, and lean up to a
  semitone with their place across the screen. `ui.Sounds` plays them as
  sonification (never taking audio focus) with one voice: a new sound cuts a
  smaller or equal one, is dropped under a larger one, a held arrow ticks at most
  every 45 ms and quick presses are spaced out; OK's select gives way to a more
  particular sound in the same press. Off with Settings → Playback → Interface
  sounds, or the TV's own System sounds switch.
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
- **Popups:** `Tv.Alert` uses one opaque panel with 22 dp corners. Menus attach
  below/above small controls, or beside episode artwork, clamped to the screen's
  safe margins. Each level measures its own content width: short lists contract,
  longer labels/forms expand within readable screen bounds. Their bounds start at the actual opener, using a small copy of
  that control for the transition; only menu content fades/slides, never stretched
  text or a screenshot of the whole page. Content appears after the shell expands.
  Automatic messages without an actual opening action fade at the centre instead
  of inventing a source on the tab bar. Empty action containers add no footer gap.
  Nested pages retain the same attachment edge, window, parent selection and scroll
  position. Back/Left returns one level; at the root either closes the popup.
  Short secondary headers and icon rows share aligned insets, with no redundant
  footer or Back header button. Long value lists scroll below a pinned header and
  preview; message-only panels scroll their content. Popups leave the clock header clear.
  Outside presses dismiss; no blur, tint or dim layer changes the background.
  Reduced motion follows Android's animator setting. Playback failures share the
  compact surface and Back/Left exit, retaining Retry and Choose source.
  More → Remove from library demonstrates a nested confirmation. The shared
  player's visible menus use this same app-owned popup stack for tracks, audio,
  sources, quality, speed and subtitle style. The shared player menu stays invisible
  as a transport/caption guard. Focusing a style previews it without saving; selection
  commits to both player preferences and Settings. Subtitle Settings uses the
  preview already on its page, with no duplicate preview inside the choice popup.
  Both entry points share the same labels and name effective default values. Cancelling source choices retains
  the active player and downloaded cache; selecting a source preserves pause, speed
  and position.

Glass comes from cheap blurs:
- **Artwork:** each title's artwork also has a softened layer, blurred and
  fading in towards the bottom where text, buttons and shelves sit, so
  translucent controls over it read as glass. Sources shows the artwork fully
  blurred.
- **Live, on Android 12+ only:** the tab bar and search field, and the
  buttons, chips and notices on a page, blur what is behind them
  (`GlassView`: a small RenderEffect GPU pass per surface while the picture
  behind it changes, none while the screen is still). Earlier versions,
  including Fire TV's Android 9, use a static material for the bar and plain
  platters for buttons, because BlurView there blurs on the CPU before every
  frame, which is what made the UI stall. Rows, tiles and keys never blur:
  they are many, and rows and keys sit on the plain canvas.
- **Player:** the video plays on a SurfaceView, which the system composites
  outside the view tree. Options and error panels use opaque surfaces.

Artwork is cached on disk (40 MiB, least recently shown evicted) as well as in
memory. Off-screen shelves keep their views, so scrolling back neither rebuilds
a row nor reloads its art.

The Seasons view leaves space between the title logo, season selector and cards.
Episode captions show the episode number, title, a description limited to three
lines and, when the provider supplies a valid date, its initial air date. Missing
dates add no empty row. Only the selected season's shelf is attached, so
episode images load for the season on screen, not the whole series.
An episode's action sheet uses Play or Resume according to its saved progress,
and offers Choose source. Its on-card completion toggle reads Mark watched or
Watched, with a season/episode-specific accessible action and no redundant badge.

The shared player's sources stay unchanged: app resource overrides and app-owned
presentation adapters give Daniil TV its look, while Daniil Cast keeps its own:
- **Palette:** `res/values/colors.xml`, `pill` and `bg_card_soft`.
- **Subtitle Box:** `DaniilSubtitleOutput` adds modest side padding and rounded
  corners per text line in playback and the settings preview. Other edges and
  bitmap cues retain Media3 rendering. Media3 1.11.1 exposes no public output
  factory on its final `SubtitleView`; the app keeps one reflective `setView`
  integration, retained by its R8 rules. Check `SubtitleBoxTest` and the release
  shrinker whenever updating Media3. The shared library and receiver are unchanged.
- **Overlay:** `layout/playback_layers.xml`, with the same IDs and the view
  types the library reads:
  - The title logo sits at the top, followed only by the season/episode context
    for series. Film facts and the selected-track summary stay off the overlay.
    Three round buttons follow: Mark watched / Mark unwatched, Audio and subtitles
    (the subtitle list, then Audio), and Player settings (quality, speed, subtitle
    style). Up focuses the watched toggle; Right reaches audio/subtitles. Menu on
    the remote still opens everything. The toggle preserves progress and works
    after natural completion too, without later saves undoing the viewer's choice.
  - Loading uses the same logo and a spinner. Stage names and technical messages
    appear only in Developer mode. One cached artwork request supplies both logo
    views; a missing or failed image keeps a readable text fallback and never
    delays playback.
  - There is no centre play/pause button. Tap the timeline to pause/resume;
    drag it to preview a position and seek on release. Remote OK and transport
    keys still play/pause, and left/right still skip. Up reaches the header,
    Down reaches the timeline, and left/right within the header reach each action.
  - The timeline follows the proportions of Apple's [tvOS playback example](https://support.apple.com/en-nz/guide/tv/atvb7944597f/26/tvos/26):
    a 4 dp track, small capsule playhead and close-set, tabular time labels.
    The digits anchor to the playhead independently of feedback width. A small
    circled pause badge and transient directional skip feedback share one slot;
    near the end, the feedback moves before the time to avoid crowding the
    remaining-time label. The timeline retains its 48 dp touch target and
    captions remain above the complete toolbar.
    The player's buttons grow while focused or under the pointer; the overlay
    doesn't clip them.
  - Subtitles are chosen in the player. With subtitles off, nothing downloads
    before the video starts; the add-ons' subtitles are then fetched. The
    Subtitles list always shows Off, the loaded tracks and every add-on file
    not yet loaded (marked Download), so other choices stay in reach while a
    track shows. A download reloads the video where it was, keeps the loaded
    tracks, shows the new one and remembers its language.
  - Errors are a centred glass alert like the app's, with stacked full-width
    buttons. It opens with its first button focused, and OK presses the
    focused one (`WatchActivity.onKey`); the library alone would retry.
- **Options menu:** tracks, audio, sources, quality, speed and subtitle style
  open in the app's own popups (`PlayerMenus`, see Popups above); Back and Menu
  close them. The shared player's menu (`layout/playback_menu.xml`, rows in
  `layout/row_menu.xml`) stays hidden as its transport/caption guard.
- **Subtitle font & style:** Standard, Bold, Condensed, Serif, Monospace,
  Casual, Cursive or Small caps, with expanded colors (White, Yellow, Cyan,
  Green, Magenta, Blue, Amber, Red), from the shared player's subtitle style
  (system families every Android TV has), with a live preview card in
  Settings → Subtitles and choice dialogs, plus the player's Subtitle style menu.
  Daniil Cast's receiver offers the same choice on the TV; the Chrome extension
  doesn't send fonts.

Screens are built off the main thread as `ui.Page` lists of `ui.Item` blocks
(`BrowseScreens`, `TitleScreens`, `SetupScreens`), and `ui.PageAdapter` turns
them into views. Every focusable view is tagged with a stable key. Back restores
the retained page, focused card, vertical scroll and each shelf's horizontal
position without another provider request. Tab roots retain their own focus and
viewports too, including Search's nested grid and both Settings columns. Returning
from playback or changing favourites updates local data while keeping the prior
Home/Search provider snapshot, even after a long film. Provider configuration
changes invalidate snapshots; explicit Refresh fetches new data. The Back button appears on subpages; default Home,
Library and Settings views omit it. Stable
keys also let the instrumentation find controls by label. Year ranges use
`2016 - Present` or `2016 - 2022`. The startup screen uses a light uppercase
STREAM wordmark and a light sweeping through the play icon's full interior,
clearing the tip before repeating.
`MainActivity` only owns the back stack, chrome, dialogs and worker
generations.

Browse category requests retry transient network, timeout, rate-limit and server
errors up to three attempts, with cancellable 1/2 second backoff and a visible
attempt counter. The failure panel includes Reload; it starts a fresh attempt
budget and bypasses the fresh-page cache. Certificate, authentication, invalid
response and ordinary client errors are shown immediately. A genuine empty
catalogue stays an empty state. Existing saved posters remain usable during a
failed refresh. The repository retains at most 12 recent category pages / 288
titles for five minutes, keyed by the full category/filter/page request; add-on
configuration changes and Clear app caches invalidate it. Search retains at most
six query snapshots. Provider requests and retry waits stay off the UI thread;
navigation cancels the old scope and its sockets/backoff before starting another.

Catalogue add-ons such as Cinemeta describe titles but supply no video.
Details and Sources say so ("No source add-on installed") and open the store on
its Sources filter. The player names the actual cause when nothing can play: no
source add-on, failing providers (by name and reason), only unsupported
results, or no results. Nothing is installed without the user's review.

`WatchActivity` re-resolves sources and uses the shared player. `SourcePolicy`
checks advertised hints and actual Media3 formats against the 1080p profile.
Bundled FFmpeg audio/video decoders cover missing TV codecs, including E-AC-3,
DTS, TrueHD and common 10-bit video. See [DECODERS.md](DECODERS.md) for decoder
selection, recovery, limits, licensing and the focused `decoder-test` command.
Automatic failover is limited to three candidates before first frame; explicit
choices are preserved. Ambiguous multi-file torrents require a file choice.
Screensaver, sleep and Home transitions pause an already started film and save
progress while retaining Media3, its source scope and the bounded native
download cache. Returning to the player keeps the same position, speed and
downloaded data, without resolving the source again; playback stays paused
until the viewer resumes. Background time does not count toward stall/recovery
timeouts, and pending next-episode playback is cancelled. Closing the player
or destroying its activity releases these resources. After process death or
activity recreation, playback is reconstructed from saved progress; startup
recovers disposable payload/subtitle files left by a killed process.
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
HTTPS requests can't deadlock the pool. Internet Archive (a starter default, removable like any other)
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
[playback preparation and recovery](../PLAYBACK_IMPLEMENTATION.md).

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
`WatchActivity` handle ranking, file selection and native session ownership.

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

See [TESTING.md](TESTING.md) for what each suite proves and the focused
commands. The routine gates, from the repository root:

```sh
(cd streaming/android && ./gradlew :app:testDebugUnitTest)  # JVM: protocol, store, new episodes; no device or network
./streaming/simulator.sh test       # engine, data and core app tests on the verified AVD
./streaming/simulator.sh smoke      # one catalogue → torrent → player flow
```

## Device target and budgets

The intended TV is a first-generation Fire TV Stick HD: 1 GB RAM, a 32-bit ABI,
Fire OS 7 (Android 9), H.264/HEVC/VP9 up to 1080p. Development and every test
run on the `DaniilCast_Local_TV` emulator (Android TV 16, 4 GB, ARM64), so
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
measure (`./streaming/simulator.sh profile`), not a guarantee.

## Build variants and branding

`branding/make_brand.py` regenerates the launcher icon and the TV banner from
one geometry: a graphite field and a frosted-glass play mark. The icon is an
adaptive vector with a monochrome layer; the separate 16:9 banner is PNGs at five
densities with the wordmark in Inter Display SemiBold. The application declares
the adaptive icon and TV banner separately; the launcher activity inherits them.
Do not wrap the banner in a padded square icon: that makes the artwork smaller.
`branding/make_fonts.sh` regenerates the bundled Inter subsets.

For a separate installation alongside Stream, build with
`./gradlew :app:assembleDebug -PseparateTvApp=true` from `streaming/android`.
This uses package `tv.daniil.stream.tvapp` and the launcher label **Stream TV**;
it has its own data and does not replace `tv.daniil.stream`.
Fire TV's choice of artwork can involve Appstore assets as well as APK resources.
Compare a working app on the same device before attributing a tile problem to
sideloading alone.

## Known gaps

- **Endurance:** the last thirty-minute soak on the emulator failed after about
  27 minutes (`Timed out: soak seek 159`); the cause hasn't been investigated.
- **Formats:** VC-1 has decoder-discovery coverage only. Small 10-bit fixtures
  prove decoding and seeking, not full-resolution throughput on a TV; HDR
  output, Dolby Vision, unsupported extractors, damaged files and DRM can fail.
- **Devices and providers:** nothing has run on the physical stick. Public
  provider latency, expiring links and swarm health are outside the fixtures.
- **Series loading:** Details parses a series' whole episode list and builds
  every season's card models up front; building the selected season on demand
  would save work on long series. Concurrent requests for the same uncached
  image aren't combined, and rebinding an image doesn't cancel its download.
- **Metadata:** reopening Details fetches its metadata again after the 60-second
  preparation window; showing the cache first and revalidating would be faster.
  `Title` keeps no network/studio or person data, so the app can't show the
  original service (Netflix, Apple TV+) or person pages; add-ons such as
  AIOMetadata and the TMDB add-on publish those fields.
- **New episodes** rely on providers' `released` dates; a provider without dates
  never announces anything.
- **Search return:** Back from a search result restores the right card but not
  its exact vertical position in the results grid (about 30 px off);
  `BrowseRecoveryTest#searchLibraryAndHomeRestoreTheirOwnFocusAndNestedViewports`
  fails on it, and did before the new-episode work.
