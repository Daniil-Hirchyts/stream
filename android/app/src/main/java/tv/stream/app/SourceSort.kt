package tv.stream.app

import java.util.ArrayList
import java.util.Comparator
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern
import tv.stream.app.addon.MediaSource
import tv.stream.app.data.TvStore

/** The orders Sources offers, from what add-ons advertise in a source's name and description. Nothing is downloaded. */
object SourceSort {
    enum class Order(@JvmField val label: String) {
        BEST("Best"), QUALITY("Quality"), SIZE("Size"), SEEDS("Seeds"), PLAYED("Played");
        companion object {
            @JvmStatic fun of(name: String?): Order { for (o in values()) if (o.name == name) return o; return BEST }
        }
    }

    /** Facts read from the advertised text: 0 or -1 when a source does not say. */
    class Facts(@JvmField val lines: Int, @JvmField val bytes: Long, @JvmField val seeds: Int) {
        fun quality(): String { return if (lines <= 0) "" else lines.toString() + "p" }
    }

    private val LINES: Pattern = Pattern.compile("(?i)\\b(4320|2160|1440|1080|720|576|480|360)p\\b|\\b(4k|uhd)\\b")
    private val SIZE: Pattern = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*(tb|tib|gb|gib|mb|mib)\\b")
    private val SEEDS: Pattern = Pattern.compile("(?i)(?:👤|👥|🌱|⬆️?|\\b(?:seed(?:er)?s?|peers?)\\b\\s*[:=]?)\\s*(\\d+)")

    /** Release labels are advertised hints, not a claim verified from the video. */
    @JvmStatic
    fun releaseType(name: String?, description: String?, filename: String?): String {
        val text = (name + "\n" + description + "\n" + (filename ?: "")).lowercase(Locale.ROOT)
        if (releaseMatch(text, "(?:hd[ ._-]?)?cam(?:rip)?")) return "CAM"
        if (releaseMatch(text, "telesync|hd[ ._-]?ts")
                || Pattern.compile("(?:^|[^a-z0-9])ts[ ._-]+(?=[a-z0-9])").matcher(text).find()) return "TS"
        if (releaseMatch(text, "web[ ._-]?dl")) return "WEB-DL"
        if (releaseMatch(text, "web[ ._-]?rip")) return "WEBRip"
        if (releaseMatch(text, "blu[ ._-]?ray|b[dr][ ._-]?rip|brrip")) return "Blu-ray"
        if (releaseMatch(text, "dvd[ ._-]?rip|dvd")) return "DVD"
        if (releaseMatch(text, "hdtv|pdtv")) return "HDTV"
        if (releaseMatch(text, "web")) return "WEB"
        return ""
    }
    private fun releaseMatch(text: String, pattern: String): Boolean {
        return Pattern.compile("(?:^|[^a-z0-9])(?:$pattern)(?:[^a-z0-9]|$)").matcher(text).find()
    }
    @JvmStatic
    fun releaseType(source: MediaSource): String { return releaseType(source.name, source.description, source.filename) }

    @JvmStatic
    fun facts(name: String?, description: String?, filename: String?, advertisedBytes: Long): Facts {
        val text = name + "\n" + description + "\n" + (filename ?: "")
        var lines = 0
        var m: Matcher = LINES.matcher(text)
        while (m.find()) lines = Math.max(lines, if (m.group(1) != null) Integer.parseInt(m.group(1)) else 2160)
        var bytes = advertisedBytes
        if (bytes <= 0) {
            m = SIZE.matcher(text)
            if (m.find()) {
                val value = java.lang.Double.parseDouble(m.group(1).replace(',', '.'))
                val unit = m.group(2).lowercase(Locale.ROOT)
                val scale: Double = if (unit.startsWith("t")) (1L shl 40).toDouble() else if (unit.startsWith("g")) (1L shl 30).toDouble() else (1L shl 20).toDouble()
                bytes = (value * scale).toLong()
            }
        }
        m = SEEDS.matcher(text)
        var seeds = -1
        if (m.find()) { try { seeds = Math.min(Integer.MAX_VALUE.toLong(), java.lang.Long.parseLong(m.group(1))).toInt() } catch (overflow: NumberFormatException) { seeds = Integer.MAX_VALUE } }
        return Facts(lines, bytes, seeds)
    }
    @JvmStatic
    fun facts(s: MediaSource): Facts {
        val text = facts(s.name, s.description, s.filename, s.videoSize)
        return Facts(text.lines, text.bytes, if (s.seeders >= 0) s.seeders else text.seeds)
    }

    /**
     * `ranked` is the policy's best-first list. `reverse` flips an order (worst, smallest, fewest or oldest
     * first). Sources that don't say keep their best-first place below those that do, whichever way the list runs.
     */
    @JvmStatic
    fun sort(ranked: List<MediaSource>, order: Order, reverse: Boolean, marks: Map<String, TvStore.SourceMark>): List<MediaSource> {
        val out: MutableList<MediaSource> = ArrayList(ranked)
        if (order == Order.BEST) { if (reverse) java.util.Collections.reverse(out); return out }
        val value: MutableMap<MediaSource, Long> = java.util.IdentityHashMap()
        val rank: MutableMap<MediaSource, Int> = java.util.IdentityHashMap()
        for (i in 0 until out.size) {
            val s = out[i]; val f = facts(s); rank[s] = i
            when (order) {
                Order.QUALITY -> value[s] = f.lines.toLong()
                Order.SIZE -> value[s] = f.bytes
                Order.SEEDS -> value[s] = f.seeds.toLong()
                else -> value[s] = playedAt(marks, s)
            }
        }
        val known: Comparator<MediaSource> = Comparator.comparing { s: MediaSource -> value[s]!! <= 0 && !(order == Order.SEEDS && value[s]!! == 0L) }
        val by: Comparator<MediaSource> = Comparator.comparingLong { s: MediaSource -> value[s]!! }
        out.sortWith(known.thenComparing(if (reverse) by else by.reversed()).thenComparingInt { s: MediaSource -> rank[s]!! })
        return out
    }
    /** When a source last played; zero for failed and untried ones. */
    private fun playedAt(marks: Map<String, TvStore.SourceMark>, s: MediaSource): Long {
        val mark = marks[SourcePolicy.key(s)]
        return if (mark != null && mark.state == TvStore.SourceMark.PLAYED) mark.updatedMs else 0
    }
}
