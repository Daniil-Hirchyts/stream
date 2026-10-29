package tv.stream.app.addon

import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.util.TreeMap

/** Builds resource paths without decoding or rebuilding an add-on's configured path. */
class AddonUrl @Throws(AddonException::class) constructor(value: String) {
    @JvmField val manifest: String
    private val base: String
    private val query: String

    init {
        if (value.startsWith(BUILTIN)) {
            if (!value.matches("stream://[a-z0-9-]+/manifest\\.json".toRegex())) throw AddonException(AddonException.Kind.INVALID_URL, "Unknown built-in add-on")
            manifest = value
            base = value.substring(0, value.length - 14)
            query = ""
        } else {
            val uri = web(value)
            val path = uri.rawPath
            if (path == null || !path.endsWith("/manifest.json") || uri.rawFragment != null) {
                throw AddonException(AddonException.Kind.INVALID_URL, "Enter an HTTP(S) manifest.json address")
            }
            // Keep escapes, configuration tokens, and query parameters exactly as supplied.
            manifest = value
            base = uri.scheme + "://" + uri.rawAuthority + path.substring(0, path.length - 14)
            query = if (uri.rawQuery == null) "" else "?" + uri.rawQuery
        }
    }

    fun resource(resource: String, type: String, id: String, extras: Map<String, String>): String {
        if (!resource.matches("catalog|meta|stream|subtitles|addon_catalog".toRegex())) throw IllegalArgumentException("Unknown resource")
        val out = StringBuilder(base).append('/').append(resource).append('/')
                .append(encode(type)).append('/').append(encode(id))
        if (!extras.isEmpty()) {
            out.append('/')
            var first = true
            for (e in TreeMap(extras).entries) {
                if (!first) out.append('&')
                first = false
                out.append(encode(e.key)).append('=').append(encode(e.value))
            }
        }
        return out.append(".json").append(query).toString()
    }

    fun builtin(): Boolean { return manifest.startsWith(BUILTIN) }

    /** Keeps configured escapes and query parameters intact, just like resource addresses. */
    fun configuration(): String { return "$base/configure$query" }

    override fun toString(): String { return redacted(manifest) }

    companion object {
        const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"
        /** Add-ons answered inside the app ([BuiltinAddons]), addressed as `stream://name/manifest.json`. */
        const val BUILTIN = "stream://"

        @JvmStatic
        @Throws(AddonException::class)
        fun web(value: String?): URI {
            try {
                val uri = URI(value)
                if (!("https".equals(uri.scheme, ignoreCase = true) || "http".equals(uri.scheme, ignoreCase = true))
                        || uri.host == null || uri.rawUserInfo != null || uri.port > 65535
                        || value!!.any { c -> c.code <= 32 || c.code == 127 }) throw URISyntaxException("", "Invalid URL")
                return uri
            } catch (e: URISyntaxException) {
                throw AddonException(AddonException.Kind.INVALID_URL, "Invalid HTTP(S) address")
            } catch (e: NullPointerException) {
                throw AddonException(AddonException.Kind.INVALID_URL, "Invalid HTTP(S) address")
            }
        }

        /** What people paste: add-on configure pages hand out `stremio://host/…/manifest.json` install links. */
        @JvmStatic
        fun normalize(input: String): String {
            val value = input.trim { it <= ' ' }
            return if (value.regionMatches(0, "stremio://", 0, 10, ignoreCase = true)) "https://" + value.substring(10) else value
        }

        /** Never display configured paths, userinfo, queries, or fragments in diagnostic output. */
        @JvmStatic
        fun redacted(value: String): String {
            if (value.matches("stream://[a-z0-9-]+/manifest\\.json".toRegex())) return value
            try {
                val uri = web(value)
                return uri.scheme + "://" + uri.host + (if (uri.port < 0) "" else ":" + uri.port) + "/…"
            } catch (e: AddonException) { return "[private address]" }
        }

        @JvmStatic
        fun encode(value: String): String {
            val out = StringBuilder()
            for (b in value.toByteArray(StandardCharsets.UTF_8)) {
                val c = b.toInt() and 255
                if (c >= 'a'.code && c <= 'z'.code || c >= 'A'.code && c <= 'Z'.code || c >= '0'.code && c <= '9'.code
                        || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code) out.append(c.toChar())
                else out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
            // A resource ID is one segment, including when its entire value happens to be a dot segment.
            if (out.toString() == "." || out.toString() == "..") return out.toString().replace(".", "%2E")
            return out.toString()
        }
    }
}
