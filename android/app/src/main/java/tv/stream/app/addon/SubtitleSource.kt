package tv.stream.app.addon

import org.json.JSONObject
import java.util.ArrayList
import java.util.Collections

class SubtitleSource private constructor(@JvmField val provider: String, @JvmField val id: String, @JvmField val url: String, @JvmField val language: String) {
    companion object {
        @JvmStatic
        fun list(provider: String, response: JSONObject): List<SubtitleSource> {
            val out: MutableList<SubtitleSource> = ArrayList()
            for (o in Json.objects(response, "subtitles")) {
                val url = Json.webUrl(Json.string(o, "url"))
                val language = Json.string(o, "lang")
                if (url != null && language != null) out.add(SubtitleSource(provider, Json.string(o, "id", url), url, language))
            }
            return Collections.unmodifiableList(out)
        }
    }
}
