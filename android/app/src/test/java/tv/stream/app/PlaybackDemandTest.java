package tv.stream.app;

import static org.junit.Assert.*;
import org.junit.Test;

public final class PlaybackDemandTest {
    @Test public void averageIncludesAllTracksAndPlaybackSpeedDoesNotBecomeBitrate() {
        PlaybackDemand demand = new PlaybackDemand();
        assertEquals(1_000_000, demand.estimate(60_000_000, 60_000, 0, 0, 1000));
        assertEquals(1_000_000, demand.estimate(60_000_000, 60_000, 10_000_000, 10_000, 6000));
    }
    @Test public void learnedDurationReplacesTheStartupAssumption() {
        PlaybackDemand demand = new PlaybackDemand();
        assertEquals(1_250_000, demand.estimate(60_000_000, 0, 0, 0, 1000));
        assertEquals(250_000, demand.estimate(60_000_000, 240_000, 0, 0, 2000));
    }
    @Test public void seeksDoNotTurnHeaderReadsIntoAnEnormousEstimate() {
        PlaybackDemand demand = new PlaybackDemand();
        demand.estimate(60_000_000, 60_000, 0, 0, 1000);
        demand.reset();
        assertEquals(1_000_000, demand.estimate(60_000_000, 60_000, 40_000_000, 40_000, 2000));
        assertEquals(5, PlaybackDemand.aheadSeconds(false, 0));
        assertEquals(30, PlaybackDemand.aheadSeconds(true, 15_000));
        assertEquals(5, PlaybackDemand.aheadSeconds(true, 100_000));
    }
}
