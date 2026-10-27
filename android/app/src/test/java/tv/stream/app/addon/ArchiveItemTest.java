package tv.stream.app.addon;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Which files of an Internet Archive item the built-in add-on offers, from metadata shaped like archive.org's. */
public final class ArchiveItemTest {
    private static JSONObject file(String name, String format, int height, String length, long megabytes) throws Exception {
        return new JSONObject().put("name", name).put("format", format).put("height", String.valueOf(height)).put("width", String.valueOf(height * 16 / 9))
                .put("length", length).put("size", String.valueOf(megabytes << 20));
    }
    private static BuiltinAddons.Item item(JSONObject... files) throws Exception {
        JSONArray list = new JSONArray();
        for (JSONObject f : files) list.put(f);
        list.put(new JSONObject().put("name", "film.ogv").put("format", "Ogg Video")).put(new JSONObject().put("name", "../escape.mp4"));
        return new BuiltinAddons.Item("film_1968", new JSONObject().put("title", "A Film").put("year", 1968), new JSONObject().put("files", list));
    }

    @Test public void completeFilmsOutrankPartsTrailersAndHeavyMasters() throws Exception {
        BuiltinAddons.Item whole = item(file("film.mkv", "h.264 MPEG4", 1080, "5752.8", 10989), file("film_ps3.mp4", "h.264 MPEG4", 1080, "5752.8", 1776),
                file("film_512kb.mp4", "512Kb MPEG4", 240, "1:35:52", 388), file("trailer.mp4", "h.264", 720, "120.5", 30));
        assertEquals(5752.8, whole.runtime, 0.01);
        assertEquals(2, whole.files.size());
        assertEquals("film_ps3.mp4", whole.files.get(0).getString("name"));
        JSONObject stream = whole.stream(whole.files.get(0));
        assertEquals("Internet Archive 1080p", stream.getString("name"));
        assertEquals("https://archive.org/download/film_1968/film_ps3.mp4", stream.getString("url"));
        assertTrue(stream.getString("description"), stream.getString("description").startsWith("A Film (1968)\nfilm_ps3.mp4 · 1920×1080 · H.264 · 1776 MB"));
        assertEquals(1776L << 20, stream.getJSONObject("behaviorHints").getLong("videoSize"));
        MediaSource source = MediaSource.list("archive", new JSONObject().put("streams", new JSONArray().put(stream))).get(0);
        assertEquals(MediaSource.Kind.HTTP, source.kind);

        BuiltinAddons.Item parts = item(file("film-1of2_512kb.mp4", "512Kb MPEG4", 240, "1190.5", 82), file("film-2of2_512kb.mp4", "512Kb MPEG4", 240, "1190.6", 82));
        assertTrue(parts.runtime < whole.runtime / 2);
        // Only a master exists: it is still offered.
        assertEquals(1, item(file("master.mkv", "MPEG4", 1080, "5000", 9000)).files.size());
        assertEquals("film%20name%20%281%29.mp4", item(file("film name (1).mp4", "MPEG4", 480, "", 700)).stream(new JSONObject().put("name", "film name (1).mp4"))
                .getString("url").replaceAll(".*/", ""));
    }
}
