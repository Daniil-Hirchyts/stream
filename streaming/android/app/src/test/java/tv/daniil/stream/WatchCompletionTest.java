package tv.daniil.stream;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WatchCompletionTest {
    @Test public void twoMinutesOnlyOffersAChoiceNearTheEnd() {
        assertTrue(WatchCompletion.offerOnExit(38 * 60_000L, 40 * 60_000L, false));
        assertFalse(WatchCompletion.offerOnExit(3 * 60_000L, 5 * 60_000L, false));
        assertFalse(WatchCompletion.offerOnExit(110 * 60_000L, 120 * 60_000L, false));
        assertTrue(WatchCompletion.offerOnExit(117 * 60_000L, 120 * 60_000L, false));
    }
    @Test public void unknownEndedAndAlreadyWatchedDoNotAsk() {
        assertFalse(WatchCompletion.offerOnExit(100_000, 0, false));
        assertFalse(WatchCompletion.offerOnExit(600_000, 600_000, false));
        assertFalse(WatchCompletion.offerOnExit(590_000, 600_000, true));
        assertFalse(WatchCompletion.offerOnExit(29_000, 30_000, false));
    }
}
