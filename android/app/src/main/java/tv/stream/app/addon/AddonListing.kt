package tv.stream.app.addon

import org.json.JSONObject
import java.net.URI
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

/**
 * One add-on offered by the store: an `addon_catalog` entry or a built-in add-on. Its manifest is the listing's
 * copy, for browsing only; installation always reviews the add-on's own current manifest first.
 */
data class AddonListing(val url: String, val manifest: AddonManifest,
                   /** The list it came from, such as "Official" or "Community". */
                   val collection: String) {

    override fun toString(): String = "Add-on ${manifest.id} ($collection)"

    fun sources(): Boolean { return manifest.declares(AddonManifest.STREAM) }
    fun torrents(): Boolean { return sources() && (manifest.p2p || TorrentAddons.find(url) != null) }
    fun catalogues(): Boolean { return manifest.declares(AddonManifest.CATALOG) && !manifest.catalogs.isEmpty() || manifest.declares(AddonManifest.META) }
    fun subtitles(): Boolean { return manifest.declares(AddonManifest.SUBTITLES) }
    fun builtin(): Boolean { return url.startsWith(AddonUrl.BUILTIN) }

    companion object {
        /**
         * The entries of an `addon_catalog` response. Skips what this app can't install: other transports, legacy
         * non-manifest addresses, add-ons on this device or a private network (they need a companion server) unless the
         * list itself comes from that same host, adult add-ons and unreadable manifests. Within one list a manifest ID
         * appears once, at its shortest address, so a configured copy that embeds someone's settings isn't offered in
         * place of the plain add-on.
         */
        @Throws(AddonException::class)
        fun parse(collection: String, listAddress: String, response: JSONObject): List<AddonListing> {
            if (response.optJSONArray("addons") == null) throw AddonException(AddonException.Kind.MALFORMED, "Missing add-on entries")
            val found: MutableMap<String, AddonListing> = LinkedHashMap()
            for (o in Json.objects(response, "addons")) {
                val transport = Json.string(o, "transportName", "http")
                val url = Json.string(o, "transportUrl")
                if (transport != "http" || url == null || url.startsWith(AddonUrl.BUILTIN) || !installable(url, listAddress)) continue
                val raw = o.optJSONObject("manifest") ?: continue
                val manifest: AddonManifest
                try { manifest = AddonManifest.parse(raw) } catch (malformed: AddonException) { continue }
                if (manifest.adult) continue
                val previous = found[manifest.id]
                if (previous == null || url.length < previous.url.length) found[manifest.id] = AddonListing(url, manifest, collection)
            }
            return Collections.unmodifiableList(ArrayList(found.values))
        }

        private fun installable(url: String, listAddress: String): Boolean {
            try {
                AddonUrl(url)
                val host = host(url)
                return !local(host) || !listAddress.startsWith(AddonUrl.BUILTIN) && host == host(listAddress)
            } catch (e: AddonException) { return false }
            catch (e: IllegalArgumentException) { return false }
            catch (e: NullPointerException) { return false }
        }
        private fun host(url: String): String { return URI.create(url).host.lowercase(Locale.ROOT) }
        private fun local(host: String): Boolean {
            return host == "localhost" || host.endsWith(".local") || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")
                    || host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*".toRegex()) || host == "0.0.0.0" || host.startsWith("[") || host.startsWith("169.254.")
        }
    }
}
