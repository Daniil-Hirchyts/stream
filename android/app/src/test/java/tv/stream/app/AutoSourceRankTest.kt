package tv.stream.app

import org.junit.Assert.*
import org.junit.Test

class AutoSourceRankTest {
    private fun rank(lines: Int, peers: Int, mib: Long): Int { return AutoSourceRank.score(SourceSort.Facts(lines, mib shl 20, peers), 0) }
    @Test fun balancesPeersQualityAndSizeInsteadOfTreatingOneAndHundredsOfPeersEqually() {
        assertTrue(rank(720, 250, 900) > rank(1080, 3, 900))
        assertTrue(rank(1080, 200, 1400) > rank(1080, 220, 18000))
        assertTrue(rank(1080, 50, 1200) > rank(720, 50, 1200))
        assertTrue(rank(720, 10, 1000) > rank(480, 1000, 500))
        assertTrue(rank(1080, -1, 1000) > rank(1080, 0, 1000))
        assertTrue(rank(1080, 100, 1000) > rank(1080, 100, 0))
    }
    @Test fun failedSourcesLoseAndMissingFactsRemainUsableFallbacks() {
        assertTrue(AutoSourceRank.score(SourceSort.Facts(1080, 1L shl 30, 1000), -3000) < rank(0, -1, 0))
        assertTrue(rank(0, 50, 1200) > rank(0, 1, 1200))
        assertEquals(48, SourceSort.facts("720p", "Peers: 48", null, 0).seeds)
        assertEquals(Integer.MAX_VALUE, SourceSort.facts("720p", "Seeds: 999999999999999999999", null, 0).seeds)
    }
}
