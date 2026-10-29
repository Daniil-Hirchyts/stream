package tv.stream.app.addon

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayList
import java.util.Collections

/** Lenient reads for add-on responses: a wrong type reads as absent rather than failing the whole response. */
object Json {
    @JvmStatic
    fun string(o: JSONObject, key: String): String? {
        val v = o.opt(key)
        if (v !is String) return null
        val s = v
        return if (s.trim { it <= ' ' }.isEmpty()) null else s
    }

    @JvmStatic
    fun string(o: JSONObject, key: String, fallback: String): String {
        val s = string(o, key)
        return s ?: fallback
    }

    @JvmStatic
    fun bool(o: JSONObject?, key: String): Boolean {
        return o != null && o.opt(key) is Boolean && o.optBoolean(key)
    }

    /** A non-negative integer, or -1 when absent; anything else is reported as -2 so callers can reject it. */
    @JvmStatic
    fun index(o: JSONObject, key: String): Long {
        if (!o.has(key)) return -1
        if (o.isNull(key)) return -2
        val v = o.opt(key)
        if (v is Int || v is Long) {
            val n = (v as Number).toLong()
            return if (n >= 0) n else -2
        }
        return -2
    }

    @JvmStatic
    fun number(o: JSONObject, key: String, fallback: Long): Long {
        val v = o.opt(key)
        if (v is Number) return v.toLong()
        if (v is String) {
            try {
                return java.lang.Long.parseLong(v.trim { it <= ' ' })
            } catch (ignored: NumberFormatException) { /* fallback */ }
        }
        return fallback
    }

    /** Display text: some providers send HTML entities ("O&apos;Dea", "Tom &amp; Jerry"); show the characters. */
    @JvmStatic
    fun text(o: JSONObject, key: String, fallback: String): String { return entities(string(o, key, fallback))!! }
    @JvmStatic
    fun texts(o: JSONObject, key: String): List<String> {
        val raw = strings(o, key)
        val out: MutableList<String> = ArrayList(raw.size)
        for (value in raw) out.add(entities(value)!!)
        return Collections.unmodifiableList(out)
    }
    @JvmStatic
    fun entities(value: String?): String? {
        if (value == null || value.indexOf('&') < 0) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val end = if (c == '&') value.indexOf(';', i) else -1
            if (end < 0 || end - i > 10) { out.append(c); i++; continue }
            val name = value.substring(i + 1, end)
            var code = -1
            when (name) {
                "amp" -> code = '&'.code
                "lt" -> code = '<'.code
                "gt" -> code = '>'.code
                "quot" -> code = '"'.code
                "apos" -> code = '\''.code
                "nbsp" -> code = ' '.code
                else -> {
                    try {
                        if (name.startsWith("#x") || name.startsWith("#X")) code = Integer.parseInt(name.substring(2), 16)
                        else if (name.startsWith("#")) code = Integer.parseInt(name.substring(1))
                    } catch (ignored: NumberFormatException) { /* not an entity */ }
                }
            }
            if (code <= 0 || code > Character.MAX_CODE_POINT) { out.append(c); i++; continue }
            out.appendCodePoint(code); i = end
            i++
        }
        return out.toString()
    }

    /** The strings of an array, skipping anything else; empty when absent. */
    @JvmStatic
    fun strings(o: JSONObject, key: String): List<String> {
        val a: JSONArray = o.optJSONArray(key) ?: return Collections.emptyList()
        val out: MutableList<String> = ArrayList(a.length())
        for (i in 0 until a.length()) {
            val v = a.opt(i)
            if (v is String && !v.trim { it <= ' ' }.isEmpty()) out.add(v)
        }
        return Collections.unmodifiableList(out)
    }

    /** The objects of an array, skipping anything else. */
    @JvmStatic
    fun objects(o: JSONObject, key: String): List<JSONObject> {
        val a: JSONArray = o.optJSONArray(key) ?: return Collections.emptyList()
        val out: MutableList<JSONObject> = ArrayList(a.length())
        for (i in 0 until a.length()) {
            val item = a.optJSONObject(i)
            if (item != null) out.add(item)
        }
        return out
    }

    /** http(s) only: add-ons must never steer the player to other schemes. */
    @JvmStatic
    fun webUrl(url: String?): String? {
        if (url == null) return null
        try { AddonUrl.web(url); return url }
        catch (e: AddonException) { return null }
    }
}
