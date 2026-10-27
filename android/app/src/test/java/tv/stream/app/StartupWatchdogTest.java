package tv.stream.app;

import static org.junit.Assert.*;
import org.junit.Test;

public final class StartupWatchdogTest {
    @Test public void aBriefHttpRebufferAfterLongPlaybackDoesNotFailImmediately() {
        assertFalse(StartupWatchdog.bufferingStalled(true, 300_000, 300_000, 301_000, 6000));
        assertTrue(StartupWatchdog.bufferingStalled(true, 300_000, 300_000, 306_000, 6000));
        assertFalse(StartupWatchdog.bufferingStalled(true, 300_000, 1000, 306_000, 6000));
        assertFalse(StartupWatchdog.bufferingStalled(false, 300_000, 300_000, 306_000, 6000));
    }

    @Test public void unchangedCountersDoNotResetUsefulProgress() {
        StartupWatchdog watchdog = new StartupWatchdog(); watchdog.start(1000);
        watchdog.observe(1024, 2000); watchdog.observe(1024, 7000);
        assertEquals(5000, watchdog.quietMs(7000));
        watchdog.observe(2048, 7500); assertEquals(0, watchdog.quietMs(7500));
    }
    @Test public void pauseFreezesTheUsefulProgressWindow() {
        StartupWatchdog watchdog = new StartupWatchdog(); watchdog.start(1000); watchdog.observe(1024, 2000);
        watchdog.setPaused(true, 3000); assertEquals(0, watchdog.quietMs(10000));
        watchdog.observe(2048, 10000); watchdog.setPaused(false, 23000);
        assertEquals(2000, watchdog.quietMs(24000));
    }
}
