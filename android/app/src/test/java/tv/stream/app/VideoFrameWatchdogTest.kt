package tv.stream.app

import org.junit.Assert.*
import org.junit.Test

class VideoFrameWatchdogTest {
    @Test fun frozenVideoIsDetectedAfterItPreviouslyAdvanced() {
        val watchdog = VideoFrameWatchdog(); watchdog.reset(1000L, 0L, 0L)
        assertFalse(watchdog.unhealthy(3000L, 48L, 0L, true, false))
        assertFalse(watchdog.unhealthy(4000L, 72L, 0L, true, false))
        assertFalse(watchdog.unhealthy(6000L, 72L, 0L, true, false))
        assertTrue(watchdog.unhealthy(6001L, 72L, 0L, true, false))
    }
    @Test fun bufferingPausingAndSeekSettlingDoNotLookLikeSlowDecoding() {
        val watchdog = VideoFrameWatchdog(); watchdog.reset(1000L, 0L, 0L)
        assertFalse(watchdog.unhealthy(20_000L, 0L, 0L, false, false))
        assertFalse(watchdog.unhealthy(21_000L, 0L, 0L, true, false))
        watchdog.reset(22_000L, 0L, 0L); assertFalse(watchdog.unhealthy(23_000L, 0L, 0L, true, false))
    }
    @Test fun consistentlyDroppedFramesRequireTwoMeasurementWindows() {
        val watchdog = VideoFrameWatchdog(); watchdog.reset(1000L, 0L, 0L)
        assertFalse(watchdog.unhealthy(3000L, 48L, 0L, true, false))
        assertFalse(watchdog.unhealthy(8000L, 100L, 50L, true, false))
        assertTrue(watchdog.unhealthy(13_000L, 152L, 100L, true, false))
    }
    @Test fun endedVideoAllowsAnArbitrarilyLongAudioOrSubtitleTail() {
        val watchdog = VideoFrameWatchdog(); watchdog.reset(1000L, 0L, 0L)
        assertFalse(watchdog.unhealthy(3000L, 48L, 0L, true, false))
        var now = 4000L
        while (now < 120_000L) {
            assertFalse(watchdog.unhealthy(now, 48L, 0L, true, true))
            now += 1000L
        }
    }
    @Test fun seekingBackFromTheTailRearmsFrozenVideoDetection() {
        val watchdog = VideoFrameWatchdog(); watchdog.reset(1000L, 0L, 0L)
        assertFalse(watchdog.unhealthy(20_000L, 48L, 0L, true, true))
        watchdog.reset(21_000L, 48L, 0L)
        assertFalse(watchdog.unhealthy(23_000L, 72L, 0L, true, false))
        assertTrue(watchdog.unhealthy(25_001L, 72L, 0L, true, false))
    }
}
