package tv.daniil.stream;

import static org.junit.Assert.*;
import org.junit.Test;

public final class VideoFrameWatchdogTest {
    @Test public void frozenVideoIsDetectedAfterItPreviouslyAdvanced() {
        VideoFrameWatchdog watchdog = new VideoFrameWatchdog(); watchdog.reset(1000, 0, 0);
        assertFalse(watchdog.unhealthy(3000, 48, 0, true));
        assertFalse(watchdog.unhealthy(4000, 72, 0, true));
        assertFalse(watchdog.unhealthy(6000, 72, 0, true));
        assertTrue(watchdog.unhealthy(6001, 72, 0, true));
    }
    @Test public void bufferingPausingAndSeekSettlingDoNotLookLikeSlowDecoding() {
        VideoFrameWatchdog watchdog = new VideoFrameWatchdog(); watchdog.reset(1000, 0, 0);
        assertFalse(watchdog.unhealthy(20_000, 0, 0, false));
        assertFalse(watchdog.unhealthy(21_000, 0, 0, true));
        watchdog.reset(22_000, 0, 0); assertFalse(watchdog.unhealthy(23_000, 0, 0, true));
    }
    @Test public void consistentlyDroppedFramesRequireTwoMeasurementWindows() {
        VideoFrameWatchdog watchdog = new VideoFrameWatchdog(); watchdog.reset(1000, 0, 0);
        assertFalse(watchdog.unhealthy(3000, 48, 0, true));
        assertFalse(watchdog.unhealthy(8000, 100, 50, true));
        assertTrue(watchdog.unhealthy(13_000, 152, 100, true));
    }
}
