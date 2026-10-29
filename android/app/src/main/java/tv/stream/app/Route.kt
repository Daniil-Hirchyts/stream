package tv.stream.app

import android.os.Parcelable
import org.json.JSONException
import org.json.JSONObject
import java.util.LinkedHashMap
import tv.stream.app.addon.Title

/** One entry of the browse back stack, saved across activity recreation. */
class Route(@JvmField val screen: String) {
    /** The key of the focused view, restored when returning to this route. */
    @JvmField var focus: String = ""
    /** Screen-specific: search text, add-on id or catalogue identity. */
    @JvmField var value: String = ""
    /** Selected provider video (episode) ID on Details and Sources. */
    @JvmField var video: String = ""
    @JvmField var title: Title? = null
    @JvmField var season: Int = -1
    @JvmField var page: Int = 0
    @JvmField var scroll: Parcelable? = null
    @JvmField val childScroll: MutableMap<String, Parcelable?> = LinkedHashMap()
    @JvmField val extras: MutableMap<String, String> = LinkedHashMap()
    /** A screen's fetched data, kept in memory only so re-sorting or returning does not ask providers again. */
    @JvmField var cache: Any? = null
    @JvmField var cachedAt: Long = 0

    @Throws(JSONException::class)
    fun save(): JSONObject {
        val data = JSONObject().put("screen", screen).put("value", value).put("video", video).put("season", season)
                .put("page", page).put("focus", focus).put("extras", JSONObject(extras as Map<*, *>))
        val title = title
        if (title != null) data.put("title", JSONObject().put("provider", title.provider).put("id", title.id).put("type", title.type).put("name", title.name))
        return data
    }

    companion object {
        @JvmStatic fun of(screen: String, title: Title?, video: String?): Route {
            val route = Route(screen); route.title = title; route.video = video ?: ""; return route
        }

        @JvmStatic @Throws(JSONException::class)
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
