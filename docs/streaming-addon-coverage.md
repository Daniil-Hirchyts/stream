# Complete addon coverage — 23 October 2026

Snapshot: **107 current store listings + 20 supplemental endpoints = 127 checked endpoints**. The 43-entry earlier Nuvio inventory is cross-referenced in the [validation report](streaming-validation-current.md#earlier-nuvio-list-complete-coverage-inventory). Ten of those entries are setup tools, account services, an unidentified name, or upstream indexes rather than public installable endpoints.

All 127 endpoints were probed and opened through the production review screen on `DaniilCast_Local_TV` (`emulator-5556`, Android TV 16/API 36). The isolated test store was emptied between providers. Results: **100 valid manifests, 56 installations, 44 setup requirements, 27 unavailable/unreadable endpoints**. No directory failures.

Resources were sampled with the 1968 *Night of the Living Dead*: up to two applicable catalogs/searches plus advertised movie metadata, streams and subtitles. **HTTP / torrent / other are discovered result counts, not verified film playback.** An HTTP result may be a setup instruction clip (including Jackettio). Other results include external/proxy-dependent formats that this client does not play. Account-based playback was not tested because the user has no configured accounts; see [setup requirements](streaming-validation-current.md#account-and-configuration-requirements).

Public services change. A timeout, HTTP error, DNS/network failure or rejected certificate below describes this dated probe, not permanent availability. TLS verification remained enabled. Only public-domain film samples were used for playback.

| # | Addon | Collection | Manifest | Review | HTTP / torrent / other |
| --- | --- | --- | --- | --- | --- |
| 1 | Internet Archive | Built in | valid | installed | 7 / 0 / 0 |
| 2 | Torrentio | Torrent/P2P | valid | installed | 0 / 42 / 0 |
| 3 | Comet | Torrent/P2P | valid | installed | 0 / 190 / 0 |
| 4 | Meteor | Torrent/P2P | valid | installed | 0 / 56 / 0 |
| 5 | MediaFusion | Torrent/P2P | valid | installed | 0 / 25 / 0 |
| 6 | StremThru Torz | Torrent/P2P | valid | needs setup | — |
| 7 | Peerflix | Torrent/P2P | valid | installed | 0 / 6 / 2 |
| 8 | TorrentsDB | Torrent/P2P | valid | installed | 0 / 37 / 0 |
| 9 | Cinemeta | Official | valid | installed | — |
| 10 | YouTube | Official | valid | installed | — |
| 11 | WatchHub | Official | valid | installed | 0 / 0 / 3 |
| 12 | Public Domain Movies | Official | valid | installed | 0 / 1 / 0 |
| 13 | OpenSubtitles v3 | Official | valid | installed | — |
| 14 | Anime Kitsu | Community | valid | installed | — |
| 15 | AfterCredits | Community | valid | installed | 0 / 0 / 1 |
| 16 | Cast Search | Community | HTTP 404 | unavailable | — |
| 17 | Content Deep Dive Companion | Community | valid | installed | 0 / 0 / 1 |
| 18 | Discussio \| ElfHosted | Community | valid | installed | 0 / 0 / 1 |
| 19 | IMDb Ratings | Community | HTTP 404 | unavailable | — |
| 20 | Radios | Community | valid | installed | — |
| 21 | Peario | Community | valid | installed | 0 / 0 / 1 |
| 22 | Stremio Stinger Pro | Community | HTTP 404 | unavailable | — |
| 23 | Watch Next | Community | valid | installed | 0 / 0 / 10 |
| 24 | DoesTheDogDie (Jump Scares) | Community | valid | installed | HTTP 500 |
| 25 | Where's The Jump | Community | valid | installed | 0 / 0 / 1 |
| 26 | AIOStatus | Community | valid | needs setup | — |
| 27 | Anthony Bourdain Fans | Community | valid | installed | — |
| 28 | Archivio \| ElfHosted | Community | MALFORMED | unavailable | — |
| 29 | Game Cinema | Community | valid | needs setup | — |
| 30 | Plexio | Community | MALFORMED | unavailable | — |
| 31 | Statusio | Community | valid | installed | 0 / 0 / 0 |
| 32 | StreamBridge: Emby to Stremio | Community | valid | needs setup | — |
| 33 | TMDB Collections | Community | valid | installed | HTTP 404 |
| 34 | [NEW] TurkceAltyazi.Org | Community | HTTP 502 | unavailable | — |
| 35 | 🕒 Clockrr (Top Right) | Community | valid | installed | — |
| 36 | addic7ed subtitles by dexter21767 | Community | valid | needs setup | — |
| 37 | AddMovie for Stremio | Community | HTTP 503 | unavailable | — |
| 38 | AIO Subtitle | Community | TIMEOUT | unavailable | — |
| 39 | AIO Subtitle PRO | Community | valid | needs setup | — |
| 40 | AIOCatalogs | Community | valid | installed | — |
| 41 | Animaze | Community | valid | needs setup | — |
| 42 | Anime Catalogs | Community | valid | needs setup | — |
| 43 | Anime Seasons (with ratings) | Community | valid | needs setup | — |
| 44 | animeo | Community | valid | needs setup | — |
| 45 | Animes' season | Community | valid | installed | — |
| 46 | Auto Subtitle Translate by geanpn | Community | valid | needs setup | — |
| 47 | Auto Subtitle Translate by SonsuzAnime (DeepL) | Community | CERTIFICATE | unavailable | — |
| 48 | Buta No Subs Stremio Addon | Community | valid | installed | — |
| 49 | Catalog Builder | Community | valid | needs setup | — |
| 50 | Christmas Movies | Community | valid | installed | — |
| 51 | Cinemeta (with ratings) | Community | valid | needs setup | — |
| 52 | opensubtitles.org subtitles by dexter21767 | Community | valid | needs setup | — |
| 53 | Debrid Status by EfNikolas | Community | valid | installed | — |
| 54 | Discord Rich Presence Addon | Community | valid | installed | — |
| 55 | EfNikolas IP Test | Community | valid | installed | — |
| 56 | GTSubs | Community | valid | needs setup | — |
| 57 | Heb Subs Premium | Community | valid | needs setup | — |
| 58 | Homocentric | Community | NETWORK | unavailable | — |
| 59 | IMDB Catalogs | Community | valid | installed | — |
| 60 | Jimaku Subtitles v2 | Community | valid | needs setup | — |
| 61 | Kitsu Tracker | Community | HTTP 530 | unavailable | — |
| 62 | Ktuvit.me Subtitles | Community | valid | installed | — |
| 63 | LegendasDivx.pt | Community | valid | needs setup | — |
| 64 | Marvel | Community | HTTP 503 | unavailable | — |
| 65 | MDBList | Community | valid | needs setup | — |
| 66 | MSubtitles | Community | NETWORK | unavailable | — |
| 67 | Mubi Catalog | Community | valid | installed | — |
| 68 | MultiSub | Community | valid | needs setup | — |
| 69 | MyShows Lists | Community | valid | needs setup | — |
| 70 | Napisy24 Addon | Community | HTTP 503 | unavailable | — |
| 71 | OmniCatalogs | Community | HTTP 404 | unavailable | — |
| 72 | opensubtitles PRO | Community | valid | needs setup | — |
| 73 | OTTCatalog | Community | valid | installed | — |
| 74 | PGSSUBTITLE V1.0.1 | Community | valid | installed | — |
| 75 | podnapisi subtitles by dexter21767 | Community | valid | needs setup | — |
| 76 | Random Movie | Community | valid | needs setup | — |
| 77 | Rotten Tomatoes | Community | valid | installed | — |
| 78 | RPDB Catalogs | Community | valid | needs setup | — |
| 79 | Simkl Watchlists | Community | valid | needs setup | — |
| 80 | Streaming Catalogs | Community | valid | installed | — |
| 81 | Stremio Community Subtitles | Community | valid | needs setup | — |
| 82 | SubDL Subtitles | Community | valid | needs setup | — |
| 83 | SubMaker \| ElfHosted | Community | valid | needs setup | — |
| 84 | Subs.ro Subtitles | Community | valid | needs setup | — |
| 85 | Subscene subtitles by dexter21767 | Community | valid | needs setup | — |
| 86 | SubSource Subtitles | Community | valid | needs setup | — |
| 87 | Subtis - Beta Pública | Community | valid | installed | — |
| 88 | Subtito | Community | valid | needs setup | — |
| 89 | syncribullet | Community | valid | needs setup | — |
| 90 | The Movie Database Addon | Community | valid | installed | — |
| 91 | Titlovi.com Subtitles (Community addon by CoRySus) | Community | valid | needs setup | — |
| 92 | TMDB Alternatives | Community | valid | installed | — |
| 93 | TMDB Dice | Community | valid | installed | — |
| 94 | Top Seeded Torrent Catalogs | Community | valid | needs setup | — |
| 95 | Torrent Catalogs | Community | valid | installed | — |
| 96 | Trakt Tv | Community | valid | installed | — |
| 97 | Tu Subtitulo | Community | TIMEOUT | unavailable | — |
| 98 | Turkce Altyazi(SonsuzAnime) | Community | CERTIFICATE | unavailable | — |
| 99 | TurkceAltyazi.Org | Community | valid | needs setup | — |
| 100 | UK TV Panel Shows | Community | TIMEOUT | unavailable | — |
| 101 | Watch Calendar | Community | valid | needs setup | — |
| 102 | Wizdom Subtitles | Community | valid | installed | — |
| 103 | X-Files: Mythology Arc | Community | TIMEOUT | unavailable | — |
| 104 | yifysubtitles subtitles by dexter21767 | Community | valid | needs setup | — |
| 105 | КЮЧЕК | Community | valid | installed | — |
| 106 | כתוביות בעברית | Community | NETWORK | unavailable | — |
| 107 | SubDL Official Subtitles | Community | valid | needs setup | — |
| 108 | Filmora | Earlier Nuvio list | HTTP 402 | unavailable | — |
| 109 | TorrentClaw | Earlier Nuvio list | valid | installed | 0 / 15 / 2 |
| 110 | Watcho | Earlier Nuvio list | valid | installed | 0 / 3 / 0 |
| 111 | Jackettio | Earlier Nuvio list | valid | installed | 1 / 0 / 0 |
| 112 | TorBox addon | Earlier Nuvio list | NETWORK | unavailable | — |
| 113 | Orion | Earlier Nuvio list | valid | needs setup | — |
| 114 | Pipe | Earlier Nuvio list | valid | installed | 0 / 63 / 0 |
| 115 | Nexio Torii | Earlier Nuvio list | HTTP 502 | unavailable | — |
| 116 | HDHub / HDHub4u | Earlier Nuvio list | valid | installed | 0 / 0 / 3 |
| 117 | PenguPlay | Earlier Nuvio list | valid | installed | 1 / 0 / 0 |
| 118 | PlavioHD | Earlier Nuvio list | NETWORK | unavailable | — |
| 119 | CNCVerse Bridge | Earlier Nuvio list | valid | installed | 5 / 0 / 1 |
| 120 | Sootio | Earlier Nuvio list | NETWORK | unavailable | — |
| 121 | WebStreamrMBG | Earlier Nuvio list | valid | installed | TIMEOUT |
| 122 | Showbox | Earlier Nuvio list | valid | installed | 0 / 0 / 1 |
| 123 | 111477 | Earlier Nuvio list | valid | needs setup | — |
| 124 | Streailer | Earlier Nuvio list | NETWORK | unavailable | — |
| 125 | Eclipsia | Earlier Nuvio list | MALFORMED | unavailable | — |
| 126 | AIOMetadata | Earlier Nuvio list | valid | needs setup | — |
| 127 | SubSense | Earlier Nuvio list | valid | needs setup | — |

## Earlier entries without a public endpoint

| Entry | Recorded requirement / classification |
| --- | --- |
| [Stremio-Jackett](https://github.com/tsaridas/jackett-stremio) | requires a configured deployment; self-hosting needs Jackett host/API key and indexers; project also documents a public showcase whose availability was not tested |
| [Debridio](https://debridio.com/) | requires paid account and personal manifest |
| [Flix-Streams](https://flixnest.app/flix-streams/) | requires paid account and personal manifest |
| [DuckStreams](https://duck-tools.pages.dev/quackstart/) | AIOStreams configuration tool; requires a generated personal manifest |
| HS+ | unidentified; no verifiable public addon endpoint found for this earlier name |
| [AIOStreams](https://docs.aiostreams.viren070.me/configuration/setup/) | requires a generated personal manifest; root manifest.json is its web app manifest |
| [Xperience](https://xperience-app.com/) | requires account and personal exported manifest |
| [Bingecat](https://bingecat.com/) | requires account and personal manifest |
| [SeaDex](https://github.com/Viren070/AIOStreams/blob/main/packages/core/src/presets/seadex.ts) | AIOStreams built-in anime source; requires configured services |
| 1337x, YTS, EZTV, The Pirate Bay, TamilMV | upstream torrent indexes; not installable addon manifests |

Raw manifests, probe responses, runner output and screenshots are private ignored evidence under `streaming/simulator/.cache/evidence/current-run/`. No accounts, API keys, cookies or configured manifest URLs are included in this report.
