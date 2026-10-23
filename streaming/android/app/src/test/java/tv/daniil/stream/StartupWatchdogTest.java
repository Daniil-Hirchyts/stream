package tv.daniil.stream;

import static org.junit.Assert.*;
import org.junit.Test;

public final class StartupWatchdogTest {
    @Test public void silentSwarmExpiresAfterOneMinute() {
        StartupWatchdog watchdog = new StartupWatchdog(); watchdog.start(1000);
        assertFalse(watchdog.expired(61_000)); assertTrue(watchdog.expired(61_001));
    }
    @Test public void transferProgressExtendsStartupWithinThreeMinuteLimit() {
        StartupWatchdog watchdog = new StartupWatchdog(); watchdog.start(1000);
        for (int i = 1; i <= 3; i++) {
            watchdog.observe(i * 1024L, 1000 + i * 50_000L);
            assertFalse(watchdog.expired(1000 + i * 50_000L));
        }
        assertFalse(watchdog.expired(181_000)); assertTrue(watchdog.expired(181_001));
    }
    @Test public void unchangedCountersDoNotExtendTheWaitAndNewLoadsResetIt() {
        StartupWatchdog watchdog = new StartupWatchdog(); watchdog.start(1000);
        watchdog.observe(1024, 20_000); watchdog.observe(1024, 70_000);
        assertTrue(watchdog.expired(80_001));
        watchdog.start(90_000); assertFalse(watchdog.expired(90_001));
        watchdog.observe(1, 140_000); assertFalse(watchdog.expired(180_000));
    }
    @Test public void pausingSuspendsBothDeadlinesUntilPlaybackResumes() {
        StartupWatchdog watchdog = new StartupWatchdog(); watchdog.start(1000);
        watchdog.setPaused(true, 21_000);
        assertFalse(watchdog.expired(400_000));
        watchdog.setPaused(false, 421_000);
        assertFalse(watchdog.expired(461_000)); assertTrue(watchdog.expired(461_001));
    }
    @Test public void progressWhilePausedDoesNotExtendTheRemainingActiveWait() {
        StartupWatchdog watchdog = new StartupWatchdog(); watchdog.start(1000);
        watchdog.setPaused(true, 21_000); watchdog.observe(4096, 400_000);
        watchdog.setPaused(false, 421_000);
        assertTrue(watchdog.expired(461_001));
        watchdog.start(500_000); assertTrue(watchdog.expired(560_001));
    }
}
