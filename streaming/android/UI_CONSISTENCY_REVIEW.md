# Interface consistency review — 26 October 2026

Reviewed the existing native Android TV app on `DaniilCast_Local_TV` (`emulator-5556`, verified qemu), with isolated test databases, generated media, loopback providers and real torrent peers. No physical TV, owner library or public providers were used. This is a broad review of the main flows, not a claim that every provider, device or possible error state has been tested.

## Changes made

- Standalone text actions now use capsule corners through `tv_button` and `tv_dialog_primary`. This covers film/series Play, import actions, store actions, notices and recovery actions using the common renderer. Early Next Episode and end-of-episode actions use the same shape. Icon actions remain circles; menu rows, text fields, keyboard keys and artwork retain their own corner treatment.
- Mark unwatched uses Material Icons `remove_done` (crossed-out checks) on film actions, episode menus and the player. A check remains the state badge for completed episodes. The vector's upstream source and Apache licence are included in the app notices.
- Player Settings and Audio & subtitles now use `Tv.Alert`/`MorphDialog`, with the same opener animation, nested pages, selected checks, Back/Left navigation and outside dismissal as the rest of the app. Menu/Captions remote shortcuts also close the panel. There are no Cancel/Close footer rows or background blur/dim layers.
- Popup rows accommodate two-line labels and separate secondary values. Long value lists scroll below their heading/preview, with a subtle edge fade. Panels stay below the clock's header area and inside the bottom/side margins. Selected rows remain fully visible when opening a long list.
- Subtitle previews now behave the same in Settings and the player: focus only previews, selection commits. Player selections also update the encrypted Settings store. Embedded, downloaded and downloadable subtitles have distinct descriptions.
- Cancelling either source picker retains the player, torrent handle, position and download counters. Selecting a different source preserves pause, playback position and speed. Imports do not show a source submenu with no applicable providers.

Before/after evidence: [film before](../simulator/.cache/evidence/current-audit/design-audit-before/02-film.png), [film after](../simulator/.cache/evidence/current-audit/design-audit-after/02-film.png), [unwatched action](../simulator/.cache/evidence/current-audit/design-audit-after/03-film-watched.png), [old player tracks](../simulator/.cache/evidence/current-audit/design-audit-before/18-player-tracks.png), [new player tracks](../simulator/.cache/evidence/current-audit/design-audit-after/18-player-tracks.png), [new player font preview](../simulator/.cache/evidence/current-audit/design-audit-after/21-player-font.png), [Settings popup](../simulator/.cache/evidence/current-audit/design-audit-after/12-settings-popup.png).

## Findings resolved in the follow-up

1. **Search failure and empty results now differ.** Exhausted provider requests show “Search unavailable,” an attempt count, a concise reason and inline Reload. “No results” is reserved for a successful empty search. Reload works in keyboard and full-grid modes, including remote Right/Left navigation.
2. **Home now shares bounded recovery with catalogue pages.** Home and Search use up to three cancellable attempts for transient network/timeout/HTTP-408/429/5xx failures. Only failed resources are retried; successful provider results remain visible. Authentication, certificate and malformed-data failures stop without automatic retry. Home's notice sits between the spotlight and shelves so it stays near the current content. Reload begins a fresh user-requested load.
3. **Subtitle terminology and effective values are shared.** Settings uses `SubtitleStyle.Setting` titles/value resources, including “Subtitle background” and “Dark box,” and names the effective default rather than displaying “Default.” No stored preference keys or values changed.

Additional requested changes:

- Popup widths are measured from the current page's text, icons, fields and previews. Small value lists shrink; longer source labels, forms and messages can expand, bounded by the screen's safe margins and readable line length. Nested pages independently determine their width. The existing morph animates width and height while preserving the attachment edge and parent focus.
- Subtitle Settings popups contain only choices. Focus updates the existing preview at the top of Settings; dismissal restores saved styling, and selection commits. The player retains its own popup preview because it has no Settings preview behind it.
- Search copies recovery actions during incremental updates, preserves existing result tiles during retries and discards stale focus callbacks from replaced grids.

Follow-up screenshots: [Home recovery](../simulator/.cache/evidence/current-audit/findings-resolved/validation-home-recovery.png), [Search recovery](../simulator/.cache/evidence/current-audit/findings-resolved/validation-search-recovery.png), [single page preview](../simulator/.cache/evidence/current-audit/findings-resolved/validation-settings-single-preview.png), [compact popup](../simulator/.cache/evidence/current-audit/findings-resolved/validation-popup-content-root.png), [expanded level](../simulator/.cache/evidence/current-audit/findings-resolved/validation-popup-content-wide.png), [compact nested values](../simulator/.cache/evidence/current-audit/findings-resolved/validation-popup-content-width.png).

