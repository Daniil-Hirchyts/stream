package tv.daniil.cast;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tv.daniil.playback.Capabilities;

/**
 * A series as the page's player lists it: seasons, episodes, and for each episode its versions, voice-overs and
 * subtitle files. With it the TV changes episodes by itself, without Chrome.
 */
final class Catalog {
    static final class Version {
        /** The player's name for this version, such as "dash" or "hls"; the TV keeps the viewer's kind. */
        final String kind, url, mime, codec;

        Version(JSONObject json) {
            kind = json.optString("kind");
            url = json.optString("url");
            mime = json.optString("mime");
            codec = json.optString("codec");
        }
    }

    static final class Episode {
        final String id, number, title;
        final long durationMs;
        final List<Version> versions = new ArrayList<>();
        final List<String> audioNames = new ArrayList<>();
        /** Subtitle files in the extension's format: url, mime, language, label. */
        final JSONArray subtitles;
        Season season;

        Episode(JSONObject json) {
            id = json.optString("id");
            number = json.optString("number", json.optString("label"));
            title = json.optString("title");
            durationMs = Math.max(0, json.optLong("duration")) * 1000;
            JSONArray items = json.optJSONArray("versions");
            for (int i = 0; items != null && i < items.length(); i++) {
                Version version = new Version(items.optJSONObject(i));
                if (version.url.startsWith("http")) versions.add(version);
            }
            JSONArray names = json.optJSONArray("audioNames");
            for (int i = 0; names != null && i < names.length(); i++) audioNames.add(names.optString(i));
            JSONArray files = json.optJSONArray("subtitles");
            subtitles = files == null ? new JSONArray() : files;
        }
    }

    static final class Season {
        final String id, number;
        final List<Episode> episodes = new ArrayList<>();

        Season(JSONObject json) {
            id = json.optString("id");
            number = json.optString("number", json.optString("label"));
            JSONArray items = json.optJSONArray("episodes");
            for (int i = 0; items != null && i < items.length(); i++) {
                Episode episode = new Episode(items.optJSONObject(i));
                if (episode.versions.isEmpty()) continue;
                episode.season = this;
                episodes.add(episode);
            }
            Collections.sort(episodes, (a, b) -> compareNumbers(a.number, b.number));
        }
    }

    final String title;
    final JSONObject headers;
    final List<Season> seasons = new ArrayList<>();
    private final List<Episode> all = new ArrayList<>();

    private Catalog(JSONObject json) {
        title = json.optString("title");
        JSONObject supplied = json.optJSONObject("headers");
        headers = supplied == null ? new JSONObject() : supplied;
        JSONArray items = json.optJSONArray("seasons");
        for (int i = 0; items != null && i < items.length(); i++) {
            Season season = new Season(items.optJSONObject(i));
            if (season.episodes.isEmpty()) continue;
            seasons.add(season);
        }
        // Players list seasons newest first at times; viewers expect 1, 2, 3.
        Collections.sort(seasons, (a, b) -> compareNumbers(a.number, b.number));
        for (Season season : seasons) all.addAll(season.episodes);
    }

    @Nullable static Catalog parse(@Nullable JSONObject json) {
        if (json == null) return null;
        Catalog catalog = new Catalog(json);
        return catalog.all.isEmpty() ? null : catalog;
    }

    @Nullable Episode find(@Nullable String id) {
        for (Episode episode : all) if (episode.id.equals(id)) return episode;
        return null;
    }

    @Nullable Episode next(Episode episode) {
        int index = all.indexOf(episode);
        return index >= 0 && index + 1 < all.size() ? all.get(index + 1) : null;
    }

    @Nullable Episode previous(Episode episode) {
        int index = all.indexOf(episode);
        return index > 0 ? all.get(index - 1) : null;
    }

    /**
     * The version to play: the viewer's kind when this TV decodes it, else the first the TV decodes, in the
     * extension's order of preference. With none, the first, which the Mac can then convert.
     */
    static Version pick(Episode episode, @Nullable String kind) {
        for (Version version : episode.versions) {
            if (version.kind.equals(kind) && Capabilities.decodes(version.codec)) return version;
        }
        for (Version version : episode.versions) if (Capabilities.decodes(version.codec)) return version;
        return episode.versions.get(0);
    }

    /** Seasons and episodes without stream addresses, for the extension's menus. */
    JSONObject summary(@Nullable Episode current) throws Exception {
        JSONArray seasonList = new JSONArray();
        for (Season season : seasons) {
            JSONArray episodes = new JSONArray();
            for (Episode episode : season.episodes) {
                episodes.put(new JSONObject().put("id", episode.id).put("number", episode.number)
                        .put("title", episode.title).put("duration", episode.durationMs / 1000));
            }
            seasonList.put(new JSONObject().put("id", season.id).put("number", season.number).put("episodes", episodes));
        }
        Episode next = current == null ? null : next(current);
        return new JSONObject().put("seasons", seasonList).put("current", current == null ? "" : current.id)
                .put("next", next == null ? "" : next.id);
    }

    private static int compareNumbers(String a, String b) {
        try {
            return Double.compare(Double.parseDouble(a), Double.parseDouble(b));
        } catch (NumberFormatException exception) {
            return a.compareTo(b);
        }
    }
}
