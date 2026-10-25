package tv.daniil.stream;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.net.URLEncoder;
import org.junit.Test;
import org.junit.runner.RunWith;
import tv.daniil.stream.addon.Cancellation;

/** A failed fast source must not discard a healthy provider that is still resolving. */
@RunWith(AndroidJUnit4.class)
public final class ProgressiveRecoveryTest extends AppHarness {
    @Test public void automaticPlaybackWaitsForALateHealthyProvider() throws Exception {
        try (StartupPipelineTest.Loopback server = new StartupPipelineTest.Loopback()) {
            server.mediaRedirect = origin + "/streaming/media/" + URLEncoder.encode("Daniil Test Film (2026).mp4", "UTF-8").replace("+", "%20");
            for (String name : new String[]{"fast", "slow"}) try (Cancellation request = services.addons.newRequest()) {
                String url = server.origin + "/" + name + "/manifest.json"; services.addons.install(url, services.addons.preview(url, request));
            }
            launch(); startAutomaticFixturePlayback();
            waitUntil("fast source fails while discovery continues", 10000, () -> diagnostics().optJSONArray("sourceFailures") != null && diagnostics().optJSONArray("sourceFailures").length() == 1);
            assertNull("Play must keep waiting rather than show a terminal error", state().error);
            server.unblock.countDown();
            waitUntil("late healthy provider renders", 30000, () -> state() != null && state().error == null && state().renderedFirstFrame && state().playing);
            assertEquals(2, diagnostics().getInt("sourceAttempts")); assertEquals(1, diagnostics().getJSONArray("sourceFailures").length());
            WatchActivity activity = watch(); instrumentation.runOnMainSync(activity::finish);
            waitUntil("player closes", 10000, () -> watch() == null);
        }
    }
}
