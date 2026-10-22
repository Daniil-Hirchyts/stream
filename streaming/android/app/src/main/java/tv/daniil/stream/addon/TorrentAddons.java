package tv.daniil.stream.addon;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Public scraper listings independent of Stremio's directory. These are browsing descriptions, never accepted
 * manifests: review fetches the selected provider's current manifest before an explicit installation.
 * Endpoints and setup pages are recorded in streaming/android/ADDON_STORE.md.
 */
public final class TorrentAddons {
    public static final String COLLECTION = "Torrent/P2P";

    public static final class Entry {
        public final String url, configureUrl;
        public final AddonListing listing;

        private Entry(String id, String name, String version, String url, String configureUrl, String description,
                      boolean configurationRequired, boolean anime) throws JSONException, AddonException {
            this.url = url; this.configureUrl = configureUrl;
            JSONArray types = new JSONArray().put("movie").put("series");
            if (anime) types.put("anime");
            JSONObject manifest = new JSONObject().put("id", id).put("name", name).put("version", version)
                    .put("description", description).put("types", types).put("resources", new JSONArray().put("stream"))
                    .put("catalogs", new JSONArray()).put("behaviorHints", new JSONObject().put("p2p", true)
                            .put("configurable", true).put("configurationRequired", configurationRequired));
            listing = new AddonListing(url, AddonManifest.parse(manifest), COLLECTION);
        }
    }

    private static final List<Entry> ENTRIES;
    private static final List<AddonListing> LISTINGS;
    static {
        try {
            List<Entry> entries = new ArrayList<>();
            entries.add(new Entry("com.stremio.torrentio.addon", "Torrentio", "0.0.15",
                    "https://torrentio.strem.fun/manifest.json", "https://torrentio.strem.fun/configure",
                    "Torrent sources from multiple indexes, with optional language, quality and provider settings.", false, true));
            entries.add(new Entry("stremio.comet.fast", "Comet", "2.0.0",
                    "https://comet.feels.legal/manifest.json", "https://comet.feels.legal/configure",
                    "Torrent and debrid source search. Available sources and P2P support depend on the chosen instance and settings.", false, true));
            entries.add(new Entry("community.meteor", "Meteor", "1.0.0",
                    "https://meteorfortheweebs.midnightignite.me/manifest.json", "https://meteorfortheweebs.midnightignite.me/configure",
                    "Torrent matching for films, series and anime. Configure the provider to choose its sources and playback mode.", false, true));
            entries.add(new Entry("stremio.addons.mediafusion|midnight", "MediaFusion", "6.1.6",
                    "https://mediafusionfortheweebs.midnightignite.me/manifest.json", "https://mediafusionfortheweebs.midnightignite.me/configure",
                    "Torrent and debrid sources with optional catalogues. Choose P2P in setup for direct torrent playback.", false, false));
            entries.add(new Entry("xyz.13377001.stremthru.torz", "StremThru Torz", "0.105.3",
                    "https://stremthru.13377001.xyz/stremio/torz/manifest.json", "https://stremthru.13377001.xyz/stremio/torz/configure",
                    "Sources from a crowdsourced torrent index. Setup is required; choose a supported P2P or cloud playback mode.", true, true));
            entries.add(new Entry("com.keopps.peerflix", "Peerflix", "2.7.4",
                    "https://peerflix.mov/manifest.json", "https://config.peerflix.mov",
                    "Film and series torrent sources with Spanish and English releases, plus optional cloud provider settings.", false, false));
            entries.add(new Entry("com.torrentsdb.addon", "TorrentsDB", "1.0.0",
                    "https://torrentsdb.com/manifest.json", "https://torrentsdb.com/configure",
                    "Torrent sources from multiple indexes, with optional language, quality and cloud provider settings.", false, true));
            ENTRIES = Collections.unmodifiableList(entries);
            List<AddonListing> listings = new ArrayList<>();
            for (Entry entry : entries) listings.add(entry.listing);
            LISTINGS = Collections.unmodifiableList(listings);
        } catch (JSONException | AddonException invalid) { throw new ExceptionInInitializerError(invalid); }
    }

    private TorrentAddons() {}
    public static List<AddonListing> listings() { return LISTINGS; }

    /** Recognises configured manifests on the same HTTPS service, without decoding or exposing their settings. */
    public static Entry find(String address) {
        if (address == null) return null;
        try {
            AddonUrl validated = new AddonUrl(address);
            if (validated.builtin()) return null;
            URI actual = AddonUrl.web(address);
            for (Entry entry : ENTRIES) {
                URI base = URI.create(entry.url);
                String prefix = base.getRawPath().substring(0, base.getRawPath().length() - "manifest.json".length());
                if (base.getScheme().equalsIgnoreCase(actual.getScheme()) && base.getHost().equalsIgnoreCase(actual.getHost())
                        && base.getPort() == actual.getPort() && actual.getRawPath().startsWith(prefix)) return entry;
            }
        } catch (AddonException ignored) { /* Unknown or invalid addresses have no curated setup page. */ }
        return null;
    }
}
