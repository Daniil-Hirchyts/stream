package tv.stream.app.addon

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.ArrayList
import java.util.Collections

/** Metadata exists independently of sources. Episode IDs always come from the provider. */
class Title {
    class Episode(provider: String, o: JSONObject) {
        @JvmField val id: String = Json.string(o, "id", "")
        @JvmField val name: String = Json.text(o, "title", Json.text(o, "name", id))
        @JvmField val released: String = Json.string(o, "released", "")
        @JvmField val overview: String = Json.text(o, "overview", "")
        @JvmField val season: Int = integer(o, "season")
        @JvmField val number: Int = integer(o, "episode")
        @JvmField val thumbnail: String? = Json.webUrl(Json.string(o, "thumbnail"))
        @JvmField val streams: List<MediaSource> = MediaSource.list(provider, o)
    }

    @JvmField val provider: String
    @JvmField val id: String
    @JvmField val type: String
    @JvmField val name: String
    @JvmField val description: String
    @JvmField val releaseInfo: String
    @JvmField val rating: String
    @JvmField val poster: String?
    @JvmField val background: String?
    @JvmField val logo: String?
    @JvmField val imdbId: String?
    @JvmField val genres: List<String>
    @JvmField val cast: List<String>
    @JvmField val directors: List<String>
    @JvmField val episodes: List<Episode>
    private val cachedJson: String

    private constructor(provider: String, o: JSONObject) {
        this.provider = provider
        id = Json.string(o, "id", "")
        type = Json.string(o, "type", "")
        name = Json.text(o, "name", id)
        description = Json.text(o, "description", "")
        releaseInfo = displayReleaseInfo(type, Json.text(o, "releaseInfo", ""))
        rating = Json.string(o, "imdbRating", "")
        poster = Json.webUrl(Json.string(o, "poster"))
        background = Json.webUrl(Json.string(o, "background"))
        logo = Json.webUrl(Json.string(o, "logo"))
        var external = Json.string(o, "imdb_id")
        if (external == null && id.matches("tt[0-9]+".toRegex())) external = id
        imdbId = if (external != null && external.matches("tt[0-9]+".toRegex())) external else null
        genres = Json.texts(o, "genres")
        cast = Json.texts(o, "cast")
        directors = Json.texts(o, "director")
        val videos: MutableList<Episode> = ArrayList()
        for (video in Json.objects(o, "videos")) {
            if (Json.string(video, "id") != null) videos.add(Episode(provider, video))
        }
        episodes = Collections.unmodifiableList(videos)
        // Persist only metadata. Embedded playable URLs/headers must be resolved again after resume.
        val safe = JSONObject()
        try {
            for (key in arrayOf("id", "type", "name", "description", "releaseInfo", "imdbRating",
                    "poster", "background", "logo", "imdb_id", "genres", "cast", "director")) {
                if (o.has(key)) safe.put(key, o.get(key))
            }
            val savedVideos = JSONArray()
            for (e in videos) savedVideos.put(JSONObject().put("id", e.id).put("title", e.name)
                    .put("released", e.released).put("overview", e.overview).put("season", e.season)
                    .put("episode", e.number).put("thumbnail", e.thumbnail))
            safe.put("videos", savedVideos)
        } catch (impossible: JSONException) { throw IllegalStateException(impossible) }
        cachedJson = safe.toString()
    }

    private constructor(metadata: Title, provider: String) {
        this.provider = provider; id = metadata.id; type = metadata.type; name = metadata.name
        description = metadata.description; releaseInfo = metadata.releaseInfo; rating = metadata.rating
        poster = metadata.poster; background = metadata.background; logo = metadata.logo; imdbId = metadata.imdbId
        genres = metadata.genres; cast = metadata.cast; directors = metadata.directors
        episodes = metadata.episodes; cachedJson = metadata.cachedJson
    }
    /** Enrichment retains the catalogue/library identity; inline streams keep their actual source provider. */
    fun withProvider(provider: String): Title { return if (this.provider == provider) this else Title(this, provider) }

    /** Provider scoping prevents collisions; common IMDb IDs alone may deduplicate discovery results. */
    fun key(): String { return JSONArray().put(provider).put(type).put(id).toString() }
    fun discoveryKey(): String { return if (imdbId == null) key() else "$type:imdb:$imdbId" }
    fun cacheJson(): String { return cachedJson }

    companion object {
        /** Render incomplete catalogue ranges as a year for films, or an explicit ongoing series. */
        @JvmStatic
        fun displayReleaseInfo(type: String, release: String): String {
            var text = release.trim { it <= ' ' }
            text = text.replaceFirst("^(\\d{4})\\s*[-–—]\\s*(?i:present)?$".toRegex(), if (type == "movie") "$1" else "$1 - Present")
            return text.replaceFirst("^(\\d{4})\\s*[-–—]\\s*(\\d{4})$".toRegex(), "$1 - $2")
        }

        @JvmStatic
        fun parse(provider: String, o: JSONObject?): Title? {
            if (o == null || Json.string(o, "id") == null || Json.string(o, "type") == null
                    || Json.string(o, "name") == null) return null
            return Title(provider, o)
        }

        @JvmStatic
        @Throws(AddonException::class)
        fun catalog(provider: String, response: JSONObject): List<Title> {
            if (response.optJSONArray("metas") == null) throw AddonException(AddonException.Kind.MALFORMED, "Missing catalogue entries")
            val titles: MutableList<Title> = ArrayList()
            for (o in Json.objects(response, "metas")) {
                val t = parse(provider, o)
                if (t != null) titles.add(t)
            }
            return Collections.unmodifiableList(titles)
        }

        private fun integer(o: JSONObject, key: String): Int {
            val n = Json.index(o, key)
            return if (n >= 0 && n <= Integer.MAX_VALUE) n.toInt() else -1
        }
    }
}
