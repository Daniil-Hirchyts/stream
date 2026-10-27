package tv.stream.app.addon;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class ReleaseInfoTest {
    @Test public void catalogueAndSavedMetadataShowACompleteReleaseLabel() throws Exception {
        for (String value : new String[]{"2016-", "2016 – ", "2016—"}) {
            Title film = title("movie", value);
            assertEquals("2016", film.releaseInfo);
            assertEquals("2016", Title.parse("fixture", new JSONObject(film.cacheJson())).releaseInfo);
            assertEquals("2016 - Present", title("series", value).releaseInfo);
        }
        assertEquals("2016 - 2020", title("series", "2016–2020").releaseInfo);
        assertEquals("2016 - Present", title("series", "2016-present").releaseInfo);
        assertEquals("2016 - 2020", title("series", "2016 - 2020").releaseInfo);
        assertEquals("2016", title("movie", "2016").releaseInfo);
        assertEquals("", title("movie", "").releaseInfo);
    }
    private static Title title(String type, String release) throws Exception {
        return Title.parse("fixture", new JSONObject().put("id", "test").put("type", type)
                .put("name", "Fixture").put("releaseInfo", release));
    }
}
