package tv.stream.app

import android.content.Context
import android.os.Build
import androidx.media3.common.PlaybackException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.ArrayList
import java.util.Collections
import java.util.UUID
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject
import tv.stream.app.addon.AddonException
import tv.stream.torrent.TorrentException

/** A frozen, offline report. Never contains playable/configured addresses, headers or private file paths. */
class DiagnosticReport private constructor(@JvmField val id: String, @JvmField val text: String) {
    @JvmField val pages: List<String>

    init {
        val chunks = split(text)
        val numbered: MutableList<String> = ArrayList()
        for (i in 0 until chunks.size) numbered.add("Stream error " + id + " — " + (i + 1) + "/" + chunks.size
                + "\nJoin the numbered parts for the complete JSON report.\n" + chunks[i])
        pages = Collections.unmodifiableList(numbered)
    }

    companion object {
        // Small symbols are easier to photograph from a sofa. Every byte is retained across numbered pages.
        const val PAGE_BYTES = 850
        private val ADDRESS: Pattern = Pattern.compile("(?i)\\b(?:[a-z][a-z0-9+.-]*://|magnet:)[^\\s<>\"']+")
        private val PRIVATE_PATH: Pattern = Pattern.compile("(?i)(?<![\\w.])(?:/[\\w.%+~-]+){2,}[^\\s<>\"']*|[A-Z]:\\\\[^\\s<>\"']+")
        private val SECRET: Pattern = Pattern.compile("(?i)\\b(?:authorization|cookie|set-cookie)[\"']?\\s*[:=]\\s*[^\\r\\n]+|\\bBearer\\s+\\S+|\\b[\\w-]*(?:token|secret|password|api[_-]?key)[\\w-]*[\"']?\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)")

        @JvmStatic
        fun capture(context: Context, stage: String?, heading: String?, message: String?, code: String?,
                    cause: Throwable?, state: JSONObject?): DiagnosticReport {
            val id = UUID.randomUUID().toString().substring(0, 8)
            try {
                val report = JSONObject().put("report", id).put("schema", 1).put("timeUtc", Instant.now().toString())
                        .put("app", context.packageName).put("version", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
                        .put("device", Build.MANUFACTURER + " " + Build.MODEL).put("androidApi", Build.VERSION.SDK_INT)
                        .put("abis", java.lang.String.join(",", *Build.SUPPORTED_ABIS)).put("stage", stage)
                        .put("error", JSONObject().put("code", code).put("title", heading).put("message", message))
                        .put("causes", exception(cause)).put("state", state ?: JSONObject())
                // Redact recursively, before serialization; escaped JSON must not defeat address/path redaction.
                return DiagnosticReport(id, (scrub(report) as JSONObject).toString(2))
            } catch (impossible: org.json.JSONException) { throw IllegalStateException(impossible) }
        }

        @JvmStatic
        fun exception(cause: Throwable?): JSONArray {
            var cause = cause
            val causes = JSONArray()
            val seen: MutableSet<Throwable> = Collections.newSetFromMap(java.util.IdentityHashMap())
            while (cause != null && seen.add(cause) && causes.length() < 12) {
                try {
                    val item = JSONObject().put("type", cause.javaClass.name).put("message", safe(cause.message))
                    if (cause is AddonException) item.put("kind", cause.kind.name).put("httpStatus", cause.httpStatus)
                    if (cause is TorrentException) item.put("torrentKind", cause.kind)
                    if (cause is PlaybackException) item.put("playerCode", cause.errorCodeName)
                    val stack = JSONArray()
                    val frames = cause.stackTrace
                    for (i in 0 until Math.min(frames.size, 8)) stack.put(safe(frames[i].toString()))
                    item.put("stack", stack)
                    if (frames.size > 8) item.put("omittedFrames", frames.size - 8)
                    causes.put(item)
                } catch (impossible: org.json.JSONException) { throw IllegalStateException(impossible) }
                cause = cause.cause
            }
            if (cause != null) causes.put("Additional or cyclic causes omitted")
            return causes
        }

        @JvmStatic
        fun safe(value: String?): String {
            if (value == null) return ""
            return SECRET.matcher(PRIVATE_PATH.matcher(ADDRESS.matcher(value).replaceAll("[address redacted]")).replaceAll("[path redacted]"))
                    .replaceAll("[credential redacted]")
        }

        @Throws(org.json.JSONException::class)
        private fun scrub(value: Any?): Any? {
            if (value is JSONObject) {
                val clean = JSONObject()
                val keys = value.keys()
                while (keys.hasNext()) { val key = keys.next(); clean.put(key, scrub(value.get(key))) }
                return clean
            }
            if (value is JSONArray) {
                val clean = JSONArray()
                for (i in 0 until value.length()) clean.put(scrub(value.get(i)))
                return clean
            }
            return if (value is String) safe(value) else value
        }

        private fun split(text: String): List<String> {
            val result: MutableList<String> = ArrayList()
            var start = 0
            var bytes = 0
            var at = 0
            while (at < text.length) {
                val end = at + Character.charCount(text.codePointAt(at))
                val size = text.substring(at, end).toByteArray(StandardCharsets.UTF_8).size
                if (bytes + size > PAGE_BYTES) { result.add(text.substring(start, at)); start = at; bytes = 0 }
                bytes += size; at = end
            }
            result.add(text.substring(start))
            return result
        }
    }
}
