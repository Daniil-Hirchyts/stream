package tv.stream.app

import org.junit.Assert.*
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** The host kills the actual process while the first phase is playing, then runs the second phase. */
@RunWith(AndroidJUnit4::class)
class ProcessDeathTest : AppHarness() {
    @Test @Throws(Exception::class) fun persistedResumeAndOrphanCacheRecovery() {
        val phase = InstrumentationRegistry.getArguments().getString("phase", "")
        assertTrue("Use simulator/process-death.sh", phase == "prepare" || phase == "resume")
        if (phase == "prepare") {
            playFilm(); val activity = watch()
            instrumentation.runOnMainSync { AppFlowTest.session(activity).seekTo(31_000) }
            AppHarness.waitUntil("durable progress before death", 20_000) { state()!!.playing && !services.store.history(true).isEmpty() && services.store.history(true)[0].positionMs >= 31_000 }
            val ready = File(context.filesDir, "validation-death-ready.json")
            Files.write(ready.toPath(), JSONObject().put("database", database).put("positionMs", services.store.history(true)[0].positionMs).toString().toByteArray(StandardCharsets.UTF_8))
            SystemClock.sleep(120_000); fail("The host did not terminate the prepared process")
        } else {
            assertEquals(1, services.store.history(true).size.toLong()); assertTrue(services.store.history(true)[0].positionMs >= 31_000)
            val orphaned = File(context.cacheDir, "payload").listFiles(); assertTrue("Startup recovered orphan payload", orphaned == null || orphaned.size == 0)
            launch(); tap("Generated Film", true); tap("Resume", false); chooseFirstSource()
            AppHarness.waitUntil("real playback resumes after process death", 45_000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing && state()!!.positionMs >= 31_000 }
        }
    }
}
