package tv.daniil.stream.addon;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Add-ons answered inside the app, for services that publish an open API but no add-on. They speak the same protocol
 * as remote add-ons ({@link AddonClient} routes {@code daniiltv://} addresses here), so they're reviewed, installed,
 * ordered and removed like any other, and nothing is installed until the user accepts it.
 *
 * <p>Internet Archive: the Archive's Feature Films collection, which holds public-domain and freely licensed films.
 * Items name their IMDb page, so Cinemeta titles find their streams; files play directly over HTTPS from archive.org.
 */
public final class BuiltinAddons {
    public static final String INTERNET_ARCHIVE = AddonUrl.BUILTIN + "internet-archive/manifest.json";
    private static final String ARCHIVE = "https://archive.org", WIKIDATA = "https://query.wikidata.org/sparql";
    /** Wikimedia asks API clients to identify themselves, and refuses the add-on client's usual Accept list. In this order. */
    private static final java.util.Map<String, String> WIKIDATA_HEADERS = new java.util.LinkedHashMap<>();
    static {
        WIKIDATA_HEADERS.put("User-Agent", "DaniilTV/0.1 (Android TV; https://github.com/Daniil-Hirchyts/daniiltv)");
        WIKIDATA_HEADERS.put("Accept", "application/sparql-results+json");
    }
    private static final int PAGE = 20, ITEMS_PER_FILM = 2, ITEMS_CHECKED = 4, FILES_PER_ITEM = 6;
    /** Archive masters can exceed 15 Mbit/s; a TV on Wi-Fi streams the lighter copies more reliably. */
    private static final double MAX_MEGABITS = 8;
    private static final Pattern IMDB = Pattern.compile("tt[0-9]{7,10}");
    private static final Pattern RESOURCE = Pattern.compile("daniiltv://([a-z0-9-]+)/(catalog|stream)/([^/]+)/([^/]+?)(?:/([^/]*))?\\.json");
    private static final String ARCHIVE_MANIFEST = "{\"id\":\"tv.daniil.internet-archive\",\"version\":\"1.0.0\",\"name\":\"Internet Archive\","
            + "\"description\":\"Public-domain and freely licensed feature films from the Internet Archive, streamed directly from archive.org. "
            + "Films are matched by their IMDb identifier through archive.org and Wikidata, so Cinemeta titles play when the Archive holds them.\","
            + "\"types\":[\"movie\"],\"idPrefixes\":[\"tt\"],"
            + "\"resources\":[\"catalog\",{\"name\":\"stream\",\"types\":[\"movie\"],\"idPrefixes\":[\"tt\"]}],"
            + "\"catalogs\":[{\"type\":\"movie\",\"id\":\"feature-films\",\"name\":\"Public Domain Films\",\"extra\":[{\"name\":\"search\"},{\"name\":\"skip\"}]}]}";

    private BuiltinAddons() {}

    /** The store's built-in section. */
    public static List<AddonListing> listings() {
        try { return Collections.singletonList(new AddonListing(INTERNET_ARCHIVE, AddonManifest.parse(new JSONObject(ARCHIVE_MANIFEST)), "Built in")); }
        catch (JSONException | AddonException impossible) { throw new IllegalStateException(impossible); }
    }

    static JSONObject answer(String address, AddonClient client, Cancellation scope, long deadline) throws AddonException {
        try {
            if (address.equals(INTERNET_ARCHIVE)) return new JSONObject(ARCHIVE_MANIFEST);
            Matcher m = RESOURCE.matcher(address);
            if (!m.matches() || !m.group(1).equals("internet-archive")) throw new AddonException(AddonException.Kind.HTTP, "Unknown built-in resource", 404, null);
            String resource = m.group(2), type = decode(m.group(3)), id = decode(m.group(4)), search = "";
            int skip = 0;
            if (m.group(5) != null) for (String pair : m.group(5).split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length < 2) continue;
                if (decode(kv[0]).equals("search")) search = decode(kv[1]);
                else if (decode(kv[0]).equals("skip")) try { skip = Math.max(0, Integer.parseInt(decode(kv[1]))); } catch (NumberFormatException ignored) { /* first page */ }
            }
            if (!type.equals("movie")) throw new AddonException(AddonException.Kind.HTTP, "Unsupported type", 404, null);
            if (resource.equals("catalog") && id.equals("feature-films")) return catalog(client, scope, deadline, search, skip);
            if (resource.equals("stream") && IMDB.matcher(id).matches()) return streams(client, scope, deadline, id);
            throw new AddonException(AddonException.Kind.HTTP, "Unknown built-in resource", 404, null);
        } catch (JSONException e) { throw new AddonException(AddonException.Kind.MALFORMED, "The Internet Archive returned data this app can't read"); }
    }

    private static JSONObject catalog(AddonClient client, Cancellation scope, long deadline, String search, int skip) throws AddonException, JSONException {
        String query = "collection:feature_films AND mediatype:movies AND imdb:*";
        // Every word must appear; lower case keeps the user's "and"/"or" from becoming operators.
        String words = search.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").trim().replaceAll("\\s+", " AND ");
        if (!words.isEmpty()) query += " AND title:(" + words + ")";
        JSONArray docs = search(client, scope, deadline, query, PAGE, skip / PAGE + 1, "identifier", "title", "year", "imdb", "description");
        JSONArray metas = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < docs.length(); i++) {
            JSONObject doc = docs.optJSONObject(i);
            String imdb = doc == null ? null : imdb(doc.opt("imdb"));
            String identifier = doc == null ? null : Json.string(doc, "identifier"), title = doc == null ? null : text(doc.opt("title"));
            if (imdb == null || identifier == null || title == null || !seen.add(imdb)) continue;
            JSONObject meta = new JSONObject().put("id", imdb).put("type", "movie").put("name", title)
                    .put("poster", ARCHIVE + "/services/img/" + AddonUrl.encode(identifier)).put("imdb_id", imdb);
            String year = text(doc.opt("year")), description = text(doc.opt("description"));
            if (year != null) meta.put("releaseInfo", year);
            if (description != null) meta.put("description", plain(description));
            metas.put(meta);
        }
        return new JSONObject().put("metas", metas);
    }

    /**
     * Each matching item's directly playable files. Items come from the Feature Films collection's own IMDb links, then
     * from Wikidata, which links films (IMDb ID, P345) to their Archive items (P724).
     */
    private static JSONObject streams(AddonClient client, Cancellation scope, long deadline, String imdb) throws AddonException, JSONException {
        Set<String> identifiers = new LinkedHashSet<>();
        AddonException failure = null;
        try {
            JSONArray docs = search(client, scope, deadline, "collection:feature_films AND mediatype:movies AND imdb:" + imdb, 5, 1, "identifier", "imdb");
            for (int i = 0; i < docs.length(); i++) {
                JSONObject doc = docs.optJSONObject(i);
                String identifier = doc == null ? null : Json.string(doc, "identifier");
                // The search matches words; the item must name exactly this film.
                if (identifier != null && imdb.equals(imdb(doc.opt("imdb")))) identifiers.add(identifier);
            }
        } catch (AddonException e) { if (e.kind == AddonException.Kind.CANCELLED) throw e; failure = e; }
        try { identifiers.addAll(wikidata(client, scope, deadline, imdb)); }
        catch (AddonException e) { if (e.kind == AddonException.Kind.CANCELLED) throw e; failure = e; }
        if (identifiers.isEmpty() && failure != null) throw failure;
        // Read a few items, then prefer complete presentations: a film split into parts, or trailers and extras,
        // rank below an item holding the whole film.
        List<Item> found = new ArrayList<>();
        int checked = 0;
        for (String identifier : identifiers) {
            if (checked++ >= ITEMS_CHECKED) break;
            JSONObject item;
            try { item = client.json(ARCHIVE + "/metadata/" + AddonUrl.encode(identifier), scope, deadline); }
            catch (AddonException e) { if (e.kind == AddonException.Kind.CANCELLED) throw e; continue; }
            JSONObject metadata = item.optJSONObject("metadata");
            if (metadata == null || item.optBoolean("is_dark") || metadata.has("access-restricted-item") || !"movies".equals(Json.string(metadata, "mediatype"))) continue;
            Item candidate = new Item(identifier, metadata, item);
            if (!candidate.files.isEmpty()) found.add(candidate);
        }
        found.sort((a, b) -> Double.compare(b.runtime, a.runtime));
        JSONArray streams = new JSONArray();
        for (int i = 0; i < Math.min(ITEMS_PER_FILM, found.size()); i++) {
            Item item = found.get(i);
            if (i > 0 && item.runtime < found.get(0).runtime / 2) break;
            for (JSONObject f : item.files) streams.put(item.stream(f));
        }
        return new JSONObject().put("streams", streams);
    }

    /** One Archive item's playable files: full-length ones only, largest picture first, without heavy masters. */
    static final class Item {
        final String identifier, label;
        final List<JSONObject> files = new ArrayList<>();
        /** The longest file's running time in seconds, or 0 when the Archive doesn't say. */
        final double runtime;

        Item(String identifier, JSONObject metadata, JSONObject item) {
            this.identifier = identifier;
            String title = text(metadata.opt("title")), year = text(metadata.opt("year"));
            if (year == null) year = text(metadata.opt("date"));
            label = (title == null ? identifier : title) + (year == null ? "" : " (" + year.substring(0, Math.min(4, year.length())) + ")");
            List<JSONObject> videos = new ArrayList<>();
            double longest = 0;
            for (JSONObject f : Json.objects(item, "files")) {
                String name = Json.string(f, "name");
                if (name == null || name.contains("..") || !name.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|m4v|mkv|webm)")) continue;
                videos.add(f); longest = Math.max(longest, seconds(f));
            }
            runtime = longest;
            boolean light = false;
            for (JSONObject f : videos) light |= megabits(f) <= MAX_MEGABITS;
            for (JSONObject f : videos) {
                // Parts, trailers and extras are much shorter than the whole film.
                if (longest > 0 && seconds(f) > 0 && seconds(f) < longest * 0.8) continue;
                if (light && megabits(f) > MAX_MEGABITS) continue;
                files.add(f);
            }
            files.sort((a, b) -> Long.compare(Json.number(b, "height", 0), Json.number(a, "height", 0)));
            while (files.size() > FILES_PER_ITEM) files.remove(files.size() - 1);
        }

        JSONObject stream(JSONObject f) throws JSONException {
            String name = Json.string(f, "name"), format = Json.string(f, "format", "");
            long height = Json.number(f, "height", 0), width = Json.number(f, "width", 0), size = Json.number(f, "size", 0);
            StringBuilder path = new StringBuilder();
            for (String segment : name.split("/")) path.append('/').append(AddonUrl.encode(segment));
            // Archive derivatives labelled h.264 are re-encoded by the Archive; other uploads keep their own codec.
            String codec = format.toLowerCase(Locale.ROOT).contains("h.264") || format.equals("512Kb MPEG4") ? "H.264" : format;
            String file = name.substring(name.lastIndexOf('/') + 1);
            String details = label + "\n" + file + " · " + (width > 0 && height > 0 ? width + "×" + height + " · " : "") + codec + (size > 0 ? " · " + (size >> 20) + " MB" : "");
            JSONObject hints = new JSONObject().put("filename", file).put("bingeGroup", "internet-archive");
            if (size > 0) hints.put("videoSize", size);
            return new JSONObject().put("name", "Internet Archive" + (height > 0 ? " " + height + "p" : ""))
                    .put("description", details).put("url", ARCHIVE + "/download/" + AddonUrl.encode(identifier) + path).put("behaviorHints", hints);
        }
        /** "5796.14" or "1:36:36". */
        static double seconds(JSONObject f) {
            String value = Json.string(f, "length");
            if (value == null) return 0;
            try {
                double total = 0;
                for (String part : value.split(":")) total = total * 60 + Double.parseDouble(part);
                return total;
            } catch (NumberFormatException e) { return 0; }
        }
        static double megabits(JSONObject f) {
            double length = seconds(f);
            return length > 0 ? Json.number(f, "size", 0) * 8 / length / 1_000_000 : 0;
        }
    }

    /** Archive identifiers Wikidata links to the film with this IMDb ID. */
    private static List<String> wikidata(AddonClient client, Cancellation scope, long deadline, String imdb) throws AddonException {
        String sparql = "SELECT ?ia WHERE { ?film wdt:P345 \"" + imdb + "\"; wdt:P724 ?ia } LIMIT 10";
        String address = WIKIDATA + "?format=json&query=" + AddonUrl.encode(sparql);
        JSONObject response;
        try { response = client.json(address, WIKIDATA_HEADERS, scope, deadline); }
        catch (AddonException e) {
            // Wikimedia briefly refuses bursts of queries; one later attempt usually succeeds.
            if (e.kind != AddonException.Kind.HTTP || !(e.httpStatus == 403 || e.httpStatus == 429 || e.httpStatus == 503)) throw e;
            try { Thread.sleep(750); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AddonException(AddonException.Kind.CANCELLED, "Request cancelled"); }
            response = client.json(address, WIKIDATA_HEADERS, scope, deadline);
        }
        JSONObject results = response.optJSONObject("results");
        if (results == null) throw new AddonException(AddonException.Kind.MALFORMED, "Wikidata returned data this app can't read");
        List<String> found = new ArrayList<>();
        for (JSONObject binding : Json.objects(results, "bindings")) {
            JSONObject ia = binding.optJSONObject("ia");
            String identifier = ia == null ? null : Json.string(ia, "value");
            if (identifier != null && identifier.matches("[A-Za-z0-9._-]{1,100}")) found.add(identifier);
        }
        return found;
    }

    private static JSONArray search(AddonClient client, Cancellation scope, long deadline, String query, int rows, int page, String... fields) throws AddonException {
        StringBuilder url = new StringBuilder(ARCHIVE).append("/advancedsearch.php?q=").append(AddonUrl.encode(query));
        for (String field : fields) url.append("&fl%5B%5D=").append(field);
        url.append("&sort%5B%5D=downloads+desc&rows=").append(rows).append("&page=").append(page).append("&output=json");
        JSONObject response = client.json(url.toString(), scope, deadline).optJSONObject("response");
        JSONArray docs = response == null ? null : response.optJSONArray("docs");
        if (docs == null) throw new AddonException(AddonException.Kind.MALFORMED, "The Internet Archive returned data this app can't read");
        return docs;
    }

    /** The IMDb ID an item names, from a page address or a bare ID, alone or in a list. */
    static String imdb(Object value) {
        String text = value instanceof JSONArray ? ((JSONArray) value).optString(0, null) : value instanceof String ? (String) value : null;
        if (text == null) return null;
        Matcher m = IMDB.matcher(text);
        return m.find() && (m.end() == text.length() || !Character.isDigit(text.charAt(m.end()))) ? m.group() : null;
    }
    private static String text(Object value) {
        if (value instanceof JSONArray) value = ((JSONArray) value).opt(0);
        String s = value instanceof String ? (String) value : value instanceof Number ? value.toString() : null;
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
    /** Archive descriptions are HTML; details show a few sentences of plain text. */
    static String plain(String html) {
        String text = html.replaceAll("(?i)<br\\s*/?>|</p>", "\n").replaceAll("<[^>]*>", "").replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replaceAll("[ \\t]+", " ").replaceAll("\\s*\\n\\s*", "\n").trim();
        return text.length() > 600 ? text.substring(0, 600).replaceAll("\\s+\\S*$", "") + "…" : text;
    }
    private static String decode(String value) {
        try { return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name()); }
        catch (java.io.UnsupportedEncodingException | IllegalArgumentException e) { return value; }
    }
}
