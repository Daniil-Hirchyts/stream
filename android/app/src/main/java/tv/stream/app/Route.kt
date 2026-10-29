package tv.stream.app

import android.os.Parcelable
import org.json.JSONException
import org.json.JSONObject
import tv.stream.app.addon.Title

/** One entry of the browse back stack, saved across activity recreation. */
class Route(val screen: String) {
    /** The key of the focused view, restored when returning to this route. */
    var focus: String = ""
    /** Screen-specific: search text, add-on id or catalogue identity. */
    var value: String = ""
    /** Selected provider video (episode) ID on Details and Sources. */
    var video: String = ""
    var title: Title? = null
    var season: Int = -1
    var page: Int = 0
    var scroll: Parcelable? = null
    val childScroll = linkedMapOf<String, Parcelable?>()
    val extras = linkedMapOf<String, String>()
    /** A screen's fetched data, kept in memory only so re-sorting or returning does not ask providers again. */
    var cache: Any? = null
    var cachedAt: Long = 0

    @Throws(JSONException::class)
    fun save(): JSONObject {
        val data = JSONObject().put("screen", screen).put("value", value).put("video", video).put("season", season)
                .put("page", page).put("focus", focus).put("extras", JSONObject(extras as Map<*, *>))
        val title = title
        if (title != null) data.put("title", JSONObject().put("provider", title.provider).put("id", title.id).put("type", title.type).put("name", title.name))
        return data
    }

    companion object {
        fun of(screen: String, title: Title?, video: String?): Route {
            return Route(screen).apply { this.title = title; this.video = video.orEmpty() }
        }

        @Throws(JSONException::class)
        fun restore(data: JSONObject): Route {
            val route = Route(data.getString("screen"))
            route.value = data.optString("value"); route.video = data.optString("video"); route.focus = data.optString("focus")
            route.season = data.optInt("season", -1); route.page = data.optInt("page")
            val extras = data.optJSONObject("extras")
            if (extras != null) { val keys = extras.keys(); while (keys.hasNext()) { val key = keys.next(); route.extras[key] = extras.getString(key) } }
            val title = data.optJSONObject("title")
            if (title != null) route.title = Title.parse(title.getString("provider"), title)
            return route
        }
    }
}
