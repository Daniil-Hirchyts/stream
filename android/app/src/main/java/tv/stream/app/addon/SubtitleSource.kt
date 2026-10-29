package tv.stream.app.addon

import org.json.JSONObject
import java.util.ArrayList
import java.util.Collections

@ConsistentCopyVisibility
data class SubtitleSource private constructor(val provider: String, val id: String, val url: String, val language: String) {
    override fun toString(): String = "Subtitle ($language)"

    companion object {
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
