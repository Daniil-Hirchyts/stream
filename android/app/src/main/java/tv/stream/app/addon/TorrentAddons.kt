package tv.stream.app.addon

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.util.ArrayList
import java.util.Collections

/**
 * Public scraper listings independent of Stremio's directory. These are browsing descriptions, never accepted
 * manifests: review fetches the selected provider's current manifest before an explicit installation.
 * Endpoints and setup pages are recorded in android/ADDON_STORE.md.
 */
object TorrentAddons {
    const val COLLECTION = "Torrent/P2P"

    class Entry @Throws(JSONException::class, AddonException::class) constructor(
            id: String, name: String, version: String, @JvmField val url: String, @JvmField val configureUrl: String, description: String,
            configurationRequired: Boolean, anime: Boolean) {
        @JvmField val listing: AddonListing

        init {
            val types = JSONArray().put("movie").put("series")
            if (anime) types.put("anime")
            val manifest = JSONObject().put("id", id).put("name", name).put("version", version)
                    .put("description", description).put("types", types).put("resources", JSONArray().put("stream"))
                    .put("catalogs", JSONArray()).put("behaviorHints", JSONObject().put("p2p", true)
                            .put("configurable", true).put("configurationRequired", configurationRequired))
            listing = AddonListing(url, AddonManifest.parse(manifest), COLLECTION)
        }
    }

    private val ENTRIES: List<Entry>
    private val LISTINGS: List<AddonListing>
    init {
        val entries: MutableList<Entry> = ArrayList()
        val listings: MutableList<AddonListing> = ArrayList()
        try {
            entries.add(Entry("com.stremio.torrentio.addon", "Torrentio", "0.0.15",
                    "https://torrentio.strem.fun/manifest.json", "https://torrentio.strem.fun/configure",
                    "Torrent sources from multiple indexes, with optional language, quality and provider settings.", false, true))
            entries.add(Entry("stremio.comet.fast", "Comet", "2.0.0",
                    "https://comet.feels.legal/manifest.json", "https://comet.feels.legal/configure",
                    "Torrent and debrid source search. Available sources and P2P support depend on the chosen instance and settings.", false, true))
            entries.add(Entry("community.meteor", "Meteor", "1.0.0",
                    "https://meteorfortheweebs.midnightignite.me/manifest.json", "https://meteorfortheweebs.midnightignite.me/configure",
                    "Torrent matching for films, series and anime. Configure the provider to choose its sources and playback mode.", false, true))
            entries.add(Entry("stremio.addons.mediafusion|midnight", "MediaFusion", "6.1.6",
                    "https://mediafusionfortheweebs.midnightignite.me/manifest.json", "https://mediafusionfortheweebs.midnightignite.me/configure",
                    "Torrent and debrid sources with optional catalogues. Choose P2P in setup for direct torrent playback.", false, false))
            entries.add(Entry("xyz.13377001.stremthru.torz", "StremThru Torz", "0.105.3",
                    "https://stremthru.13377001.xyz/stremio/torz/manifest.json", "https://stremthru.13377001.xyz/stremio/torz/configure",
                    "Sources from a crowdsourced torrent index. Setup is required; choose a supported P2P or cloud playback mode.", true, true))
            entries.add(Entry("com.keopps.peerflix", "Peerflix", "2.7.4",
                    "https://peerflix.mov/manifest.json", "https://config.peerflix.mov",
                    "Film and series torrent sources with Spanish and English releases, plus optional cloud provider settings.", false, false))
            entries.add(Entry("com.torrentsdb.addon", "TorrentsDB", "1.0.0",
                    "https://torrentsdb.com/manifest.json", "https://torrentsdb.com/configure",
                    "Torrent sources from multiple indexes, with optional language, quality and cloud provider settings.", false, true))
            for (entry in entries) listings.add(entry.listing)
        } catch (invalid: JSONException) { throw ExceptionInInitializerError(invalid) }
        catch (invalid: AddonException) { throw ExceptionInInitializerError(invalid) }
        ENTRIES = Collections.unmodifiableList(entries)
        LISTINGS = Collections.unmodifiableList(listings)
    }

    @JvmStatic fun listings(): List<AddonListing> { return LISTINGS }

    /** Recognises configured manifests on the same HTTPS service, without decoding or exposing their settings. */
    @JvmStatic
    fun find(address: String?): Entry? {
        if (address == null) return null
        try {
            val validated = AddonUrl(address)
            if (validated.builtin()) return null
            val actual = AddonUrl.web(address)
            for (entry in ENTRIES) {
                val base = URI.create(entry.url)
                val prefix = base.rawPath.substring(0, base.rawPath.length - "manifest.json".length)
                if (base.scheme.equals(actual.scheme, ignoreCase = true) && base.host.equals(actual.host, ignoreCase = true)
                        && base.port == actual.port && actual.rawPath.startsWith(prefix)) return entry
            }
        } catch (ignored: AddonException) { /* Unknown or invalid addresses have no curated setup page. */ }
        return null
    }
}
