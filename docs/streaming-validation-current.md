# Current Daniil TV validation

Status: broad validation remains incomplete because the thirty-minute endurance check failed after about 26.9 minutes (`Timed out: soak seek 159`). That failure remains unresolved. A subsequent targeted playback/remote-control fix successfully played Hope (2026) through Meteor, TorrentsDB and combined automatic selection, including a seek to 30 minutes; see the [targeted reproduction report](streaming-hope-reproduction.md). The broad-run counts below are historical results, not a rerun of every suite after this fix.

Target: `DaniilCast_Local_TV`, `emulator-5556`, Android TV 16/API 36. Physical TV maintenance is outside this run.

## Changes under validation

- Preserve remote focus after pointer actions, so the first D-pad seek is delivered.
- Keep playback controls hidden beneath the open options menu when tracks change.
- Accept empty-resource setup manifests and offer configuration before installation.
- Allow active torrent transfers to extend startup, bounded by three active minutes preparing video and one active minute without transfer progress. Pause suspends both deadlines. Startup timeout uses bounded automatic failover and also catches READY without a video frame.

## Verified evidence already collected

- Native engine: 32 tests passed in normal, ASan/UBSan and TSan builds; 16 cache/eviction regressions passed with libtorrent invariants.
- Receiver: 9 shared-player instrumentation regressions passed.
- JVM: 21 current tests passed; debug/release APK builds and lint completed (0 errors, 18 warnings in each variant).
- Emulator: engine/JNI/Media3 13 tests, addons/store/source policy 17 tests, and the final application/player/profile suite 16 tests passed. This includes import window focus, setup descriptors, pointer/remote controls and timeout-to-failover.
- Shrunk release JNI: verified torrent bytes and native exception construction passed; debug APKs restored.
- Process death: real playback, durable progress, orphan cache recovery and resumed playback passed.
- Final generated-film profile: first frame 3,888 ms; cached seek 977 ms; uncached forward/backward seeks 857/1,151 ms; PSS 154,393,600 bytes; RSS 307,941,376 bytes; peak RSS 313,114,624 bytes; 2 dropped frames; 15,732,736 cache bytes allocated within the 16 MiB budget; 48 evictions.
- Four screen-render/focus tests produced 45 images.
- Both live store-film tests passed: Internet Archive HTTP playback and a seek to 30 minutes through Media3, plus Public Domain Movies torrent playback through the store.
- Live JVM checks decoded five public-domain film samples at 30 minutes. The Kid and Charade had no matching Archive copy; the chosen House on Haunted Hill file returned HTTP 500.
- Final live torrent run: 8 of 11 sampled providers rendered and advanced the public-domain film. MediaFusion could not obtain the selected torrent metadata; Peerflix lost supplying peers; StremThru required setup. Earlier failed samples are retained as historical evidence. The final run used the known public-domain control when offered; fixture import tests separately cover explicit file choice.

## Final public-domain playback samples

Film: *Night of the Living Dead* (1968). Each successful case required a rendered first video frame, advancing playback and duration of at least one hour. Times below include opening through the first 3.5 seconds of playback, not only time to first frame. Public torrent peers/trackers/DHT used the test-only native public-address filter; local fixtures use their separate loopback policy.

| Provider | Result | Elapsed | Selection / detail |
| --- | --- | --- | --- |
| Torrentio | rendered and advancing | 14.4 s | public-domain control |
| Comet | rendered and advancing | 22.2 s | public-domain control |
| Meteor | rendered and advancing | 15.6 s | public-domain control |
| MediaFusion | error | — | No peer supplied the torrent's metadata in time. Retry or choose another source. |
| StremThru Torz | needs setup | — |  |
| Peerflix | error | — | No peers are supplying this video. Retry later or choose another source. |
| TorrentsDB | rendered and advancing | 6.2 s | first supported 1968 candidate |
| Public Domain Movies | rendered and advancing | 15.5 s | public-domain control |
| TorrentClaw | rendered and advancing | 35.9 s | public-domain control |
| Watcho | rendered and advancing | 13.6 s | public-domain control |
| Pipe | rendered and advancing | 18.3 s | public-domain control |

