# Interface design

The browse UI follows tvOS. Artwork fills the screen behind a dark scrim. On
Home, a spotlight shows the focused card's logo or title, facts and
description, and the backdrop cross-fades to its art once focus settles.
Shelves scroll horizontally. Page titles scroll with their page.


- **First run:** no onboarding and no setup. On its first start the app
  installs the starter add-ons (Cinemeta and OpenSubtitles)
  and the torrent sources Meteor, MediaFusion, Peerflix and TorrentsDB (their
  public, unconfigured addresses) in the background from their current
  manifests, and refreshes Home when they arrive. An add-on already installed
  with the viewer's own configuration is not added again. Each default is installed once (remembered in the encrypted store),
  so one the viewer removes stays removed; one that can't be reached is tried
  again on the next start. If Home is still empty, a quiet text message points to Settings for
  catalogues and imports. Test databases and local-only instrumentation install
  no defaults, so tests never reach public providers.
- **Navigation rail:** Search, Home, Browse, Library and Settings share a vertically centred glass rail.
  Wrapped is the default on Home, Browse, catalogues, Library, Settings and its management pages, including
  while scrolling. Search, Details and Sources hide the rail to give their keyboard, action row or source list
  more room. The wrapped rail is 56 dp wide with 6 dp insets around
  circular 44 × 44 dp controls and 6 dp gaps. Expansion morphs to a compact 144 dp over 240 ms, revealing
  labels while the icons stay fixed. Its width fits the longest measured label plus icon spacing and
  trailing padding, growing beyond 144 dp for larger text. Expanded navigation overlays the existing page as a contextual popup,
  using the same opaque material, 22 dp corners and shadow as the other menus. Opening and closing it never
  moves/resizes the page, its controls or its scroll position. No navigation gradient or dimmer is used.
  The wrapped content viewport begins at 104 dp; pages with hidden navigation reclaim that space at 16 dp.
  Search, Details and Sources always hide the rail and its reveal target; Back returns to the previous page.
  On pages with a wrapped rail, first Left focuses it; a second, separate Left opens
  the labels. Focus and Up/Down alone never expand it, and held-key repeats do not count as a second press.
  Wrapped rail controls also support clicks and touch. Browse uses a compass icon.
  Browse is a primary page with catalogue categories, not a Home toolbar action.
- **Toolbar:** Back remains an icon-only circle at the top left. Refresh is always icon-only at
  the top right, beside the clock, with 40 dp controls and safe insets of 24 dp above and 40 dp
  from the sides. Refresh's icon rotates when clicked; its accessible label follows the page.
  Neither Back nor Refresh acquires a visible label in any navigation state.
- **Clock:** the browse clock uses one line at the top right. It remains visible through reloads
  and navigation; startup hides it. Player clocks retain their top-right placement on paused
  controls and menus; player loading and active playback keep the picture clear.
  Settings → Clock sets its format (Automatic, 24-hour, 12-hour or Off) and whether the player shows it.
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
  An icon button's caption is centred directly under it, 6 dp below its grown
  focus edge; it is placed from the button's own coordinates, so the row's page
  margin, arrangement and scroll never push it off-centre. Toggling favourite or
  watched morphs the glyph in place (heart outline fills from its centre with a
  small beat; the tick opens into the struck-out eye), then rests on the same
  picture as the static icon; with animations off it changes at once.
  Season tabs list numbered seasons in order with Specials last. The selected
  season is the resume or next episode's, else Season 1; Specials are selected
  first only when they hold that episode or are the only season.
  In a series, focusing or hovering a season changes its episodes immediately;
  the episode shelf slides a little in the direction of travel and crossfades
  (`ui.SeasonShelf`), while the season strip and its focus stay put.
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
  scrolls inside itself and never moves the page. Shelves scroll sideways on a
  soft critically damped spring (about a third of a second, no overshoot) that
  keeps its speed when the next press retargets it, so held and repeated presses
  glide instead of snapping card by card.
