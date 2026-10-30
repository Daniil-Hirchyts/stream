# Torrent/P2P store listings

The app includes these public scraper descriptors in `TorrentAddons`, ahead of
Stremio's directory results. Each entry is a browsing description, not an
installed or accepted manifest. Opening it fetches the provider's current
manifest; Install still requires the user's explicit action. Configurable
providers also offer their setup page and a configured-address entry action.

| Add-on | Manifest | Setup page |
| --- | --- | --- |
| Torrentio | https://torrentio.strem.fun/manifest.json | https://torrentio.strem.fun/configure |
| Comet | https://comet.feels.legal/manifest.json | https://comet.feels.legal/configure |
| Meteor | https://meteorfortheweebs.midnightignite.me/manifest.json | https://meteorfortheweebs.midnightignite.me/configure |
| MediaFusion | https://mediafusionfortheweebs.midnightignite.me/manifest.json | https://mediafusionfortheweebs.midnightignite.me/configure |
| StremThru Torz | https://stremthru.13377001.xyz/stremio/torz/manifest.json | https://stremthru.13377001.xyz/stremio/torz/configure |
| Peerflix | https://peerflix.mov/manifest.json | https://config.peerflix.mov |
| TorrentsDB | https://torrentsdb.com/manifest.json | https://torrentsdb.com/configure |

On 2026-10-22 each manifest endpoint returned valid JSON declaring a stream
resource. These are third-party services; that check establishes discovery
compatibility, not film availability, playback success or uptime. No stream
requests or public torrent transfers were used to validate the listings.
The setup addresses come from the providers' configure pages/manifests;
Peerflix publishes its separate configuration host in its manifest.

StremThru's unconfigured manifest requires setup. Other providers currently
advertise optional configuration. Choose a P2P-capable instance and P2P mode
when direct torrent results are wanted; some public hosts restrict P2P or
require an account for particular modes. The app does not insert credentials,
enable a cloud service or silently rewrite a provider's configuration. Users
may install another compatible instance through Install from address.

The Torrent/P2P filter includes providers known here and other directory
entries whose stream manifests declare `behaviorHints.p2p`. The review also
discloses peer transfers for known providers that omit that optional flag.
Curated entries survive remote directory failures and take precedence over
duplicate manifest IDs in the directory. No provider is contacted merely to
build the curated section, and no provider is installed by browsing it.
The screen reuses fetched lists for up to a minute when switching filters;
explicit refresh or installed-provider changes invalidate that screen cache.

Only repositories using the default public directory include this section.
Custom/fixture directories stay isolated. JVM tests cover descriptor validity,
deduplication, offline merging, recognition of configured addresses and setup
URL encoding. Emulator instrumentation covers filtering, required setup,
configured-manifest review and explicit installation using loopback fixtures.
