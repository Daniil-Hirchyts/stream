package tv.stream.app

import org.junit.Assert.*
import org.junit.Test

class WatchCompletionTest {
    @Test fun twoMinutesOnlyOffersAChoiceNearTheEnd() {
        assertTrue(WatchCompletion.offerOnExit(38 * 60_000L, 40 * 60_000L, false))
        assertFalse(WatchCompletion.offerOnExit(3 * 60_000L, 5 * 60_000L, false))
        assertFalse(WatchCompletion.offerOnExit(110 * 60_000L, 120 * 60_000L, false))
        assertTrue(WatchCompletion.offerOnExit(117 * 60_000L, 120 * 60_000L, false))
    }
    @Test fun unknownEndedAndAlreadyWatchedDoNotAsk() {
        assertFalse(WatchCompletion.offerOnExit(100_000L, 0L, false))
        assertFalse(WatchCompletion.offerOnExit(600_000L, 600_000L, false))
        assertFalse(WatchCompletion.offerOnExit(590_000L, 600_000L, true))
        assertFalse(WatchCompletion.offerOnExit(29_000L, 30_000L, false))
    }
}