- **Search:** typing replaces only the four-column results beside the keyboard, so the
  focused key never moves and posters keep readable widths. Results are never cut at the column's edges: the grid
  extends to the window's top and bottom, so rows scroll on under the toolbar and off the screen's foot, fading
  out over that space (fully clear under Back and the clock). Inside the results area, 28 dp of content padding
  top and bottom holds a focused poster's lift and rim, and focus scrolls a card that margin inside the area
  rather than stopping at its edge.
  Moving Right into results slides the keyboard out and expands the grid to six
  columns. Left from the leading column restores the editing layout; Right
  returns to the selected result. There is no search bar or mode button: the typed
  text heads the keyboard as plain text and leaves with it, so the full layout is
  only results. A pointer or touch tap on a result keeps the keyboard, since a pointer
  has no other way to bring it back. Back closes Search. Results fade out before the
  column change and back in after layout, so films never travel over other rows.
  The keyboard slides and fades continuously over 360 ms while the results reflow invisibly;
  reversing mid-motion starts from its current position and opacity, without jumping to an endpoint.
  The transition preserves the query and selection without another provider request,
  and Back retains the active mode. Left within a keyboard row selects its previous key
  and stops at the leading edge. Navigation stays hidden for keyboard, results, loading and empty states.
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
  The query and first key row have fixed top positions; extra rows grow downward when a language
  or character page changes, instead of vertically centring the keyboard again.
  Keys share one graphite popup surface with 22 dp corners, equal 6 dp padding on all four sides and a soft shadow. Letters are bare at rest;
  letters use a 34 dp circular platter before focus growth, including on wider tail cells. Space, Delete, page switches,
  Clear and the language action use quiet capsule platters, with a hairline above the page actions.
  Focus and hover use the shared control easing. Growth adds about 4 dp to the visible width:
  12% for a letter's circle, about 5.7% for two-cell actions and 1.8% for the full-width language key.
  Scaling stays uniform on both axes, with smaller percentages for wider capsules.
  Growth uses drawing transforms, so neither focus nor hover changes row alignment or spacing.
  Space and Delete fill unused cells in the last character row when Space can span at least two cells.
  A four- or five-character tail spreads evenly across the row and puts Space/Delete on their own full row,
  so no language or character page adds blank spacer keys. Page switches and Clear
  span two cells; the language key spans all six. Cell and gap widths are rounded
  to pixels before calculating spans. The popup leaves focus growth clear of clipping. Results update as you type,
  with no heading or count. Before a search, and when nothing matches, a quiet
  line sits centred in the results area.
- **Corners:** one scale in `res/values/dimens.xml`, on this 960 dp canvas
  (half of tvOS points):
  - 10 dp for rows, fields and artwork; 8 dp for keycaps; 22 dp for the search keyboard's shared popup surface.
  - 16 dp for cards and 24 dp for sheets.
  - 28 dp for the morphing rail; capsules for its rows and chips; circles for icon buttons.
- **Type:** Geist Sans, bundled at Regular (400) for body text and supporting values,
  Medium (500) for labels, captions and small metadata, and SemiBold (600) for
  titles, section headings and primary actions. Hero/page/section titles use
  32/28/18 sp; control labels use 15–16 sp, body text 15 sp, values 14 sp and metadata
  13 sp. Player titles use 20 sp, its loading title 28 sp and clocks 14.5 sp. The full
  upstream faces preserve their character coverage; other scripts fall back to
  the system font. The player uses the same family through app resource overrides,
  with tabular figures for times. `android/branding/make_fonts.sh` restores the pinned faces.
- **Icons:** the MIT-licensed Geist UI collection (`geist-org/icons`), converted
  to native 24-unit vectors with round 2-unit TV outlines and optical sizing per shape.
  Filled icons retain their original lighter rim. Navigation, actions,
  menus and player controls share this set. Keyboard and subtitle icons are
  original matching drawings for concepts absent upstream. This is the open
  Geist UI collection, separate from Vercel's private Geist design-system icons.
  `python3 branding/make_geist.py all` regenerates fonts, vectors and bundled
  notices from pinned revisions with SHA-256 verification; sources and mapping
  are documented in [fonts and icons](fonts-and-icons.md).
