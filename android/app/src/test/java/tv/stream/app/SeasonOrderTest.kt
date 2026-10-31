package tv.stream.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stream.app.addon.Title

/** Specials follow the numbered seasons, and are never the season a series opens on unless nothing else exists. */
class SeasonOrderTest {
    private fun series(vararg episodes: Pair<Int, Int>): Title {
        val videos = JSONArray()
        for ((s, e) in episodes) videos.put(JSONObject().put("id", "tt1:$s:$e").put("title", "Episode $e").put("season", s).put("episode", e))
        return requireNotNull(Title.parse("fixture", JSONObject().put("id", "tt1").put("type", "series").put("name", "Series").put("videos", videos)))
    }

    @Test fun specialsComeAfterTheLastNumberedSeason() {
        val title = series(0 to 1, 0 to 2, 2 to 1, 1 to 1, 1 to 2, 3 to 1)
        assertEquals(listOf(1, 2, 3, 0), TitleScreens.seasonOrder(title.episodes))
    }

    @Test fun seriesWithoutSpecialsKeepNumericOrder() {
        assertEquals(listOf(1, 2, 10), TitleScreens.seasonOrder(series(10 to 1, 2 to 1, 1 to 1).episodes))
    }

    @Test fun defaultIsTheSelectedEpisodesSeason() {
        val title = series(0 to 1, 1 to 1, 2 to 1, 2 to 2)
        val order = TitleScreens.seasonOrder(title.episodes)
        assertEquals(2, TitleScreens.defaultSeason(title.episodes, "tt1:2:2", order))
        // A special being resumed or chosen keeps its own tab.
        assertEquals(0, TitleScreens.defaultSeason(title.episodes, "tt1:0:1", order))
    }

    @Test fun withoutASelectionSeasonOneWinsOverSpecials() {
        val title = series(0 to 1, 1 to 1, 2 to 1)
        assertEquals(1, TitleScreens.defaultSeason(title.episodes, "tt1", TitleScreens.seasonOrder(title.episodes)))
        // The first episode to start without progress is numbered, so the Play button never opens on Specials.
        assertEquals("tt1:1:1", NewEpisodes.order(title).first().id)
    }

    @Test fun specialsAloneAreSelected() {
        val title = series(0 to 1, 0 to 2)
        val order = TitleScreens.seasonOrder(title.episodes)
        assertEquals(listOf(0), order)
        assertEquals(0, TitleScreens.defaultSeason(title.episodes, "tt1", order))
    }
}