Original failure evidence is kept at [Search before](../simulator/.cache/evidence/current-audit/design-audit-other/31-search-failure.png), [Home before](../simulator/.cache/evidence/current-audit/design-audit-other/30-home-failure.png) and [subtitle wording before](../simulator/.cache/evidence/current-audit/design-audit-other/27-settings-edge.png). The failing providers were deliberate local fixtures, not an availability assessment of public add-ons.

## Flow coverage and health

1. **Home → film → actions/menu:** consistent capsule/circle shapes, readable action hints and state-specific watched icon after the change. Captures 01–04.
2. **Library and history:** gallery captions and action shapes reviewed; empty Library uses quiet text. The legacy `History` route intentionally renders Library, so capture 06 is the same combined library/history view, not a separate History implementation. Captures 05–06, 23–24.
3. **Browse categories → failed catalogue:** category cards retain appropriate artwork corners; Reload is now a capsule. Home/Search now use the same retry classification and recovery controls. Captures 07, 32.
4. **Search keyboard ↔ full grid, empty and failed search:** both layouts visually reviewed; keyboard keys remain rounded rectangles. Failure and successful-empty messaging are now distinct, with a reachable Reload action. Captures 08–09, 25, 31.
5. **Settings → subtitle preview → value popup:** panel/preview/row gaps and clock clearance reviewed; long lists keep the preview visible. Wording is shared with the player and the existing page preview handles live focus previews. Captures 10–12, 27.
6. **Settings → Playback and Imports → entry form:** form remains a text field rather than a capsule; action spacing and validation/back navigation covered by focused interaction checks. Captures 13–14, 26.
7. **Series → seasons → episode popup:** card descriptions, title spacing, single watched/unwatched action and common popup presentation reviewed. Captures 15–16.
8. **Player → tracks/audio → settings/speed → subtitle style/font:** common popup system replaces the visible old menu, with pinned previews and nested focus restoration. Pointer and remote interaction checks cover actual session changes. Captures 17–21.
9. **Source selection:** film source page and player source picker reviewed; cancellation and actual selection exercised with real torrent data. Capture 28 and `PlayerPopupTest`.
10. **Add-on store:** filter chips, source rows and action shapes reviewed using only the local fixture directory. Capture 29.
11. **Empty Home and provider failures:** quiet Home/Library/Search layouts checked; The follow-up resolves the Home/Search recovery gaps documented in the original review. Captures 22–25, 30–32.

## Verification and limits

Only selected checks relevant to this work were run. No complete existing suite was run.

- `PlayerInteractionTest`: pointer tracks/audio/style/speed/timeline and remote-only subtitle/transport navigation (2 checks).
- `PopupInteractionTest`: nested navigation/focus, attachment at all four screen edges, long selected lists, forms, message-only panels and playback failure Back/Left behaviour (6 checks).
- `PlayerPopupTest`: preview versus commit, both preference stores, cancellation without native cache reset, actual source selection preserving viewer state (2 checks).
- `PlayerPresentationTest#logosReplaceTitlesAndTheFirstActionTogglesWatchedBothWays` (1 check).
- `AutoplayInteractionTest#manualNextWorksWithAutoplayOffAndUsesTheNextEpisodesSource` (1 check).
- Java/resource compilation and `git diff --check`.

`DesignAuditCapture` is an explicitly invoked screenshot utility, not a regression test. It captures the same 21 states before/after and 11 additional empty/settings/store/failure states. Evidence is kept in ignored local simulator caches; it is not an app asset or committed owner data.

Generated catalogue fixtures have no posters or show logos, so their fallback artwork/text is expected. The logo and watched test uses its own local artwork server. Local success does not establish physical remote ergonomics, production provider reliability, large-font accessibility, screen-reader compliance or all-device performance.

Implementation stays in the standalone app. The shared player menu remains invisible as its existing transport/caption guard; the app renders only the new popup panels. Shared Cast playback sources were not changed.

Follow-up validation adds `DiscoveryRecoveryTest`, `SubtitlePagePreviewTest` and
`PopupInteractionTest#nestedPagesMeasureTheirContentAndMorphNarrowWideNarrow`.
Selected existing category, form, edge-placement, long-list, player preview,
Search-mode and exact-return-position checks also ran. The old JVM subtitle
snapshot class is ignored and provides no pass evidence. See `TESTING.md` for
the selected follow-up command; no complete existing suite was run.