## How the source chain works

Nuvio and Daniil TV are clients. Source addons return torrent identifiers/magnets or HTTP stream results; an aggregator such as AIOStreams combines configured providers. A TorBox-backed configuration asks TorBox to fetch or resolve a torrent and deliver a stream through the account. Direct P2P playback instead obtains pieces from peers. TorBox documents torrent creation and account limits in its [API](https://api-docs.torbox.app/) and [free-plan restrictions](https://support.torbox.app/en/articles/9836418-account-restrictions).

Cinemeta, AIOMetadata and Kitsu primarily provide catalogs/metadata, while OpenSubtitles, SubSource and SubSense provide subtitles. Their presence does not supply films. Upstream indexes such as 1337x, YTS, EZTV, The Pirate Bay and TamilMV are separate from installable client addons. No finite inventory can cover every private instance; this report covers the current store snapshot and all 43 earlier-list entries.

## Earlier Nuvio list: complete coverage inventory

The **2026-10-23 audit after the setup-manifest fix** covered 107 store listings plus 20 supplemental endpoints: 127 manifest/resource/review probes, 56 temporary installations, 44 setup requirements and 27 unavailable or unreadable endpoints. All temporary installations used isolated test data. See the [complete 127-endpoint table](streaming-addon-coverage.md).

Resource samples used the 1968 Night of the Living Dead and up to two catalogue/search resources per provider. Source counts include provider instruction clips and do not prove film playback. For example, Jackettio returned a configuration instruction video. Account-based configurations were not supplied.

| Entry | Current review or setup status | Notes |
| --- | --- | --- |
| Torrentio | valid; installed |  |
| Comet | valid; installed |  |
| Meteor | valid; installed |  |
| MediaFusion | valid; installed |  |
| StremThru Torz | valid; needs setup |  |
| Peerflix | valid; installed |  |
| TorrentsDB | valid; installed |  |
| Filmora | HTTP 402; unavailable |  |
| TorrentClaw | valid; installed |  |
| Watcho | valid; installed |  |
| Jackettio | valid; installed | Playback needs the provider's Jackett/debrid configuration. |
| Stremio-Jackett | requires a configured deployment; self-hosting needs Jackett host/API key and indexers; project also documents a public showcase whose availability was not tested |  |
| TorBox addon | NETWORK; unavailable | Official addon disabled on 2026-05-20; the historical endpoint is checked for its failure state. |
| Debridio | requires paid account and personal manifest |  |
| Orion | valid; needs setup | Streams require an Orion API key. |
| Pipe | valid; installed |  |
| Nexio Torii | HTTP 502; unavailable |  |
| HDHub / HDHub4u | valid; installed |  |
| PenguPlay | valid; installed | Configured playback requires the provider's sign-in flow. |
| PlavioHD | NETWORK; unavailable |  |
| CNCVerse Bridge | valid; installed |  |
| Sootio | NETWORK; unavailable |  |
| WebStreamrMBG | valid; installed |  |
| Flix-Streams | requires paid account and personal manifest |  |
| Showbox | valid; installed | Configured streams require the user's FebBox cookie. |
| 111477 | valid; needs setup | Empty-resource setup root now correctly requires configuration. |
| DuckStreams | AIOStreams configuration tool; requires a generated personal manifest |  |
| Streailer | NETWORK; unavailable | Trailers and recaps, not full-film sources. |
| HS+ | unidentified; no verifiable public addon endpoint found for this earlier name |  |
| Eclipsia | MALFORMED; unavailable | Nuvio JavaScript plugin repository; expected to be rejected as a Stremio addon. |
| AIOStreams | requires a generated personal manifest; root manifest.json is its web app manifest |  |
| Xperience | requires account and personal exported manifest | Verified MDBList/TMDB keys; Streams needs membership and debrid. |
| AIOMetadata | valid; needs setup | Empty-resource setup root now correctly requires configuration. |
| Cinemeta | valid; installed |  |
| Bingecat | requires account and personal manifest |  |
| Kitsu | valid; installed | Anime-only metadata; no applicable public-domain film resource. |
| SeaDex | AIOStreams built-in anime source; requires configured services |  |
| OpenSubtitles | valid; installed |  |
| OpenSubtitles PRO | valid; needs setup |  |
| SubSource | valid; needs setup |  |
| SubSense | valid; needs setup |  |
| Stremio Community Subtitles | valid; needs setup |  |
| 1337x, YTS, EZTV, The Pirate Bay, TamilMV | upstream torrent indexes; not installable addon manifests |  |

The public [TorBox changelog](https://feedback.torbox.app/en/changelog/v844) records retirement of its official addon. DuckStreams generates an AIOStreams configuration; SeaDex is an AIOStreams built-in anime source. Eclipsia uses a Nuvio JavaScript plugin repository format. The app accepts Stremio protocol addons. The empty-resource setup correction follows the [Stremio manifest protocol](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/manifest.md) and the observed AIOMetadata root descriptor.

## Account and configuration requirements

The user confirmed that no configured accounts are available. These requirements were checked against primary documentation on 2026-10-23; authenticated playback was not tested.

| Provider | Required setup |
| --- | --- |
| [AIOStreams](https://docs.aiostreams.viren070.me/configuration/setup/) | Select upstream addons, save a password-protected configuration and retain its UUID/install URL. Debrid depends on the selected sources. |
| [Bingecat](https://bingecat.com/) | Free account and personal installation/configuration. Optional AI integrations may need keys. |
| [Xperience](https://xperience-app.com/guide) | Email/code login, profile, verified MDBList and TMDB keys. Streams requires membership and a debrid service. |
| [Orion](https://github.com/gorlev/orion-stremio-addon) | Orion API key and configured manifest. Debrid is optional for torrent results. |
| [Debridio](https://debridio.com/faq) | Paid access, sign-in, dashboard configuration and its personal addon URL. Debridio is not a debrid provider. |
| [Flix-Streams](https://flixnest.app/flix-streams/) | The current official setup page describes paid access, registered account, activation, selected sources and personal manifest. Earlier forum references to a free tier were not confirmed by the current page. |
| [Stremio-Jackett](https://github.com/tsaridas/jackett-stremio) | Self-hosted deployment needs Jackett host/API key and indexers, with Docker or Node. A documented public showcase is a separate option; availability was not tested. |
| [PenguPlay](https://pengu.uk/) | Google authentication or a PenguPlay access token, then personal install configuration. |
| [Showbox](https://showbox.codiv.dpdns.org/) | FebBox `ui` cookie and generated configured manifest. |
| [AIOMetadata](https://docs.elfhosted.com/app/aiometadata/) | Select catalogs/providers and save UUID/password and install URL. The free ElfHosted instance supplies basic metadata keys. |

Forum discussion is observational, not a compatibility guarantee. The [Nuvio addon list](https://www.reddit.com/r/nuvioaddons/comments/1v9i6q2/the_ultimate_list_of_free_addons/) distinguishes P2P and HTTP sources. [TorBox's official free-plan limits](https://support.torbox.app/en/articles/9836418-account-restrictions) are one new download per 24 hours, ten per month, one active slot and 10 GB per torrent. Free app/addon installation therefore does not establish unlimited free TorBox access.

## Unresolved failure

- The thirty-minute endurance test failed after 1,616.396 seconds at seek 159. The runner reports one failed test; the host stage exited 1. The cause has not been investigated. Earlier successful measurements do not establish a completed endurance pass.

## Coverage limits

The user confirmed that no configured accounts are available. Account/profile-based providers are recorded as requiring setup; authenticated playback is outside the available coverage. Public sample results do not establish every title, stream, codec or provider configuration.

Raw runner logs, JSON measurements and screenshots are private, ignored artifacts under `streaming/simulator/.cache/evidence/`. No remote CI or physical-device verification is claimed.
