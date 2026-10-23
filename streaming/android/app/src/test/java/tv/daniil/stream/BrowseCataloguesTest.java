package tv.daniil.stream;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import tv.daniil.stream.addon.AddonManifest;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class BrowseCataloguesTest {
    private AddonManifest.Catalog catalog(String extra, boolean required) throws Exception {
        JSONObject c = new JSONObject().put("type", "series").put("id", "fixture").put("name", "Fixture")
                .put("extra", new JSONArray().put(new JSONObject().put("name", extra).put("isRequired", required)));
        return AddonManifest.parse(new JSONObject().put("id", "fixture").put("name", "Fixture")
                .put("version", "1.0.0").put("resources", new JSONArray().put("catalog"))
                .put("types", new JSONArray().put("series")).put("catalogs", new JSONArray().put(c))).catalogs.get(0);
    }
    @Test public void internalEpisodeQueriesAreNotBrowseCategories() throws Exception {
        assertFalse(BrowseScreens.browsable(catalog("lastVideosIds", true)));
        assertFalse(BrowseScreens.browsable(catalog("calendarVideosIds", true)));
    }
    @Test public void ordinaryRequiredFiltersAndSearchRemainBrowsable() throws Exception {
        assertTrue(BrowseScreens.browsable(catalog("genre", true)));
        assertTrue(BrowseScreens.browsable(catalog("search", true)));
        assertTrue(BrowseScreens.browsable(catalog("lastVideosIds", false)));
    }
}
