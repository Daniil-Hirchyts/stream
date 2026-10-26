# Loading surfaces

The loading UI now distinguishes unavailable remote content from fast local
pages and already displayed content. Skeletons wait 160 ms before appearing;
finishing, leaving or cancelling a load removes its pending placeholder. A
refresh keeps usable content instead of replacing it with ghosts.

| Surface | Current loading behaviour |
| --- | --- |
| App launch | Existing branded intro; separate from page skeletons. |
| Home, first remote load | Spotlight/poster shelves, then poster-shelf fragments while catalogues arrive. The old category-block placeholder is removed. |
| Library / History | Real local collections or the real empty state. No fake collection headings or poster grids. |
| Settings | Real categories and preference rows. The old split-view ghost form is removed. |
| Imports / installed add-on / Developer / category index | Local data; no skeleton. An unusually slow local build gets a delayed small spinner. |
| Film / series details | Known title/artwork header immediately, then actual controls and metadata. Old button-row and details placeholders are removed. |
| Category page | Poster grids only while the requested remote page has no titles. Saved posters stay visible during refresh/retry. |
| Search | The keyboard stays interactive. A brief search uses a quiet “Searching…” state; a longer request gets poster placeholders in the active layout's five or six columns. |
| Sources | Actual title/header/sort controls with delayed source-row placeholders. The obsolete “Finding sources” alias is replaced by the source-row shape. |
| Add-on store | Existing filters/header; delayed listing rows while the remote directory loads. |
| Add-on review | A delayed review-content skeleton while remote manifest details are unknown. |
| Starter installation | An actual “Installing add-ons” status replaces the install action; no fake install button. |
| Artwork | Poster/thumbnail/title fallback belongs to the actual known title, not a data-loading skeleton. Images remain demand-loaded for attached cards. |
| Film loading / buffering | Existing player presentation; not a browse-page skeleton. |

Poster skeletons now reserve one title line, matching cards whose old year/type
caption was removed. Retired local-page, category-block, details and Settings
skeleton builders were removed. The UI snapshot inventory now lists only the
remaining full-page skeletons.

`LoadingSurfacesTest` checks a blocked real HTTP catalogue, local-page navigation
and cancellation of its late answer. The focused slow-source check verifies
that its actual header and Back action stay usable while only source rows wait.
`SearchModesTest` checks both animated search layouts, keyboard return by Left,
pointer/touch switching, retained selection and zero extra provider requests.

Screenshots support manual visual review; they are not pixel correctness tests.
