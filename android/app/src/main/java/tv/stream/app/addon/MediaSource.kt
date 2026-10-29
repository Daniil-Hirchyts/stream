package tv.stream.app.addon

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.regex.Pattern
import tv.stream.torrent.TorrentEngine

/** An advertised candidate, including unsupported candidates so Sources can explain them. */
class MediaSource private constructor(@JvmField val provider: String, o: JSONObject) {
    enum class Kind { HTTP, TORRENT, MAGNET, UNSUPPORTED }
    @JvmField val name: String
    @JvmField val description: String
    @JvmField val reason: String
    @JvmField val kind: Kind
    @JvmField val url: String?
    @JvmField val infoHash: String?
    @JvmField val filename: String?
    @JvmField val videoHash: String?
    @JvmField val bingeGroup: String?
    /** -1 means absent; zero is an explicit first file. Invalid values make the candidate unsupported. */
    @JvmField val fileIndex: Int
    @JvmField val videoSize: Long
    /** Optional advertised availability; -1 is unknown, zero is explicitly no peers. */
    @JvmField val seeders: Int
    @JvmField val discovery: List<String>
    @JvmField val requestHeaders: Map<String, String>
    @JvmField val subtitles: List<SubtitleSource>

    init {
        name = Json.string(o, "name", "Source")
        description = Json.string(o, "description", Json.string(o, "title", ""))
        url = Json.string(o, "url")
        infoHash = Json.string(o, "infoHash")
        val index = Json.index(o, "fileIdx")
        fileIndex = if (index >= 0 && index <= Integer.MAX_VALUE) index.toInt() else -1
        var hints = o.optJSONObject("behaviorHints")
        if (hints == null) hints = JSONObject()
        filename = Json.string(hints, "filename")
        videoHash = Json.string(hints, "videoHash")
        videoSize = Math.max(0L, Json.number(hints, "videoSize", 0))
        val availability = Json.number(o, "seeders", Json.number(o, "seeds", Json.number(o, "peers", -1)))
        seeders = if (availability < 0) -1 else Math.min(Integer.MAX_VALUE.toLong(), availability).toInt()
        bingeGroup = Json.string(hints, "bingeGroup")
        discovery = Json.strings(o, "sources")
        subtitles = SubtitleSource.list(provider, o)
        var unsupported = ""
        var selected = Kind.UNSUPPORTED
        if (o.has("externalUrl")) unsupported = "Opens an external browser"
        else if (o.has("ytId")) unsupported = "Requires a YouTube player"
        else if (o.has("nzbUrl")) unsupported = "Usenet is unsupported"
        else if (o.has("rarUrls") || o.has("zipUrls") || o.has("7zipUrls") || o.has("tarUrls") || o.has("tgzUrls")) unsupported = "Archives are unsupported"
        else if (o.has("drm") || o.has("drmInfo") || hints.has("drm")) unsupported = "Requires DRM"
        else if (index == -2L || index > Integer.MAX_VALUE) unsupported = "Invalid torrent file index"
        else if (infoHash != null) {
            if (infoHash.matches("(?i)[0-9a-f]{40}|[0-9a-f]{64}".toRegex())) selected = Kind.TORRENT
            else unsupported = "Invalid torrent hash"
        } else if (url != null && url.regionMatches(0, "magnet:?", 0, 8, ignoreCase = true)) {
            if (validMagnet(url)) selected = Kind.MAGNET
            else unsupported = "Invalid magnet link"
        } else if (Json.webUrl(url) != null) selected = Kind.HTTP
        else unsupported = "Unsupported source address"
        kind = selected
        reason = unsupported
        val headers: MutableMap<String, String> = LinkedHashMap()
        val proxy = hints.optJSONObject("proxyHeaders")
        val request = proxy?.optJSONObject("request")
        if (kind == Kind.HTTP && request != null) {
            val keys = request.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = Json.string(request, key)
                if (value != null && key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+".toRegex()) && !value.contains("\r") && !value.contains("\n")
                        && !key.matches("(?i)Host|Connection|Content-Length|Transfer-Encoding|Proxy-.*".toRegex())) headers[key] = value
            }
        }
        requestHeaders = Collections.unmodifiableMap(headers)
    }

    fun playable(): Boolean { return kind != Kind.UNSUPPORTED }

    /** Converts only the explicitly selected torrent; never creates a native session while browsing. */
    fun torrentSource(): TorrentEngine.Source {
        val source: TorrentEngine.Source
        if (kind == Kind.TORRENT) source = TorrentEngine.Source.infoHash(infoHash!!)
        else if (kind == Kind.MAGNET) source = TorrentEngine.Source.magnet(url!!)
        else throw IllegalStateException("This source is not a torrent")
        val trackers: MutableList<String> = ArrayList()
        for (hint in discovery) if (hint.startsWith("tracker:")) {
            val address = hint.substring(8)
            try {
                val uri = URI.create(address)
                if (uri.host != null && uri.rawUserInfo == null && ("udp".equals(uri.scheme, ignoreCase = true)
                        || "http".equals(uri.scheme, ignoreCase = true) || "https".equals(uri.scheme, ignoreCase = true))) trackers.add(address)
            } catch (ignored: IllegalArgumentException) { /* optional malformed tracker */ }
        }
        // dht:<hash> hints are retained as discovery metadata. Production DHT is configured on the engine;
        // deterministic tests use localOnly() and explicit peers, which never bootstrap public DHT.
        return source.withTrackers(*trackers.toTypedArray())
    }

    companion object {
        /**
         * Long-running open trackers, announced to when a source names no trackers of its own (many add-ons send only an
         * info hash). DHT alone finds peers too, but a cold DHT often needs longer than a viewer waits.
         */
        @JvmField val PUBLIC_TRACKERS: Array<String> = arrayOf(
                "udp://tracker.opentrackr.org:1337/announce", "udp://open.demonii.com:1337/announce", "udp://open.stealth.si:80/announce",
                "udp://tracker.torrent.eu.org:451/announce", "udp://exodus.desync.com:6969/announce", "udp://explodie.org:6969/announce",
                "http://tracker.opentrackr.org:1337/announce")

        @JvmStatic
        fun list(provider: String, response: JSONObject): List<MediaSource> {
            val out: MutableList<MediaSource> = ArrayList()
            for (o in Json.objects(response, "streams")) {
                if (o.has("url") || o.has("infoHash") || o.has("externalUrl") || o.has("ytId") || o.has("nzbUrl")
                        || o.has("rarUrls") || o.has("zipUrls") || o.has("7zipUrls") || o.has("tarUrls") || o.has("tgzUrls")) out.add(MediaSource(provider, o))
            }
            return Collections.unmodifiableList(out)
        }

        @JvmStatic
        fun validMagnet(address: String): Boolean {
            try {
                val uri = URI.create(address)
                if (!"magnet".equals(uri.scheme, ignoreCase = true) || uri.rawFragment != null) return false
                val query = uri.rawSchemeSpecificPart
                if (!query.startsWith("?")) return false
                for (pair in Pattern.compile("&").split(query.substring(1))) {
                    val parts = Pattern.compile("=").split(pair, 2)
                    if (parts.size == 2 && parts[0] == "xt") {
                        val hash = URLDecoder.decode(parts[1], "UTF-8")
                        if (hash.matches("(?i)urn:btih:([0-9a-f]{40}|[a-z2-7]{32})|urn:btmh:1220[0-9a-f]{64}".toRegex())) return true
                    }
                }
            } catch (ignored: IllegalArgumentException) { /* invalid source */ }
            catch (ignored: java.io.UnsupportedEncodingException) { /* invalid source */ }
            return false
        }
    }
}
