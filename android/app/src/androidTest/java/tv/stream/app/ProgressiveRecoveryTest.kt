package tv.stream.app

import org.junit.Assert.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URLEncoder
import org.junit.Test
import org.junit.runner.RunWith
import tv.stream.app.addon.Cancellation

/** A failed fast source must not discard a healthy provider that is still resolving. */
@RunWith(AndroidJUnit4::class)
class ProgressiveRecoveryTest : AppHarness() {
    @Test @Throws(Exception::class) fun automaticPlaybackWaitsForALateHealthyProvider() {
        StartupPipelineTest.Loopback().use { server ->
            server.mediaRedirect = origin + "/media/" + URLEncoder.encode("Stream Test Film (2026).mp4", "UTF-8").replace("+", "%20")
            for (name in arrayOf("fast", "slow")) services.addons.newRequest().use { request ->
                val url = server.origin + "/" + name + "/manifest.json"; services.addons.install(url, services.addons.preview(url, request))
            }
            launch(); startAutomaticFixturePlayback()
            AppHarness.waitUntil("fast source fails while discovery continues", 10000) { diagnostics().optJSONArray("sourceFailures") != null && diagnostics().optJSONArray("sourceFailures").length() == 1 }
            assertNull("Play must keep waiting rather than show a terminal error", state()!!.error)
            server.unblock.countDown()
            AppHarness.waitUntil("late healthy provider renders", 30000) { state() != null && state()!!.error == null && state()!!.renderedFirstFrame && state()!!.playing }
            assertEquals(2, diagnostics().getInt("sourceAttempts").toLong()); assertEquals(1, diagnostics().getJSONArray("sourceFailures").length().toLong())
            val activity = watch(); instrumentation.runOnMainSync(activity!!::finish)
            AppHarness.waitUntil("player closes", 10000) { watch() == null }
        }
    }
}
