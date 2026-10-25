# Series loading review — 25 October 2026

The concern is partly confirmed: episode metadata and card models are eager;
episode image downloads are lazy. This review does not change loading behavior.

| Stage | Current behavior | Implication |
| --- | --- | --- |
| Open details | `TitleScreens.details` shows catalogue metadata first, then awaits the add-on metadata on a worker. `Title` parses the complete returned `videos` array, including episode descriptions, dates and thumbnail URLs. | A long-running series has a larger metadata response, even before entering Seasons. URLs are not image downloads. |
| Build seasons | `TitleScreens.episodes` builds all season panes and episode card models, scanning the episode list once per season. It reads saved progress once for the whole title. | Work and allocations grow with the whole series. Build the selected season's card models on demand; group episodes in a single pass. |
| Display artwork | `PageAdapter.TileAdapter` loads images when cards bind. Only the selected season's shelf is attached; RecyclerView can also bind nearby cards. | The app does not fetch every episode thumbnail on opening a show. |
| Cache artwork | `Artwork` uses an 8 MiB decoded memory cache, a 40 MiB disk cache and two workers with a bounded queue. Downloads are capped at 4 MiB each. | Memory and concurrency are bounded. Concurrent requests for the same uncached URL are not combined, and rebinding an image does not cancel its old download. Those are useful later improvements. |
| Cache metadata | Returning Back to a clean retained page avoids a provider fetch. `PlaybackPreparation` also reuses the current title's metadata for up to 60 seconds. Outside that reuse, `AddonRepository.metadata` tries the network first and reads its disk cache after a failure. | Reopening details can repeat a full response. A freshness policy with cached content shown immediately and background revalidation would help. |
| Prepare playback | `PlaybackPreparation` discovers sources for the chosen episode, not every episode. It does not open torrents or download media payload. | This is intentional preparation for Play; whole-series playback is not prefetched. |

The standard [Stremio metadata contract](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/meta.md)
contains episode lists in `videos`. Its [metadata request](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/requests/defineMetaHandler.md)
does not define standard per-season pagination. Lazy card construction is possible
without another provider; reducing the metadata response itself requires provider
support rather than assuming that a season parameter exists.

## Measured fixture check

On `emulator-5556` / `DaniilCast_Local_TV`, the production series UI with 60 episodes
across three seasons requested **4** unique episode images initially and **8**
total after focusing the second season. The unvisited third season had **zero**
requests. This checks request timing with loopback fixtures, not public-provider
latency or whole-app performance. No timing benchmark was run.

Focused check:
`SeriesBrowseTest#seriesArtworkLoadsForTheVisibleSeasonRatherThanTheWholeShow`.

## Service information

`Title` currently retains no original network, streaming service or regional
availability field, and its cache does not preserve provider extension fields or
metadata `links`. The add-on's own name is not the show's original service.
We cannot reliably display Netflix, Prime Video or Apple TV+ with the data the
app currently keeps. Supporting this needs a suitable metadata source/field;
original service and current regional availability should be separate facts.

### Available add-on support checked on 25 October

The sources do exist. AIOMetadata v3.7.0 includes
[network/studio fields](https://github.com/cedya77/aiometadata/blob/v3.7.0/addon/lib/metaDetails.ts),
rich cast/director records in `app_extras`, and
[person metadata](https://github.com/cedya77/aiometadata/blob/v3.7.0/addon/lib/personMeta.ts)
with a portrait, biography and filmography. Its person records are requested as
`meta/person/{personId}`. The TMDB Addon also emits rich cast records through
[`app_extras.cast`](https://github.com/mrcanelas/tmdb-addon/blob/main/addon/lib/getMeta.js).
These are richer than the string names currently retained by Daniil TV.

Using them in this app requires retaining person identifiers, portraits and
network fields, and adding a person page that resolves credits only when opened.
For directors, filmography results must be filtered by directing credits; acting
credits and producing credits should not be presented as films they directed.
No provider was installed or configured during this review. The checks inspected
published source and documentation, without querying public media providers.
