package tv.stream.app.addon

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Add-ons answered inside the app, for services that publish an open API but no add-on. They speak the same protocol
 * as remote add-ons ([AddonClient] routes `stream://` addresses here), so they're reviewed, installed,
 * ordered and removed like any other, and nothing is installed until the user accepts it.
 *
 * Internet Archive: the Archive's Feature Films collection, which holds public-domain and freely licensed films.
 * Items name their IMDb page, so Cinemeta titles find their streams; files play directly over HTTPS from archive.org.
 */
object BuiltinAddons {
    const val INTERNET_ARCHIVE = AddonUrl.BUILTIN + "internet-archive/manifest.json"
    private const val ARCHIVE = "https://archive.org"
    private const val WIKIDATA = "https://query.wikidata.org/sparql"
    /** Wikimedia asks API clients to identify themselves, and refuses the add-on client's usual Accept list. In this order. */
    private val WIKIDATA_HEADERS: MutableMap<String, String> = java.util.LinkedHashMap()
    init {
        WIKIDATA_HEADERS["User-Agent"] = "Stream/0.1 (Android TV; https://github.com/Stream-Hirchyts/stream)"
        WIKIDATA_HEADERS["Accept"] = "application/sparql-results+json"
    }
    private const val PAGE = 20
    private const val ITEMS_PER_FILM = 2
    private const val ITEMS_CHECKED = 4
    private const val FILES_PER_ITEM = 6
    /** Archive masters can exceed 15 Mbit/s; a TV on Wi-Fi streams the lighter copies more reliably. */
    private const val MAX_MEGABITS = 8.0
    private val IMDB: Pattern = Pattern.compile("tt[0-9]{7,10}")
    private val RESOURCE: Pattern = Pattern.compile("stream://([a-z0-9-]+)/(catalog|stream)/([^/]+)/([^/]+?)(?:/([^/]*))?\\.json")
    private const val ARCHIVE_MANIFEST = ("{\"id\":\"tv.stream.internet-archive\",\"version\":\"1.0.0\",\"name\":\"Internet Archive\","
            + "\"description\":\"Public-domain and freely licensed feature films from the Internet Archive, streamed directly from archive.org. "
            + "Films are matched by their IMDb identifier through archive.org and Wikidata, so Cinemeta titles play when the Archive holds them.\","
            + "\"types\":[\"movie\"],\"idPrefixes\":[\"tt\"],"
            + "\"resources\":[\"catalog\",{\"name\":\"stream\",\"types\":[\"movie\"],\"idPrefixes\":[\"tt\"]}],"
            + "\"catalogs\":[{\"type\":\"movie\",\"id\":\"feature-films\",\"name\":\"Public Domain Films\",\"extra\":[{\"name\":\"search\"},{\"name\":\"skip\"}]}]}")

    /** The store's built-in section. */
    fun listings(): List<AddonListing> {
        try { return Collections.singletonList(AddonListing(INTERNET_ARCHIVE, AddonManifest.parse(JSONObject(ARCHIVE_MANIFEST)), "Built in")) }
        catch (impossible: JSONException) { throw IllegalStateException(impossible) }
        catch (impossible: AddonException) { throw IllegalStateException(impossible) }
    }

    @Throws(AddonException::class)
    fun answer(address: String, client: AddonClient, scope: Cancellation, deadline: Long): JSONObject {
        try {
            if (address == INTERNET_ARCHIVE) return JSONObject(ARCHIVE_MANIFEST)
            val m = RESOURCE.matcher(address)
            if (!m.matches() || m.group(1) != "internet-archive") throw AddonException(AddonException.Kind.HTTP, "Unknown built-in resource", 404, null)
            val resource = m.group(2)
            val type = decode(m.group(3))
            val id = decode(m.group(4))
            var search = ""
            var skip = 0
            if (m.group(5) != null) for (pair in Pattern.compile("&").split(m.group(5))) {
                val kv = Pattern.compile("=").split(pair, 2)
                if (kv.size < 2) continue
                if (decode(kv[0]) == "search") search = decode(kv[1])
                else if (decode(kv[0]) == "skip") try { skip = Math.max(0, Integer.parseInt(decode(kv[1]))) } catch (ignored: NumberFormatException) { /* first page */ }
            }
            if (type != "movie") throw AddonException(AddonException.Kind.HTTP, "Unsupported type", 404, null)
            if (resource == "catalog" && id == "feature-films") return catalog(client, scope, deadline, search, skip)
            if (resource == "stream" && IMDB.matcher(id).matches()) return streams(client, scope, deadline, id)
            throw AddonException(AddonException.Kind.HTTP, "Unknown built-in resource", 404, null)
        } catch (e: JSONException) { throw AddonException(AddonException.Kind.MALFORMED, "The Internet Archive returned data this app can't read") }
    }

