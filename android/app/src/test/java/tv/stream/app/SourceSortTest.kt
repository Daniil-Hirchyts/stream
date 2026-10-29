package tv.stream.app

import java.util.Arrays
import java.util.HashMap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.stream.app.addon.MediaSource
import tv.stream.app.data.TvStore

/** Sorting reads only the advertised text; sources that say nothing keep their best-first place. Made-up hashes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourceSortTest {
    @Test fun releaseLabelsUseAdvertisedHintsWithoutConfusingFileExtensions() {
        assertEquals("CAM", SourceSort.releaseType("Source", "Film.2026.HDCAM.1080p.mkv", null))
        assertEquals("TS", SourceSort.releaseType("Source", "Film.2026.HD-TS.1080p.mkv", null))
        assertEquals("WEB-DL", SourceSort.releaseType("Source", "", "Film.S01E02.1080p.WEB-DL.mkv"))
        assertEquals("WEBRip", SourceSort.releaseType("WEBRip 720p", "", null))
        assertEquals("Blu-ray", SourceSort.releaseType("Source", "Film.1080p.BluRay.x264", null))
        assertEquals("DVD", SourceSort.releaseType("Source", "Film.DVDRip", null))
        assertEquals("HDTV", SourceSort.releaseType("Source", "Film.HDTV", null))
        assertEquals("", SourceSort.releaseType("Source", "", "Film.1080p.ts"))
        assertEquals("", SourceSort.releaseType("Source", "Film.1080p.HEVC.x265", null))
    }
    @Test fun readsQualitySizeAndSeedsAsTorrentAddonsWriteThem() {
        val torrentio = SourceSort.facts("Torrentio\n1080p", "Film.2019.1080p.x264\n👤 42 💾 2.1 GB ⚙️ ThePirateBay", null, 0)
        assertEquals(1080, torrentio.lines)
        assertEquals((2.1 * (1L shl 30)).toLong(), torrentio.bytes)
        assertEquals(42, torrentio.seeds)
        val other = SourceSort.facts("[TPB] 720p", "Seeders: 7 | Size: 700 MB", null, 0)
        assertEquals(720, other.lines)
        assertEquals(700L shl 20, other.bytes)
        assertEquals(7, other.seeds)
        assertEquals(12, SourceSort.facts("Meteor", "\uD83D\uDCC4 Film.mkv\n\uD83D\uDC65 12  \uD83D\uDCE6 1 GB", null, 0).seeds)
        assertEquals(3, SourceSort.facts("Comet", "\uD83C\uDF31 3", null, 0).seeds)
        val silent = SourceSort.facts("Direct", "", null, 123)
        assertEquals(0, silent.lines)
        assertEquals("", silent.quality())
        assertEquals("Advertised size wins", 123L, silent.bytes)
        assertEquals(-1, silent.seeds)
    }

    @Test @Throws(Exception::class) fun ordersBreakTiesByRankAndPlayedPutsTheMostRecentFirst() {
        val ranked: List<MediaSource> = MediaSource.list("p", JSONObject().put("streams", JSONArray()
                .put(stream("A 1080p", "👤 5 💾 1 GB", "1"))
                .put(stream("B 720p", "👤 90 💾 4 GB", "2"))
                .put(stream("C", "", "3"))
                .put(stream("D 1080p", "👤 5", "4"))))
        assertEquals(Arrays.asList("A 1080p", "D 1080p", "B 720p", "C"), names(SourceSort.sort(ranked, SourceSort.Order.QUALITY, false, HashMap())))
        assertEquals(Arrays.asList("B 720p", "A 1080p", "C", "D 1080p"), names(SourceSort.sort(ranked, SourceSort.Order.SIZE, false, HashMap())))
        assertEquals(Arrays.asList("B 720p", "A 1080p", "D 1080p", "C"), names(SourceSort.sort(ranked, SourceSort.Order.SEEDS, false, HashMap())))
        assertEquals(names(ranked), names(SourceSort.sort(ranked, SourceSort.Order.BEST, false, HashMap())))
        // Reversed: smallest first, and a source with no size still comes last.
        assertEquals(Arrays.asList("A 1080p", "B 720p", "C", "D 1080p"), names(SourceSort.sort(ranked, SourceSort.Order.SIZE, true, HashMap())))
        assertEquals(Arrays.asList("B 720p", "A 1080p", "D 1080p", "C"), names(SourceSort.sort(ranked, SourceSort.Order.QUALITY, true, HashMap())))
        assertEquals(Arrays.asList("D 1080p", "C", "B 720p", "A 1080p"), names(SourceSort.sort(ranked, SourceSort.Order.BEST, true, HashMap())))
        val marks: MutableMap<String, TvStore.SourceMark> = HashMap()
        marks.put(SourcePolicy.key(ranked.get(2)), TvStore.SourceMark(TvStore.SourceMark.PLAYED, 10L))
        marks.put(SourcePolicy.key(ranked.get(3)), TvStore.SourceMark(TvStore.SourceMark.PLAYED, 20L))
        marks.put(SourcePolicy.key(ranked.get(1)), TvStore.SourceMark(TvStore.SourceMark.FAILED, 30L))
        assertEquals(Arrays.asList("D 1080p", "C", "A 1080p", "B 720p"), names(SourceSort.sort(ranked, SourceSort.Order.PLAYED, false, marks)))
        assertEquals(Arrays.asList("C", "D 1080p", "A 1080p", "B 720p"), names(SourceSort.sort(ranked, SourceSort.Order.PLAYED, true, marks)))
    }

    companion object {
        @Throws(Exception::class)
        private fun stream(name: String, description: String, hashDigit: String): JSONObject {
            return JSONObject().put("name", name).put("description", description).put("infoHash", hashDigit.repeat(40))
        }
        private fun names(sources: List<MediaSource>): List<String> { return sources.stream().map { s -> s.name }.collect(java.util.stream.Collectors.toList()) }
    }
}
