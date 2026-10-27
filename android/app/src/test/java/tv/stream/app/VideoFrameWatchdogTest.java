package tv.stream.app;

import static org.junit.Assert.*;
import org.junit.Test;

public final class VideoFrameWatchdogTest {
    @Test public void frozenVideoIsDetectedAfterItPreviouslyAdvanced() {
        VideoFrameWatchdog watchdog = new VideoFrameWatchdog(); watchdog.reset(1000, 0, 0);
        assertFalse(watchdog.unhealthy(3000, 48, 0, true, false));
        assertFalse(watchdog.unhealthy(4000, 72, 0, true, false));
        assertFalse(watchdog.unhealthy(6000, 72, 0, true, false));
        assertTrue(watchdog.unhealthy(6001, 72, 0, true, false));
    }
    @Test public void bufferingPausingAndSeekSettlingDoNotLookLikeSlowDecoding() {
        VideoFrameWatchdog watchdog = new VideoFrameWatchdog(); watchdog.reset(1000, 0, 0);
        assertFalse(watchdog.unhealthy(20_000, 0, 0, false, false));
        assertFalse(watchdog.unhealthy(21_000, 0, 0, true, false));
        watchdog.reset(22_000, 0, 0); assertFalse(watchdog.unhealthy(23_000, 0, 0, true, false));
    }
    @Test public void consistentlyDroppedFramesRequireTwoMeasurementWindows() {
        VideoFrameWatchdog watchdog = new VideoFrameWatchdog(); watchdog.reset(1000, 0, 0);
        assertFalse(watchdog.unhealthy(3000, 48, 0, true, false));
        assertFalse(watchdog.unhealthy(8000, 100, 50, true, false));
        assertTrue(watchdog.unhealthy(13_000, 152, 100, true, false));
    }
    @Test public void endedVideoAllowsAnArbitrarilyLongAudioOrSubtitleTail() {
        VideoFrameWatchdog watchdog = new VideoFrameWatchdog(); watchdog.reset(1000, 0, 0);
        assertFalse(watchdog.unhealthy(3000, 48, 0, true, false));
        for (long now = 4000; now < 120_000; now += 1000)
            assertFalse(watchdog.unhealthy(now, 48, 0, true, true));
    }
    @Test public void seekingBackFromTheTailRearmsFrozenVideoDetection() {
        VideoFrameWatchdog watchdog = new VideoFrameWatchdog(); watchdog.reset(1000, 0, 0);
        assertFalse(watchdog.unhealthy(20_000, 48, 0, true, true));
        watchdog.reset(21_000, 48, 0);
        assertFalse(watchdog.unhealthy(23_000, 72, 0, true, false));
        assertTrue(watchdog.unhealthy(25_001, 72, 0, true, false));
    }
}
