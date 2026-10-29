package tv.stream.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.stream.app.addon.AddonManifest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseCataloguesTest {
    @Throws(Exception::class)
    private fun catalog(extra: String, required: Boolean): AddonManifest.Catalog {
        val c = JSONObject().put("type", "series").put("id", "fixture").put("name", "Fixture")
                .put("extra", JSONArray().put(JSONObject().put("name", extra).put("isRequired", required)))
        return AddonManifest.parse(JSONObject().put("id", "fixture").put("name", "Fixture")
                .put("version", "1.0.0").put("resources", JSONArray().put("catalog"))
                .put("types", JSONArray().put("series")).put("catalogs", JSONArray().put(c))).catalogs.get(0)
    }
    @Test @Throws(Exception::class) fun internalEpisodeQueriesAreNotBrowseCategories() {
        assertFalse(BrowseScreens.browsable(catalog("lastVideosIds", true)))
        assertFalse(BrowseScreens.browsable(catalog("calendarVideosIds", true)))
    }
    @Test @Throws(Exception::class) fun ordinaryRequiredFiltersAndSearchRemainBrowsable() {
        assertTrue(BrowseScreens.browsable(catalog("genre", true)))
        assertTrue(BrowseScreens.browsable(catalog("search", true)))
        assertTrue(BrowseScreens.browsable(catalog("lastVideosIds", false)))
    }
}
