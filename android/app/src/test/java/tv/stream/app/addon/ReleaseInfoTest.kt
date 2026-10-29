package tv.stream.app.addon

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseInfoTest {
    @Test @Throws(Exception::class) fun catalogueAndSavedMetadataShowACompleteReleaseLabel() {
        for (value in arrayOf("2016-", "2016 – ", "2016—")) {
            val film = title("movie", value)
            assertEquals("2016", film.releaseInfo)
            assertEquals("2016", Title.parse("fixture", JSONObject(film.cacheJson()))!!.releaseInfo)
            assertEquals("2016 - Present", title("series", value).releaseInfo)
        }
        assertEquals("2016 - 2020", title("series", "2016–2020").releaseInfo)
        assertEquals("2016 - Present", title("series", "2016-present").releaseInfo)
        assertEquals("2016 - 2020", title("series", "2016 - 2020").releaseInfo)
        assertEquals("2016", title("movie", "2016").releaseInfo)
        assertEquals("", title("movie", "").releaseInfo)
    }

    companion object {
        @Throws(Exception::class)
        private fun title(type: String, release: String): Title {
            return Title.parse("fixture", JSONObject().put("id", "test").put("type", type)
                    .put("name", "Fixture").put("releaseInfo", release))!!
        }
    }
}
