package tv.stream.app

import java.util.ArrayList
import java.util.LinkedHashMap
import tv.stream.app.addon.AddonException
import tv.stream.app.addon.MediaSource
import tv.stream.app.data.InstalledAddon

/** Explains an empty source list: no source add-on, failing providers, only unsupported results, or none at all. */
class Availability private constructor(@JvmField val heading: String, @JvmField val detail: String) {
    companion object {
        const val NO_PROVIDER = "No source add-on installed"
        const val NO_PROVIDER_DETAIL = "Add a source add-on to play titles."

        /**
         * @param providers add-ons asked for streams
         * @param found every advertised source, including unsupported ones
         * @param failures failed providers by add-on ID
         */
        @JvmStatic
        fun of(providers: List<InstalledAddon>, found: List<MediaSource>, failures: Map<String, AddonException.Kind>, installed: List<InstalledAddon>): Availability {
            if (found.isEmpty() && providers.isEmpty()) return Availability(NO_PROVIDER, NO_PROVIDER_DETAIL)
            if (found.isEmpty() && !failures.isEmpty()) return Availability("Source providers are unavailable", failures(failures, installed))
            if (!found.isEmpty()) {
                val reasons: MutableMap<String, Int> = LinkedHashMap()
                for (s in found) { val reason = SourcePolicy.assess(s).label; reasons.merge(reason, 1) { a, b -> Integer.sum(a, b) } }
                val parts: MutableList<String> = ArrayList()
                for (e in reasons.entries) parts.add(e.key + (if (reasons.size > 1) " (" + e.value + ")" else ""))
                return Availability("No playable sources", found.size.toString() + " found · " + java.lang.String.join(" · ", parts))
            }
            return Availability("No sources found", "Nothing for this title yet.")
        }

        /** "Provider X didn't respond in time." for each failed provider, without addresses or response bodies. */
        @JvmStatic
        fun failures(failures: Map<String, AddonException.Kind>, installed: List<InstalledAddon>): String {
            val lines: MutableList<String> = ArrayList()
            for (e in failures.entries) {
                var name = "A provider"
                for (a in installed) if (a.id == e.key) name = a.manifest.name
                lines.add(name + " " + problem(e.value) + ".")
            }
            return java.lang.String.join(" ", lines)
        }
        @JvmStatic
        fun problem(kind: AddonException.Kind): String {
            return when (kind) {
                AddonException.Kind.TIMEOUT -> "didn't respond in time"
                AddonException.Kind.CERTIFICATE -> "has a certificate this TV can't verify"
                AddonException.Kind.HTTP -> "returned an error"
                AddonException.Kind.MALFORMED -> "returned data this app can't read"
                AddonException.Kind.CANCELLED -> "was cancelled"
                else -> "couldn't be reached"
            }
        }
    }
}