    @Throws(AddonException::class, JSONException::class)
    private fun catalog(client: AddonClient, scope: Cancellation, deadline: Long, search: String, skip: Int): JSONObject {
        var query = "collection:feature_films AND mediatype:movies AND imdb:*"
        // Every word must appear; lower case keeps the user's "and"/"or" from becoming operators.
        val words = search.lowercase(Locale.ROOT).replace("[^\\p{L}\\p{N} ]".toRegex(), " ").trim { it <= ' ' }.replace("\\s+".toRegex(), " AND ")
        if (!words.isEmpty()) query += " AND title:($words)"
        val docs = search(client, scope, deadline, query, PAGE, skip / PAGE + 1, "identifier", "title", "year", "imdb", "description")
        val metas = JSONArray()
        val seen: MutableSet<String> = LinkedHashSet()
        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i)
            val imdb = if (doc == null) null else imdb(doc.opt("imdb"))
            val identifier = if (doc == null) null else Json.string(doc, "identifier")
            val title = if (doc == null) null else text(doc.opt("title"))
            if (imdb == null || identifier == null || title == null || !seen.add(imdb)) continue
            val meta = JSONObject().put("id", imdb).put("type", "movie").put("name", title)
                    .put("poster", ARCHIVE + "/services/img/" + AddonUrl.encode(identifier)).put("imdb_id", imdb)
            val year = text(doc!!.opt("year"))
            val description = text(doc.opt("description"))
            if (year != null) meta.put("releaseInfo", year)
            if (description != null) meta.put("description", plain(description))
            metas.put(meta)
        }
        return JSONObject().put("metas", metas)
    }

    /**
     * Each matching item's directly playable files. Items come from the Feature Films collection's own IMDb links, then
     * from Wikidata, which links films (IMDb ID, P345) to their Archive items (P724).
     */
    @Throws(AddonException::class, JSONException::class)
    private fun streams(client: AddonClient, scope: Cancellation, deadline: Long, imdb: String): JSONObject {
        val identifiers: MutableSet<String> = LinkedHashSet()
        var failure: AddonException? = null
        try {
            val docs = search(client, scope, deadline, "collection:feature_films AND mediatype:movies AND imdb:$imdb", 5, 1, "identifier", "imdb")
            for (i in 0 until docs.length()) {
                val doc = docs.optJSONObject(i)
                val identifier = if (doc == null) null else Json.string(doc, "identifier")
                // The search matches words; the item must name exactly this film.
                if (identifier != null && imdb == imdb(doc!!.opt("imdb"))) identifiers.add(identifier)
            }
        } catch (e: AddonException) { if (e.kind == AddonException.Kind.CANCELLED) throw e; failure = e }
        try { identifiers.addAll(wikidata(client, scope, deadline, imdb)) }
        catch (e: AddonException) { if (e.kind == AddonException.Kind.CANCELLED) throw e; failure = e }
        if (identifiers.isEmpty() && failure != null) throw failure
        // Read a few items, then prefer complete presentations: a film split into parts, or trailers and extras,
        // rank below an item holding the whole film.
        val found: MutableList<Item> = ArrayList()
        var checked = 0
        for (identifier in identifiers) {
            if (checked++ >= ITEMS_CHECKED) break
            val item: JSONObject = try { client.json(ARCHIVE + "/metadata/" + AddonUrl.encode(identifier), scope, deadline) }
            catch (e: AddonException) { if (e.kind == AddonException.Kind.CANCELLED) throw e; continue }
            val metadata = item.optJSONObject("metadata")
            if (metadata == null || item.optBoolean("is_dark") || metadata.has("access-restricted-item") || "movies" != Json.string(metadata, "mediatype")) continue
            val candidate = Item(identifier, metadata, item)
            if (!candidate.files.isEmpty()) found.add(candidate)
        }
        found.sortWith { a, b -> java.lang.Double.compare(b.runtime, a.runtime) }
        val streams = JSONArray()
        for (i in 0 until Math.min(ITEMS_PER_FILM, found.size)) {
            val item = found[i]
            if (i > 0 && item.runtime < found[0].runtime / 2) break
            for (f in item.files) streams.put(item.stream(f))
        }
        return JSONObject().put("streams", streams)
    }

    /** One Archive item's playable files: full-length ones only, largest picture first, without heavy masters. */
    class Item(val identifier: String, metadata: JSONObject, item: JSONObject) {
        val label: String
        val files: MutableList<JSONObject> = ArrayList()
        /** The longest file's running time in seconds, or 0 when the Archive doesn't say. */
        val runtime: Double

        init {
            val title = text(metadata.opt("title"))
            var year = text(metadata.opt("year"))
            if (year == null) year = text(metadata.opt("date"))
            label = (title ?: identifier) + (if (year == null) "" else " (" + year.substring(0, Math.min(4, year.length)) + ")")
            val videos: MutableList<JSONObject> = ArrayList()
            var longest = 0.0
            for (f in Json.objects(item, "files")) {
                val name = Json.string(f, "name")
                if (name == null || name.contains("..") || !name.lowercase(Locale.ROOT).matches(".*\\.(mp4|m4v|mkv|webm)".toRegex())) continue
                videos.add(f); longest = Math.max(longest, seconds(f))
            }
            runtime = longest
            var light = false
            for (f in videos) light = light or (megabits(f) <= MAX_MEGABITS)
            for (f in videos) {
                // Parts, trailers and extras are much shorter than the whole film.
                if (longest > 0 && seconds(f) > 0 && seconds(f) < longest * 0.8) continue
                if (light && megabits(f) > MAX_MEGABITS) continue
                files.add(f)
            }
            files.sortWith { a, b -> java.lang.Long.compare(Json.number(b, "height", 0), Json.number(a, "height", 0)) }
            while (files.size > FILES_PER_ITEM) files.removeAt(files.size - 1)
        }

        @Throws(JSONException::class)
        fun stream(f: JSONObject): JSONObject {
            val name = Json.string(f, "name")
            val format = Json.string(f, "format", "")
            val height = Json.number(f, "height", 0)
            val width = Json.number(f, "width", 0)
            val size = Json.number(f, "size", 0)
            val path = StringBuilder()
            for (segment in Pattern.compile("/").split(name!!)) path.append('/').append(AddonUrl.encode(segment))
            // Archive derivatives labelled h.264 are re-encoded by the Archive; other uploads keep their own codec.
            val codec = if (format.lowercase(Locale.ROOT).contains("h.264") || format == "512Kb MPEG4") "H.264" else format
            val file = name.substring(name.lastIndexOf('/') + 1)
            val details = label + "\n" + file + " · " + (if (width > 0 && height > 0) "${width}×${height} · " else "") + codec + (if (size > 0) " · " + (size shr 20) + " MB" else "")
            val hints = JSONObject().put("filename", file).put("bingeGroup", "internet-archive")
            if (size > 0) hints.put("videoSize", size)
            return JSONObject().put("name", "Internet Archive" + (if (height > 0) " " + height + "p" else ""))
                    .put("description", details).put("url", ARCHIVE + "/download/" + AddonUrl.encode(identifier) + path).put("behaviorHints", hints)
        }

        companion object {
            /** "5796.14" or "1:36:36". */
            fun seconds(f: JSONObject): Double {
                val value = Json.string(f, "length") ?: return 0.0
                try {
                    var total = 0.0
                    for (part in Pattern.compile(":").split(value)) total = total * 60 + java.lang.Double.parseDouble(part)
                    return total
                } catch (e: NumberFormatException) { return 0.0 }
            }
            fun megabits(f: JSONObject): Double {
                val length = seconds(f)
                return if (length > 0) Json.number(f, "size", 0) * 8 / length / 1_000_000 else 0.0
            }
        }
    }

    /** Archive identifiers Wikidata links to the film with this IMDb ID. */
    @Throws(AddonException::class)
    private fun wikidata(client: AddonClient, scope: Cancellation, deadline: Long, imdb: String): List<String> {
        val sparql = "SELECT ?ia WHERE { ?film wdt:P345 \"" + imdb + "\"; wdt:P724 ?ia } LIMIT 10"
        val address = WIKIDATA + "?format=json&query=" + AddonUrl.encode(sparql)
        val response: JSONObject = try { client.json(address, WIKIDATA_HEADERS, scope, deadline) }
        catch (e: AddonException) {
            // Wikimedia briefly refuses bursts of queries; one later attempt usually succeeds.
            if (e.kind != AddonException.Kind.HTTP || !(e.httpStatus == 403 || e.httpStatus == 429 || e.httpStatus == 503)) throw e
            try { Thread.sleep(750) } catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw AddonException(AddonException.Kind.CANCELLED, "Request cancelled") }
            client.json(address, WIKIDATA_HEADERS, scope, deadline)
        }
        val results = response.optJSONObject("results") ?: throw AddonException(AddonException.Kind.MALFORMED, "Wikidata returned data this app can't read")
        val found: MutableList<String> = ArrayList()
        for (binding in Json.objects(results, "bindings")) {
            val ia = binding.optJSONObject("ia")
            val identifier = if (ia == null) null else Json.string(ia, "value")
            if (identifier != null && identifier.matches("[A-Za-z0-9._-]{1,100}".toRegex())) found.add(identifier)
        }
        return found
    }

    @Throws(AddonException::class)
    private fun search(client: AddonClient, scope: Cancellation, deadline: Long, query: String, rows: Int, page: Int, vararg fields: String): JSONArray {
        val url = StringBuilder(ARCHIVE).append("/advancedsearch.php?q=").append(AddonUrl.encode(query))
        for (field in fields) url.append("&fl%5B%5D=").append(field)
        url.append("&sort%5B%5D=downloads+desc&rows=").append(rows).append("&page=").append(page).append("&output=json")
        val response = client.json(url.toString(), scope, deadline).optJSONObject("response")
        val docs = if (response == null) null else response.optJSONArray("docs")
        if (docs == null) throw AddonException(AddonException.Kind.MALFORMED, "The Internet Archive returned data this app can't read")
        return docs
    }

    /** The IMDb ID an item names, from a page address or a bare ID, alone or in a list. */
    fun imdb(value: Any?): String? {
        val text: String? = if (value is JSONArray) value.optString(0, null) else if (value is String) value else null
        if (text == null) return null
        val m: Matcher = IMDB.matcher(text)
        return if (m.find() && (m.end() == text.length || !Character.isDigit(text[m.end()]))) m.group() else null
    }
    private fun text(value: Any?): String? {
        var v = value
        if (v is JSONArray) v = v.opt(0)
        val s = if (v is String) v else if (v is Number) v.toString() else null
        return if (s == null || s.trim { it <= ' ' }.isEmpty()) null else s.trim { it <= ' ' }
    }
    /** Archive descriptions are HTML; details show a few sentences of plain text. */
    fun plain(html: String): String {
        val text = html.replace("(?i)<br\\s*/?>|</p>".toRegex(), "\n").replace("<[^>]*>".toRegex(), "").replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("[ \\t]+".toRegex(), " ").replace("\\s*\\n\\s*".toRegex(), "\n").trim { it <= ' ' }
        return if (text.length > 600) text.substring(0, 600).replace("\\s+\\S*$".toRegex(), "") + "…" else text
    }
    private fun decode(value: String): String {
        try { return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }
        catch (e: java.io.UnsupportedEncodingException) { return value }
        catch (e: IllegalArgumentException) { return value }
    }
}
