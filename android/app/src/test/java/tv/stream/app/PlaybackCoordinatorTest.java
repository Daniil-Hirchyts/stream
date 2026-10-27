package tv.stream.app;

import static org.junit.Assert.*;
import android.os.SystemClock;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class PlaybackCoordinatorTest {
    @Test public void anAttemptStartedDuringPauseDoesNotSubtractEarlierPausedTime() {
        PlaybackCoordinator coordinator = new PlaybackCoordinator(); coordinator.begin(SystemClock.elapsedRealtime());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); coordinator.paused(true, SystemClock.elapsedRealtime());
        ShadowSystemClock.advanceBy(Duration.ofHours(2)); coordinator.attempt();
        assertEquals(0, coordinator.attemptElapsed(SystemClock.elapsedRealtime()));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30)); assertEquals(0, coordinator.attemptElapsed(SystemClock.elapsedRealtime()));
        coordinator.paused(false, SystemClock.elapsedRealtime()); ShadowSystemClock.advanceBy(Duration.ofSeconds(4));
        assertEquals(4000, coordinator.attemptElapsed(SystemClock.elapsedRealtime()));
        assertEquals(5000, coordinator.elapsed(SystemClock.elapsedRealtime()));
        coordinator.recover(SystemClock.elapsedRealtime()); ShadowSystemClock.advanceBy(Duration.ofSeconds(60));
        assertTrue(coordinator.expired(SystemClock.elapsedRealtime()));
    }
}
