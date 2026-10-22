package tv.daniil.stream.addon;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An add-on's manifest (Stremio add-on protocol): which resources it answers, for which types and id prefixes, and
 * its catalogues. Resources may be plain names, using the manifest's types and prefixes, or objects with their own.
 * Malformed optional entries are skipped; the identity fields and resources are required.
 */
public final class AddonManifest {
    public static final String CATALOG = "catalog", META = "meta", STREAM = "stream", SUBTITLES = "subtitles";

    public static final class Resource {
        public final String name;
        public final List<String> types;
        /** Empty: any id. */
        public final List<String> idPrefixes;

        Resource(String name, List<String> types, List<String> idPrefixes) {
            this.name = name;
            this.types = types;
            this.idPrefixes = idPrefixes;
        }
    }

    public static final class Extra {
        public final String name;
        public final boolean required;
        public final List<String> options;

        Extra(String name, boolean required, List<String> options) {
            this.name = name;
            this.required = required;
            this.options = options;
        }
    }

    public static final class Catalog {
        public final String type, id, name;
        public final List<Extra> extras;

        Catalog(String type, String id, String name, List<Extra> extras) {
            this.type = type;
            this.id = id;
            this.name = name;
            this.extras = extras;
        }

        public boolean supports(String extra) {
            for (Extra e : extras) if (e.name.equals(extra)) return true;
            return false;
        }

        /** A catalogue that needs an extra, such as a search query, can't be listed on its own. */
        public boolean listable() {
            for (Extra e : extras) if (e.required) return false;
            return true;
        }

        /** Searchable when "search" is its only required extra, or an optional one. */
        public boolean searchable() {
            if (!supports("search")) return false;
            for (Extra e : extras) if (e.required && !e.name.equals("search")) return false;
            return true;
        }

        @Nullable public Extra extra(String name) {
            for (Extra e : extras) if (e.name.equals(name)) return e;
            return null;
        }
    }

    public final String id, version, name, description;
    @Nullable public final String logo, background;
    public final List<Resource> resources;
    public final List<String> types;
    public final List<String> idPrefixes;
    public final List<Catalog> catalogs;
    public final boolean configurable, configurationRequired, adult, p2p;
    /** The manifest as received, kept so an installed add-on survives restarts without refetching. */
    public final String json;

    private AddonManifest(JSONObject o, List<Resource> resources, List<Catalog> catalogs) {
        id = Json.string(o, "id", "");
        version = Json.string(o, "version", "");
        name = Json.string(o, "name", "");
        description = Json.string(o, "description", "");
        logo = Json.webUrl(Json.string(o, "logo"));
        background = Json.webUrl(Json.string(o, "background"));
        types = Json.strings(o, "types");
        idPrefixes = Json.strings(o, "idPrefixes");
        this.resources = resources;
        this.catalogs = catalogs;
        JSONObject hints = o.optJSONObject("behaviorHints");
        configurable = Json.bool(hints, "configurable");
        configurationRequired = Json.bool(hints, "configurationRequired");
        adult = Json.bool(hints, "adult");
        p2p = Json.bool(hints, "p2p");
        json = o.toString();
    }

    public static AddonManifest parse(JSONObject o) throws AddonException {
        if (Json.string(o, "id") == null || Json.string(o, "name") == null || Json.string(o, "version") == null) {
            throw new AddonException(AddonException.Kind.MALFORMED, "The manifest has no id, name or version");
        }
        List<String> types = Json.strings(o, "types");
        List<String> prefixes = Json.strings(o, "idPrefixes");
        JSONArray raw = o.optJSONArray("resources");
        if (raw == null) throw new AddonException(AddonException.Kind.MALFORMED, "The manifest lists no resources");
        List<Resource> resources = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            Object item = raw.opt(i);
            if (item instanceof String && !((String) item).isEmpty()) {
                resources.add(new Resource((String) item, types, prefixes));
            } else if (item instanceof JSONObject) {
                JSONObject r = (JSONObject) item;
                String name = Json.string(r, "name");
                if (name == null) continue;
                // An object without its own types or prefixes uses the manifest's.
                List<String> rTypes = r.has("types") ? Json.strings(r, "types") : types;
                List<String> rPrefixes = r.has("idPrefixes") ? Json.strings(r, "idPrefixes") : prefixes;
                resources.add(new Resource(name, rTypes, rPrefixes));
            }
        }
        if (resources.isEmpty()) throw new AddonException(AddonException.Kind.MALFORMED, "The manifest lists no usable resources");
        List<Catalog> catalogs = new ArrayList<>();
        for (JSONObject c : Json.objects(o, "catalogs")) {
            String type = Json.string(c, "type"), id = Json.string(c, "id");
            if (type == null || id == null) continue;
            List<Extra> extras = new ArrayList<>();
            for (JSONObject e : Json.objects(c, "extra")) {
                String name = Json.string(e, "name");
                if (name != null) extras.add(new Extra(name, Json.bool(e, "isRequired"), Json.strings(e, "options")));
            }
            // The older form lists names in extraSupported and extraRequired.
            List<String> required = Json.strings(c, "extraRequired");
            for (String name : Json.strings(c, "extraSupported")) {
                boolean known = false;
                for (Extra e : extras) known |= e.name.equals(name);
                if (!known) extras.add(new Extra(name, required.contains(name), Collections.emptyList()));
            }
            for (String name : required) {
                boolean known = false;
                for (Extra e : extras) known |= e.name.equals(name);
                if (!known) extras.add(new Extra(name, true, Collections.emptyList()));
            }
            catalogs.add(new Catalog(type, id, Json.string(c, "name", id), Collections.unmodifiableList(extras)));
        }
        return new AddonManifest(o, Collections.unmodifiableList(resources), Collections.unmodifiableList(catalogs));
    }

    /** Whether the add-on answers `resource` for this type, and this id when one is given. */
    public boolean handles(String resource, String type, @Nullable String id) {
        for (Resource r : resources) {
            if (!r.name.equals(resource)) continue;
            if (!r.types.isEmpty() && !r.types.contains(type)) continue;
            if (id == null || r.idPrefixes.isEmpty()) return true;
            for (String prefix : r.idPrefixes) if (id.startsWith(prefix)) return true;
        }
        return false;
    }
}
