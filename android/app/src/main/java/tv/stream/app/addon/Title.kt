package tv.stream.app.addon

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/** Metadata exists independently of sources. Episode IDs always come from the provider. */
@ConsistentCopyVisibility
data class Title private constructor(
    val provider: String,
    val id: String,
    val type: String,
    val name: String,
    val description: String,
    val releaseInfo: String,
    val rating: String,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val imdbId: String?,
    val genres: List<String>,
    val cast: List<String>,
    val directors: List<String>,
    val episodes: List<Episode>,
    private val cachedJson: String,
) {
    override fun toString(): String = "Title metadata"

    @ConsistentCopyVisibility
    data class Episode private constructor(
        val id: String,
        val name: String,
        val released: String,
        val overview: String,
        val season: Int,
        val number: Int,
        val thumbnail: String?,
        val streams: List<MediaSource>,
    ) {
        override fun toString(): String = "Episode metadata"

        constructor(provider: String, o: JSONObject) : this(
            id = Json.string(o, "id", ""),
            name = Json.text(o, "title", Json.text(o, "name", Json.string(o, "id", ""))),
            released = Json.string(o, "released", ""),
            overview = Json.text(o, "overview", ""),
            season = integer(o, "season"),
            number = integer(o, "episode"),
            thumbnail = Json.webUrl(Json.string(o, "thumbnail")),
            streams = MediaSource.list(provider, o),
        )
    }

    /** Enrichment retains the catalogue/library identity; inline streams keep their actual source provider. */
    fun withProvider(provider: String): Title = if (this.provider == provider) this else copy(provider = provider)

    /** Provider scoping prevents collisions; common IMDb IDs alone may deduplicate discovery results. */
    fun key(): String = JSONArray().put(provider).put(type).put(id).toString()
    fun discoveryKey(): String = if (imdbId == null) key() else "$type:imdb:$imdbId"
    fun cacheJson(): String = cachedJson

    companion object {
        private val IMDB_ID = Regex("tt[0-9]+")
        private val OPEN_RELEASE = Regex("^(\\d{4})\\s*[-–—]\\s*(?i:present)?$")
        private val CLOSED_RELEASE = Regex("^(\\d{4})\\s*[-–—]\\s*(\\d{4})$")

        /** Render incomplete catalogue ranges as a year for films, or an explicit ongoing series. */
        fun displayReleaseInfo(type: String, release: String): String = release.trim { it <= ' ' }
            .replaceFirst(OPEN_RELEASE, if (type == "movie") "$1" else "$1 - Present")
            .replaceFirst(CLOSED_RELEASE, "$1 - $2")

        fun parse(provider: String, o: JSONObject?): Title? {
            if (o == null) return null
            val id = Json.string(o, "id") ?: return null
            val type = Json.string(o, "type") ?: return null
            if (Json.string(o, "name") == null) return null
            val external = Json.string(o, "imdb_id") ?: id.takeIf(IMDB_ID::matches)
            val episodes = Collections.unmodifiableList(Json.objects(o, "videos")
                .filter { Json.string(it, "id") != null }.map { Episode(provider, it) })
            // Persist only metadata. Embedded playable URLs/headers must be resolved again after resume.
            val safe = JSONObject()
            for (key in arrayOf("id", "type", "name", "description", "releaseInfo", "imdbRating",
                "poster", "background", "logo", "imdb_id", "genres", "cast", "director")) {
                if (o.has(key)) safe.put(key, o.get(key))
            }
            val videos = JSONArray()
            for (episode in episodes) videos.put(JSONObject().put("id", episode.id).put("title", episode.name)
                .put("released", episode.released).put("overview", episode.overview).put("season", episode.season)
                .put("episode", episode.number).put("thumbnail", episode.thumbnail))
            safe.put("videos", videos)
            return Title(
                provider = provider, id = id, type = type, name = Json.text(o, "name", id),
                description = Json.text(o, "description", ""),
                releaseInfo = displayReleaseInfo(type, Json.text(o, "releaseInfo", "")),
                rating = Json.string(o, "imdbRating", ""), poster = Json.webUrl(Json.string(o, "poster")),
                background = Json.webUrl(Json.string(o, "background")), logo = Json.webUrl(Json.string(o, "logo")),
                imdbId = external?.takeIf(IMDB_ID::matches), genres = Json.texts(o, "genres"),
                cast = Json.texts(o, "cast"), directors = Json.texts(o, "director"), episodes = episodes,
                cachedJson = safe.toString(),
            )
        }

        @Throws(AddonException::class)
        fun catalog(provider: String, response: JSONObject): List<Title> {
            if (response.optJSONArray("metas") == null) throw AddonException(AddonException.Kind.MALFORMED, "Missing catalogue entries")
            return Collections.unmodifiableList(Json.objects(response, "metas").mapNotNull { parse(provider, it) })
        }

        private fun integer(o: JSONObject, key: String): Int = Json.index(o, key)
            .takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: -1
    }
}
