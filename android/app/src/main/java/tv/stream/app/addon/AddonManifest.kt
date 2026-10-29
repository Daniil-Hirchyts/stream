package tv.stream.app.addon

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayList
import java.util.Collections

/**
 * An add-on's manifest (Stremio add-on protocol): which resources it answers, for which types and id prefixes, and
 * its catalogues. Resources may be plain names, using the manifest's types and prefixes, or objects with their own.
 * Malformed optional entries are skipped; the identity fields and resources are required.
 */
class AddonManifest private constructor(o: JSONObject, resources: List<Resource>, catalogs: List<Catalog>, addonCatalogs: List<Catalog>) {
    class Resource(val name: String, types: List<String>,
                   /** Empty: any id. */
                   idPrefixes: List<String>) {
        val types: List<String> = Collections.unmodifiableList(ArrayList(types))
        val idPrefixes: List<String> = Collections.unmodifiableList(ArrayList(idPrefixes))
    }

    class Extra(val name: String, val required: Boolean, options: List<String>) {
        val options: List<String> = Collections.unmodifiableList(ArrayList(options))
    }

    class Catalog(val type: String, val id: String, val name: String, extras: List<Extra>) {
        val extras: List<Extra> = Collections.unmodifiableList(ArrayList(extras))

        fun supports(extra: String): Boolean = extras.any { it.name == extra }

        /** A catalogue that needs an extra, such as a search query, can't be listed on its own. */
        fun listable(): Boolean = extras.none { it.required }

        /** Searchable when "search" is its only required extra, or an optional one. */
        fun searchable(): Boolean = supports("search") && extras.none { it.required && it.name != "search" }

        fun extra(name: String): Extra? = extras.firstOrNull { it.name == name }
    }

    val id: String = Json.string(o, "id", "")
    val version: String = Json.string(o, "version", "")
    val name: String = Json.string(o, "name", "")
    val description: String = Json.string(o, "description", "")
    val logo: String? = Json.webUrl(Json.string(o, "logo"))
    val background: String? = Json.webUrl(Json.string(o, "background"))
    val types: List<String> = Json.strings(o, "types")
    val idPrefixes: List<String> = Json.strings(o, "idPrefixes")
    val resources: List<Resource> = Collections.unmodifiableList(ArrayList(resources))
    val catalogs: List<Catalog> = Collections.unmodifiableList(ArrayList(catalogs))
    /** Lists of other add-ons this add-on publishes through `addon_catalog`, such as Cinemeta's Official and Community. */
    val addonCatalogs: List<Catalog> = Collections.unmodifiableList(ArrayList(addonCatalogs))
    val configurable: Boolean
    val configurationRequired: Boolean
    val adult: Boolean
    val p2p: Boolean
    /** The manifest as received, kept so an installed add-on survives restarts without refetching. */
    val json: String

    init {
        val hints = o.optJSONObject("behaviorHints")
        configurable = Json.bool(hints, "configurable")
        // Some setup roots, including AIOMetadata, omit the required flag until a profile exposes resources.
        configurationRequired = Json.bool(hints, "configurationRequired") || configurable && resources.isEmpty()
        adult = Json.bool(hints, "adult")
        p2p = Json.bool(hints, "p2p")
        json = o.toString()
    }

    /** Declares `resource` at all, for any type or id. */
    fun declares(resource: String): Boolean {
        for (r in resources) if (r.name == resource) return true
        return false
    }

    /** Whether the add-on answers `resource` for this type, and this id when one is given. */
    fun handles(resource: String, type: String, id: String?): Boolean {
        for (r in resources) {
            if (r.name != resource) continue
            if (!r.types.isEmpty() && !r.types.contains(type)) continue
            if (id == null || r.idPrefixes.isEmpty()) return true
            for (prefix in r.idPrefixes) if (id.startsWith(prefix)) return true
        }
        return false
    }

    companion object {
        const val CATALOG = "catalog"
        const val META = "meta"
        const val STREAM = "stream"
        const val SUBTITLES = "subtitles"
        const val ADDON_CATALOG = "addon_catalog"

        @Throws(AddonException::class)
        fun parse(o: JSONObject): AddonManifest {
            if (Json.string(o, "id") == null || Json.string(o, "name") == null || Json.string(o, "version") == null) {
                throw AddonException(AddonException.Kind.MALFORMED, "The manifest has no id, name or version")
            }
            val types = Json.strings(o, "types")
            val prefixes = Json.strings(o, "idPrefixes")
            val raw: JSONArray = o.optJSONArray("resources") ?: throw AddonException(AddonException.Kind.MALFORMED, "The manifest lists no resources")
            val resources: MutableList<Resource> = ArrayList()
            for (i in 0 until raw.length()) {
                val item = raw.opt(i)
                if (item is String && !item.isEmpty()) {
                    resources.add(Resource(item, types, prefixes))
                } else if (item is JSONObject) {
                    val r = item
                    val name = Json.string(r, "name") ?: continue
                    // An object without its own types or prefixes uses the manifest's.
                    val rTypes = if (r.has("types")) Json.strings(r, "types") else types
                    val rPrefixes = if (r.has("idPrefixes")) Json.strings(r, "idPrefixes") else prefixes
                    resources.add(Resource(name, rTypes, rPrefixes))
                }
            }
            // An explicit empty array is a valid setup descriptor; a nonempty but unreadable declaration isn't.
            if (resources.isEmpty() && raw.length() > 0) throw AddonException(AddonException.Kind.MALFORMED, "The manifest lists no usable resources")
            val catalogs: MutableList<Catalog> = ArrayList()
            for (c in Json.objects(o, "catalogs")) {
                val type = Json.string(c, "type")
                val id = Json.string(c, "id")
                if (type == null || id == null) continue
                val extras: MutableList<Extra> = ArrayList()
                for (e in Json.objects(c, "extra")) {
                    val name = Json.string(e, "name")
                    if (name != null) extras.add(Extra(name, Json.bool(e, "isRequired"), Json.strings(e, "options")))
                }
                // The older form lists names in extraSupported and extraRequired.
                val required = Json.strings(c, "extraRequired")
                for (name in Json.strings(c, "extraSupported")) {
                    var known = false
                    for (e in extras) known = known or (e.name == name)
                    if (!known) extras.add(Extra(name, required.contains(name), Collections.emptyList()))
                }
                for (name in required) {
                    var known = false
                    for (e in extras) known = known or (e.name == name)
                    if (!known) extras.add(Extra(name, true, Collections.emptyList()))
                }
                catalogs.add(Catalog(type, id, Json.string(c, "name", id), Collections.unmodifiableList(extras)))
            }
            val addonCatalogs: MutableList<Catalog> = ArrayList()
            for (c in Json.objects(o, "addonCatalogs")) {
                val type = Json.string(c, "type")
                val id = Json.string(c, "id")
                if (type != null && id != null) addonCatalogs.add(Catalog(type, id, Json.string(c, "name", id), Collections.emptyList()))
            }
            return AddonManifest(o, Collections.unmodifiableList(resources), Collections.unmodifiableList(catalogs), Collections.unmodifiableList(addonCatalogs))
        }
    }
}
