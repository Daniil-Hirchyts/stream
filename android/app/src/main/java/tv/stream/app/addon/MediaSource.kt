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
class MediaSource private constructor(val provider: String, o: JSONObject) {
    enum class Kind { HTTP, TORRENT, MAGNET, UNSUPPORTED }
    val name: String
    val description: String
    /** A playable location or the exact reason it cannot be opened. */
    sealed interface Location {
        data class Http(val url: String) : Location {
            override fun toString(): String = "HTTP source"
        }
        data class Torrent(val infoHash: String) : Location {
            override fun toString(): String = "Torrent source"
        }
        data class Magnet(val url: String) : Location {
            override fun toString(): String = "Magnet source"
        }
        data class Unsupported(val reason: String) : Location
    }

    val location: Location
    val kind: Kind get() = when (location) {
        is Location.Http -> Kind.HTTP
        is Location.Torrent -> Kind.TORRENT
        is Location.Magnet -> Kind.MAGNET
        is Location.Unsupported -> Kind.UNSUPPORTED
    }
    val reason: String get() = (location as? Location.Unsupported)?.reason.orEmpty()
    val url: String?
    val infoHash: String?
    val filename: String?
    val videoHash: String?
    val bingeGroup: String?
    /** -1 means absent; zero is an explicit first file. Invalid values make the candidate unsupported. */
    val fileIndex: Int
    val videoSize: Long
    /** Optional advertised availability; -1 is unknown, zero is explicitly no peers. */
    val seeders: Int
    val discovery: List<String>
    val requestHeaders: Map<String, String>
    val subtitles: List<SubtitleSource>

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
        location = when {
            o.has("externalUrl") -> Location.Unsupported("Opens an external browser")
            o.has("ytId") -> Location.Unsupported("Requires a YouTube player")
            o.has("nzbUrl") -> Location.Unsupported("Usenet is unsupported")
            o.has("rarUrls") || o.has("zipUrls") || o.has("7zipUrls") || o.has("tarUrls") || o.has("tgzUrls") -> Location.Unsupported("Archives are unsupported")
            o.has("drm") || o.has("drmInfo") || hints.has("drm") -> Location.Unsupported("Requires DRM")
            index == -2L || index > Int.MAX_VALUE -> Location.Unsupported("Invalid torrent file index")
            infoHash != null -> if (infoHash.matches(INFO_HASH)) Location.Torrent(infoHash)
                else Location.Unsupported("Invalid torrent hash")
            url != null && url.regionMatches(0, "magnet:?", 0, 8, ignoreCase = true) ->
                if (validMagnet(url)) Location.Magnet(url) else Location.Unsupported("Invalid magnet link")
            Json.webUrl(url) != null -> Location.Http(requireNotNull(url))
            else -> Location.Unsupported("Unsupported source address")
        }
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

    fun playable(): Boolean = location !is Location.Unsupported

    /** Converts only the explicitly selected torrent; never creates a native session while browsing. */
    fun torrentSource(): TorrentEngine.Source {
        val source = when (val selected = location) {
            is Location.Torrent -> TorrentEngine.Source.infoHash(selected.infoHash)
            is Location.Magnet -> TorrentEngine.Source.magnet(selected.url)
            else -> throw IllegalStateException("This source is not a torrent")
        }
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
        private val INFO_HASH = Regex("(?i)[0-9a-f]{40}|[0-9a-f]{64}")
        /**
         * Long-running open trackers, announced to when a source names no trackers of its own (many add-ons send only an
         * info hash). DHT alone finds peers too, but a cold DHT often needs longer than a viewer waits.
         */
        val PUBLIC_TRACKERS: Array<String> = arrayOf(
                "udp://tracker.opentrackr.org:1337/announce", "udp://open.demonii.com:1337/announce", "udp://open.stealth.si:80/announce",
                "udp://tracker.torrent.eu.org:451/announce", "udp://exodus.desync.com:6969/announce", "udp://explodie.org:6969/announce",
                "http://tracker.opentrackr.org:1337/announce")

        fun list(provider: String, response: JSONObject): List<MediaSource> {
            val out: MutableList<MediaSource> = ArrayList()
            for (o in Json.objects(response, "streams")) {
                if (o.has("url") || o.has("infoHash") || o.has("externalUrl") || o.has("ytId") || o.has("nzbUrl")
                        || o.has("rarUrls") || o.has("zipUrls") || o.has("7zipUrls") || o.has("tarUrls") || o.has("tgzUrls")) out.add(MediaSource(provider, o))
            }
            return Collections.unmodifiableList(out)
        }

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
