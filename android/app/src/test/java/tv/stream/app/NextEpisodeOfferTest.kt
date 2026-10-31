package tv.stream.app

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tv.stream.app.addon.Title

class NextEpisodeOfferTest {
    @Test fun offersOnlyNearAnActualKnownEnding() {
        assertFalse(NextEpisodeOffer.nearEnd(0L, 0L))
        assertFalse(NextEpisodeOffer.nearEnd(50_000L, 0L))
        assertFalse(NextEpisodeOffer.nearEnd(1_200_000L, 2_400_000L))
        assertFalse(NextEpisodeOffer.nearEnd(2_300_000L, 2_400_000L))
        assertTrue(NextEpisodeOffer.nearEnd(2_310_000L, 2_400_000L))
        assertTrue(NextEpisodeOffer.nearEnd(54_000L, 60_000L))
        assertFalse(NextEpisodeOffer.nearEnd(60_000L, 60_000L))
    }

    @Test fun offersOnlyAReleasedNextEpisode() {
        val now = Instant.parse("2026-10-31T12:00:00Z").toEpochMilli()
        assertFalse("No following episode", NextEpisodeOffer.released(null, now))
        assertTrue("Aired yesterday", NextEpisodeOffer.released(episode("e1", released = "2026-10-30T20:00:00.000Z"), now))
        assertTrue("A date alone airs at midnight UTC that day", NextEpisodeOffer.released(episode("e2", released = "2026-10-31"), now))
        assertTrue("Airing exactly now", NextEpisodeOffer.released(episode("e3", released = "2026-10-31T12:00:00Z"), now))
        assertFalse("Announced for tonight", NextEpisodeOffer.released(episode("e4", released = "2026-10-31T21:00:00.000Z"), now))
        assertFalse("Announced for next week", NextEpisodeOffer.released(episode("e5", released = "2026-11-07"), now))
        // Providers without dates (and imports) keep offering their next episode, as Next Up does.
        assertTrue("No release date", NextEpisodeOffer.released(episode("e6", released = null), now))
        assertTrue("Unreadable release date", NextEpisodeOffer.released(episode("e7", released = "soon"), now))
    }

    @Test fun loadingAndToolbarLabelsNameOnlyASeriesEpisode() {
        val episodes = listOf(episode("s2e6", season = 2, number = 6, name = "Queen of Hearts"),
                episode("s2e7", season = 2, number = 7, name = "Ace of Spades"))
        assertEquals("S2 E7 · Ace of Spades", NextEpisodeOffer.episodeLabel(episodes, "s2e7"))
        assertEquals("S2 E7 · Ace of Spades", NextEpisodeOffer.watchingLabel("series", episodes, "s2e7"))
        assertEquals("Films have no episode line", "", NextEpisodeOffer.watchingLabel("movie", episodes, "s2e7"))
        assertEquals("Unknown video", "", NextEpisodeOffer.watchingLabel("series", episodes, "missing"))
        assertEquals("No episodes yet", "", NextEpisodeOffer.watchingLabel("series", emptyList(), "s2e7"))
    }

    private fun episode(id: String, released: String? = "2020-01-01", season: Int = 1, number: Int = 1, name: String = "Episode"): Title.Episode {
        val o = JSONObject().put("id", id).put("title", name).put("season", season).put("episode", number)
        if (released != null) o.put("released", released)
        return Title.Episode("fixture", o)
    }
}
