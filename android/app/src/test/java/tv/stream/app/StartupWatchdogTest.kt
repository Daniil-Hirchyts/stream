package tv.stream.app

import org.junit.Assert.*
import org.junit.Test

class StartupWatchdogTest {
    @Test fun aBriefHttpRebufferAfterLongPlaybackDoesNotFailImmediately() {
        assertFalse(StartupWatchdog.bufferingStalled(true, 300_000L, 300_000L, 301_000L, 6000L))
        assertTrue(StartupWatchdog.bufferingStalled(true, 300_000L, 300_000L, 306_000L, 6000L))
        assertFalse(StartupWatchdog.bufferingStalled(true, 300_000L, 1000L, 306_000L, 6000L))
        assertFalse(StartupWatchdog.bufferingStalled(false, 300_000L, 300_000L, 306_000L, 6000L))
    }

    @Test fun unchangedCountersDoNotResetUsefulProgress() {
        val watchdog = StartupWatchdog(); watchdog.start(1000L)
        watchdog.observe(1024L, 2000L); watchdog.observe(1024L, 7000L)
        assertEquals(5000L, watchdog.quietMs(7000L))
        watchdog.observe(2048L, 7500L); assertEquals(0L, watchdog.quietMs(7500L))
    }
    @Test fun pauseFreezesTheUsefulProgressWindow() {
        val watchdog = StartupWatchdog(); watchdog.start(1000L); watchdog.observe(1024L, 2000L)
        watchdog.setPaused(true, 3000L); assertEquals(0L, watchdog.quietMs(10000L))
        watchdog.observe(2048L, 10000L); watchdog.setPaused(false, 23000L)
        assertEquals(2000L, watchdog.quietMs(24000L))
    }
}
