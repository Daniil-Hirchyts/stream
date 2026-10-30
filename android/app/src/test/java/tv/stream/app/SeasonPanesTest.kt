package tv.stream.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stream.app.addon.Title
import tv.stream.app.data.TvStore
import tv.stream.app.ui.Item

/** A long series builds only the season on screen; the others cost a signature, not tiles. */
class SeasonPanesTest {
    private val seasons = 40
    private val perSeason = 50

    private fun series(): Title {
        val videos = JSONArray()
        for (s in 1..seasons) for (e in 1..perSeason) {
            videos.put(JSONObject().put("id", "tt1:$s:$e").put("title", "Episode $e").put("season", s).put("episode", e)
                    .put("released", "2020-01-01T00:00:00.000Z").put("overview", "Overview of S$s E$e"))
        }
        val meta = JSONObject().put("id", "tt1").put("type", "series").put("name", "Long series").put("videos", videos)
        return requireNotNull(Title.parse("fixture", meta))
    }

    private fun panes(title: Title, saved: Map<String, TvStore.Progress> = emptyMap()) =
            TitleScreens.seasonPanes(title, (1..seasons).toList(), saved, emptySet()) { _, _, _ -> Runnable {} }

    @Test fun onlyTheShownSeasonIsBuilt() {
        val p = panes(series())
        assertEquals(seasons, p.size)
        assertEquals(0, p.builtCount)
        val shelf = p["season:7"]!!.single()
        assertEquals(perSeason, shelf.tiles.size)
        assertEquals("episode:tt1:7:1", shelf.tiles.first().key)
        assertEquals(1, p.builtCount)
        assertTrue("focus lookups know every episode", p.rowKeys.contains("episode:tt1:39:50"))
        assertEquals(1, p.builtCount)
        // Item snapshots publish the same lazy panes rather than building them.
        val item = Item.episodes(emptyList(), p, "season:7", null)
        assertTrue(item.snapshot().panes === p)
    }

    @Test fun progressChangesTheSignatureSoTheScreenRedraws() {
        val title = series()
        val before = panes(title)
        val watched = mapOf("tt1:3:4" to TvStore.Progress("series:tt1", "tt1:3:4", 0, 1000, true, 1))
        val after = panes(title, watched)
        assertNotEquals(before.signature, after.signature)
        assertEquals(before.signature, panes(title).signature)
        assertTrue(after["season:3"]!!.single().tiles[3].badge != 0)
        val firstDuration = panes(title, mapOf("tt1:3:4" to TvStore.Progress("series:tt1", "tt1:3:4", 500, 1000, false, 1)))
        val secondDuration = panes(title, mapOf("tt1:3:4" to TvStore.Progress("series:tt1", "tt1:3:4", 500, 2000, false, 1)))
        assertNotEquals(firstDuration.signature, secondDuration.signature)
    }

    @Test fun buildingOnDemandIsCheaperThanBuildingEverySeason() {
        val title = series()
        repeat(20) { panes(title).let { p -> p.keys.forEach { k -> p[k] } } } // warm up
        fun time(block: () -> Unit): Long {
            val start = System.nanoTime(); repeat(50) { block() }; return (System.nanoTime() - start) / 50
        }
        val eager = time { panes(title).let { p -> p.keys.forEach { k -> p[k] } } }
        val lazy = time { panes(title)["season:1"] }
        println("SeasonPanes: 2000 episodes, every season ${eager / 1000} us, the shown season ${lazy / 1000} us")
        assertTrue("on demand ($lazy ns) must beat building all ($eager ns)", lazy * 3 < eager)
    }
}