- **Loading:** skeletons cover unknown remote content after a 160 ms delay,
  each in its page's shape (shelves, poster grid, source rows, add-on list). A filter chip or page change keeps
  the header and chips and draws skeleton posters in the grid below them. The spinner is only for a page with
  no known layout (a slow local list or form), never for a grid, shelf or list;
  local pages and cached returns have no ghost forms/grids. Known title headers
  and existing results remain visible; a refresh keeps usable content. Poster
  placeholders use the current one-line captions and Search's active column
  count. The activity indicator is an eight-spoke wheel after Apple's,
  with fixed bars and an opacity trail advancing clockwise in distinct 128 ms
  steps (1.024 seconds per cycle). Its animated vector stays on the render
  thread while the main thread is busy.
- **Sounds:** made, not sampled: **Soft**, 28 rounded plucks rendered by
  `sounds/sounds.py` (see [its guide](sounds.md)). Navigation
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
- **Remote:** On pages with a wrapped rail, Left from the content's left edge focuses the current destination.
  A second Left unwraps it into the navigation popup.
  Up/Down traverse the five destinations; the endpoints hold focus. In content, Up and Down move exactly one
  row of controls per press, however fast, even to a row that has scrolled away; only Up from the first content
  row reaches Back or Refresh where available. Utility Down restores content and utility Left returns
  towards Back or navigation. Settings opens with its shown category focused (or the remembered row).
  Right or Back from either navigation state restores the route's remembered control without leaving the page.
  Clicking outside dismisses it without activating the page beneath; choosing a destination closes it onto that page.
  Settings first returns from its pane to the selected category; Search first returns from results
  to its keyboard. Back closes a popup (including navigation), restores the series overview or pops a subpage;
  on another root page it returns Home, and on Home it exits. The Home spotlight remains a touch-only shortcut.
- **Focus:** the focused control turns white with dark text. Artwork grows,
  gains a white rim and a deep shadow while its caption keeps its size and rides
  the grown lower edge in the same layer pass, so the two always move together; buttons
  grow slightly; list rows and tabs don't grow, so their text never shifts.
  A raised control's shadow rises with focus but leaves at once with the white platter: a shadow still settling
  under a row or category whose platter has turned clear would show through as a dark pill.
  Settings marks only the open category with a faint platter.
  Right holds at shelf ends; Left only enters the rail from the first card. Up/Down move through content and the separate toolbar.
  Toggling a favourite, watched state or a Settings switch updates its block in
  place (`PageAdapter`'s bindings), so the focused control stays the same view.
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
  preview; message-only panels scroll their content. Browse popups clear the left rail and clock; player popups clear the top-right clock.
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
- **Live, on Android 12+ only:** the navigation rail, and the
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
presentation adapters give Stream its look:
- **Palette:** `res/values/colors.xml`, `pill` and `bg_card_soft`.
- **Subtitle Box:** `StreamSubtitleOutput` adds modest side padding and rounded
  corners per text line in playback and the settings preview. Other edges and
  bitmap cues retain Media3 rendering. Media3 1.11.1 exposes no public output
  factory on its final `SubtitleView`; the app keeps one reflective `setView`
  integration, retained by its R8 rules. Check `SubtitleBoxTest` and the release
  shrinker whenever updating Media3. The renderer remains app-owned.
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
- **Subtitle font & style:** the default is the bundled Noto Sans Medium;
  Atkinson Hyperlegible Next (bundled, for legibility; Cyrillic falls back to
  Noto at a matched size) and System sans-serif (the old Standard) join Bold,
  Condensed, Serif, Monospace, Casual, Cursive and Small caps. Colours are White,
  Yellow, Cyan, Green, Magenta, Blue, Amber and Red. `ui.SubtitleFonts` and
  `StreamSubtitleOutput` own the font in this app only: cue italics and bold
  survive, provider fonts, sizes and colours don't, and the eight older saved
  values still read the same. A live preview card sits in Settings → Subtitles
  and the player's Subtitle style menu. See
  [fonts and icons](fonts-and-icons.md#subtitle-fonts) for the font sources
  and licences.

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
DTS, TrueHD and common 10-bit video. See [decoders](decoders.md) for decoder
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

