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

    /** A representative long series: 30 seasons, 1500 episodes; each options call is one tile model built. */
    private fun counted(built: java.util.concurrent.atomic.AtomicInteger): tv.stream.app.ui.LazyPanes {
        val long = JSONArray()
        for (s in 1..30) for (e in 1..50) long.put(JSONObject().put("id", "tt2:$s:$e").put("title", "Episode $e").put("season", s).put("episode", e))
        val title = requireNotNull(Title.parse("fixture", JSONObject().put("id", "tt2").put("type", "series").put("name", "Long").put("videos", long)))
        return TitleScreens.seasonPanes(title, (1..30).toList(), emptyMap(), emptySet()) { _, _, _ -> built.incrementAndGet(); Runnable {} }
    }

    @Test fun openingBuildsNoTilesAndOneSeasonBuildsOnlyItsOwn() {
        val built = java.util.concurrent.atomic.AtomicInteger()
        val p = counted(built)
        // Everything a render does before showing a season: diff, focus lookup, snapshot, inspection.
        val item = Item.episodes(emptyList(), p, "season:1", null).snapshot()
        assertTrue(p.rowKeys.contains("episode:tt2:30:50")); assertEquals(30, p.size)
        assertTrue(p.containsKey("season:30")); p.keys.toList(); p.toString(); p.hashCode()
        assertEquals(p, counted(java.util.concurrent.atomic.AtomicInteger()))
        assertEquals("no tile model on open", 0, built.get())
        assertEquals(0, p.builtCount)
        assertEquals(50, item.panes!!["season:1"]!!.single().tiles.size)
        assertEquals(50, built.get())
    }

    @Test fun rapidSeasonChangesBuildEachVisitedSeasonOnce() {
        val built = java.util.concurrent.atomic.AtomicInteger()
        val p = counted(built)
        val visits = listOf(1, 2, 3, 2, 1, 4, 5, 4, 3, 2, 1, 1, 5)
        val first = p["season:3"]
        for (season in visits) p["season:$season"]
        assertEquals("five distinct seasons, none of the other 25", 250, built.get())
        assertEquals(5, p.builtCount)
        assertTrue("a repeated season is the same models", first === p["season:3"])
    }

    @Test fun abandoningTheScreenBuildsNothingMore() {
        val built = java.util.concurrent.atomic.AtomicInteger()
        val p = counted(built)
        p["season:1"]
        val atLeave = built.get()
        Thread.sleep(50) // Nothing in the background carries on building after the viewer leaves.
        assertEquals(atLeave, built.get())
        assertEquals(1, p.builtCount)
    }

    @Test fun concurrentRequestsForASeasonBuildItOnce() {
        val built = java.util.concurrent.atomic.AtomicInteger()
        val p = counted(built)
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (0 until 8).map { kotlin.concurrent.thread { start.await(); p["season:9"] } }
        start.countDown(); threads.forEach { it.join(5000) }
        assertEquals(50, built.get())
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
