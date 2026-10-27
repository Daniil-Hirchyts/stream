package tv.stream.app.addon;

import androidx.annotation.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Metadata exists independently of sources. Episode IDs always come from the provider. */
public final class Title {
    public static final class Episode {
        public final String id, name, released, overview;
        public final int season, number;
        @Nullable public final String thumbnail;
        public final List<MediaSource> streams;

        private Episode(String provider, JSONObject o) {
            id = Json.string(o, "id", "");
            name = Json.text(o, "title", Json.text(o, "name", id));
            released = Json.string(o, "released", "");
            overview = Json.text(o, "overview", "");
            season = integer(o, "season");
            number = integer(o, "episode");
            thumbnail = Json.webUrl(Json.string(o, "thumbnail"));
            streams = MediaSource.list(provider, o);
        }
    }

    public final String provider, id, type, name, description, releaseInfo, rating;
    @Nullable public final String poster, background, logo, imdbId;
    public final List<String> genres, cast, directors;
    public final List<Episode> episodes;
    private final String cachedJson;

    private Title(String provider, JSONObject o) {
        this.provider = provider;
        id = Json.string(o, "id", "");
        type = Json.string(o, "type", "");
        name = Json.text(o, "name", id);
        description = Json.text(o, "description", "");
        releaseInfo = displayReleaseInfo(type, Json.text(o, "releaseInfo", ""));
        rating = Json.string(o, "imdbRating", "");
        poster = Json.webUrl(Json.string(o, "poster"));
        background = Json.webUrl(Json.string(o, "background"));
        logo = Json.webUrl(Json.string(o, "logo"));
        String external = Json.string(o, "imdb_id");
        if (external == null && id.matches("tt[0-9]+")) external = id;
        imdbId = external != null && external.matches("tt[0-9]+") ? external : null;
        genres = Json.texts(o, "genres");
        cast = Json.texts(o, "cast");
        directors = Json.texts(o, "director");
        List<Episode> videos = new ArrayList<>();
        for (JSONObject video : Json.objects(o, "videos")) {
            if (Json.string(video, "id") != null) videos.add(new Episode(provider, video));
        }
        episodes = Collections.unmodifiableList(videos);
        // Persist only metadata. Embedded playable URLs/headers must be resolved again after resume.
        JSONObject safe = new JSONObject();
        try {
            for (String key : new String[]{"id", "type", "name", "description", "releaseInfo", "imdbRating",
                    "poster", "background", "logo", "imdb_id", "genres", "cast", "director"}) {
                if (o.has(key)) safe.put(key, o.get(key));
            }
            JSONArray savedVideos = new JSONArray();
            for (Episode e : videos) savedVideos.put(new JSONObject().put("id", e.id).put("title", e.name)
                    .put("released", e.released).put("overview", e.overview).put("season", e.season)
                    .put("episode", e.number).put("thumbnail", e.thumbnail));
            safe.put("videos", savedVideos);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        cachedJson = safe.toString();
    }

    /** Render incomplete catalogue ranges as a year for films, or an explicit ongoing series. */
    static String displayReleaseInfo(String type, String release) {
        String text = release.trim();
        text = text.replaceFirst("^(\\d{4})\\s*[-–—]\\s*(?i:present)?$", type.equals("movie") ? "$1" : "$1 - Present");
        return text.replaceFirst("^(\\d{4})\\s*[-–—]\\s*(\\d{4})$", "$1 - $2");
    }

    private Title(Title metadata, String provider) {
        this.provider = provider; id = metadata.id; type = metadata.type; name = metadata.name;
        description = metadata.description; releaseInfo = metadata.releaseInfo; rating = metadata.rating;
        poster = metadata.poster; background = metadata.background; logo = metadata.logo; imdbId = metadata.imdbId;
        genres = metadata.genres; cast = metadata.cast; directors = metadata.directors;
        episodes = metadata.episodes; cachedJson = metadata.cachedJson;
    }
    /** Enrichment retains the catalogue/library identity; inline streams keep their actual source provider. */
    public Title withProvider(String provider) { return this.provider.equals(provider) ? this : new Title(this, provider); }

    @Nullable public static Title parse(String provider, @Nullable JSONObject o) {
        if (o == null || Json.string(o, "id") == null || Json.string(o, "type") == null
                || Json.string(o, "name") == null) return null;
        return new Title(provider, o);
    }

    public static List<Title> catalog(String provider, JSONObject response) throws AddonException {
        if (response.optJSONArray("metas") == null) throw new AddonException(AddonException.Kind.MALFORMED, "Missing catalogue entries");
        List<Title> titles = new ArrayList<>();
        for (JSONObject o : Json.objects(response, "metas")) {
            Title t = parse(provider, o);
            if (t != null) titles.add(t);
        }
        return Collections.unmodifiableList(titles);
    }

    private static int integer(JSONObject o, String key) {
        long n = Json.index(o, key);
        return n >= 0 && n <= Integer.MAX_VALUE ? (int) n : -1;
    }

    /** Provider scoping prevents collisions; common IMDb IDs alone may deduplicate discovery results. */
    public String key() { return new JSONArray().put(provider).put(type).put(id).toString(); }
    public String discoveryKey() { return imdbId == null ? key() : type + ":imdb:" + imdbId; }
    public String cacheJson() { return cachedJson; }
}
