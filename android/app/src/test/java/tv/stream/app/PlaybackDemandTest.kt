package tv.stream.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackDemandTest {
    @Test fun averageIncludesAllTracksAndPlaybackSpeedDoesNotBecomeBitrate() {
        val demand = PlaybackDemand()
        assertEquals(1_000_000L, demand.estimate(60_000_000L, 60_000L, 0L, 0L, 1000L))
        assertEquals(1_000_000L, demand.estimate(60_000_000L, 60_000L, 10_000_000L, 10_000L, 6000L))
    }
    @Test fun learnedDurationReplacesTheStartupAssumption() {
        val demand = PlaybackDemand()
        assertEquals(1_250_000L, demand.estimate(60_000_000L, 0L, 0L, 0L, 1000L))
        assertEquals(250_000L, demand.estimate(60_000_000L, 240_000L, 0L, 0L, 2000L))
    }
    @Test fun seeksDoNotTurnHeaderReadsIntoAnEnormousEstimate() {
        val demand = PlaybackDemand()
        demand.estimate(60_000_000L, 60_000L, 0L, 0L, 1000L)
        demand.reset()
        assertEquals(1_000_000L, demand.estimate(60_000_000L, 60_000L, 40_000_000L, 40_000L, 2000L))
        assertEquals(5, PlaybackDemand.aheadSeconds(false, 0L))
        assertEquals(30, PlaybackDemand.aheadSeconds(true, 15_000L))
        assertEquals(5, PlaybackDemand.aheadSeconds(true, 100_000L))
    }
}
