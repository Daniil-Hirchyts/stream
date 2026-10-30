package tv.stream.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.stream.app.addon.SubtitleSource

/** The player's subtitle list: the viewer's language first, no repeats, a bound, unloaded offers and their names. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubtitleOffersTest {
    private fun offers(vararg entries: Pair<String, String>): List<SubtitleSource> = SubtitleSource.list("test", JSONObject().put("subtitles",
            JSONArray(entries.map { (lang, url) -> JSONObject().put("lang", lang).put("url", url) })))

    @Test fun ranksWantedLanguageFirstWithoutRepeatsWithinTheBound() {
        val advertised = offers("en" to "https://a.test/1.srt", "fr" to "https://a.test/2.vtt", "eng" to "https://a.test/3.ass",
                "fr" to "https://a.test/2.vtt")
        val ranked = SubtitleOffers.rank(advertised, "fra")
        assertEquals(listOf("https://a.test/2.vtt", "https://a.test/1.srt", "https://a.test/3.ass"), ranked.map { it.url })
        val many = offers(*Array(60) { "en" to "https://a.test/$it.srt" })
        assertEquals(SubtitleOffers.MAX_OFFERS, SubtitleOffers.rank(many, null).size)
    }

    @Test fun unloadedOffersAreNamedAfterLoadedTracksWithFormats() {
        val loaded = offers("en" to "https://a.test/loaded.srt")
        val all = offers("en" to "https://a.test/loaded.srt", "en" to "https://a.test/b.srt", "en" to "https://a.test/c.ssa",
                "de" to "https://a.test/d.vtt", "de" to "https://a.test/e.vtt")
        val unloaded = SubtitleOffers.unloaded(all, loaded.map { it.url }, setOf("https://a.test/e.vtt")) { it == "https://a.test/d.vtt" }
        assertEquals(listOf("https://a.test/b.srt", "https://a.test/c.ssa"), unloaded.map { it.url })
        assertEquals(listOf("English 2", "English 3"), SubtitleOffers.names(loaded.map { it.language }, unloaded))
        assertEquals("application/x-subrip", SubtitleOffers.mime("https://a.test/b.SRT?x=1"))
        assertEquals("text/x-ssa", SubtitleOffers.mime("https://a.test/c.ssa"))
        assertEquals("text/vtt", SubtitleOffers.mime("https://a.test/sub"))
    }
}
