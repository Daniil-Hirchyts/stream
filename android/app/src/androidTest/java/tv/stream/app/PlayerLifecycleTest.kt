package tv.stream.app

import org.junit.Assert.*
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Test
import tv.stream.playback.PlaybackSession
import tv.stream.playback.PlaybackSnapshot
import tv.stream.torrent.TorrentEngine

/** Actual system screensaver, sleep/wake and Home transitions with real generated torrent playback. */
class PlayerLifecycleTest : AppHarness() {
    @Test @Throws(Exception::class) fun pausedFilmSurvivesScreensaverAndSleepWithTheSameDownloadedCache() {
        playFilm(); val activity = watch()
        instrumentation.runOnMainSync {
            val player = AppFlowTest.session(activity)
            player.pause(); player.setSpeed(1.5f); player.seekTo(6000)
        }
        AppHarness.waitUntil("paused film ready", 10000) { !state()!!.playWhenReady && !state()!!.buffering && state()!!.positionMs >= 6000 }
        val warm = WarmPlayer(activity)
        try {
            // Android TV's exported sleep action starts its real configured screensaver without root.
            shell("am start -a com.google.android.pano.action.SLEEP")
            AppHarness.waitUntil("screensaver covers the player", 10000) { stopped(activity) }
            warm.assertRetained()
            shell("input keyevent KEYCODE_WAKEUP")
            AppHarness.waitUntil("player returns from screensaver", 10000) { watch() === activity }
            warm.assertReturned()

            shell("cmd power sleep")
            val power = context.getSystemService(PowerManager::class.java)
            AppHarness.waitUntil("TV sleeps with the player stopped", 10000) { !power!!.isInteractive && stopped(activity) }
            SystemClock.sleep(1500)
            warm.assertRetained()
            shell("cmd power wakeup")
            AppHarness.waitUntil("player returns after wake", 10000) { power!!.isInteractive && watch() === activity }
            warm.assertReturned()
            warm.resumeAndVerifyFrames()
            instrumentation.runOnMainSync(activity!!::finish)
            AppHarness.waitUntil("closing film releases downloaded cache", 10000) { !warm.payload.exists() }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
        }
    }

    @Test @Throws(Exception::class) fun homePausesPlaybackAndRetainsItUntilTheStoppedPlayerIsClosed() {
        playFilm(); val activity = watch()
        pressHome()
        AppHarness.waitUntil("Home stops the player", 10000) { stopped(activity) }
        val warm = WarmPlayer(activity)
        warm.assertRetained()
        assertFalse("Home pauses rather than playing in the background", snapshot(activity)!!.playWhenReady)
        shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n tv.stream.app/.MainActivity")
        AppHarness.waitUntil("launcher restores the same film", 10000) { watch() === activity }
        warm.assertReturned(); warm.resumeAndVerifyFrames()
        pressHome(); AppHarness.waitUntil("player stopped again", 10000) { stopped(activity) }
        instrumentation.runOnMainSync(activity!!::finish)
        AppHarness.waitUntil("finishing while stopped releases the cache", 10000) { !warm.payload.exists() }
    }

    private inner class WarmPlayer @Throws(Exception::class) constructor(activity: WatchActivity?) {
        @JvmField val activity: WatchActivity?
        @JvmField val player: PlaybackSession
        @JvmField val coordinator: PlaybackCoordinator
        @JvmField val engine: TorrentEngine?
        @JvmField val torrent: Long; @JvmField val position: Long; @JvmField val downloaded: Long; @JvmField val verified: Long
        @JvmField val speed: Float
        @JvmField val generation: Int; @JvmField val attempts: Int
        @JvmField val payload: File
        init {
            this.activity = activity; player = AppFlowTest.session(activity)
            val field = WatchActivity::class.java.getDeclaredField("coordinator"); field.isAccessible = true
            coordinator = field.get(activity) as PlaybackCoordinator; engine = coordinator.engine; torrent = coordinator.torrent
            assertNotNull("A real torrent engine is active", engine); assertTrue(torrent != 0L)
            val stats = stats(); downloaded = stats.getLong("totalDownload"); verified = stats.getLong("verifiedReadBytes")
            assertTrue("Actual torrent data was read", verified > 0); assertTrue(downloaded > 0)
            val files = File(context.cacheDir, "payload").listFiles()
            assertNotNull(files); assertEquals("One playback payload", 1, files!!.size.toLong()); payload = files[0]
            val state = snapshot(activity)!!; position = state.positionMs; speed = state.speed
            val revision = AtomicReference<Int>()
            val diagnostics = AtomicReference<JSONObject>()
            instrumentation.runOnMainSync { revision.set(player.generation()); diagnostics.set(activity!!.diagnosticSnapshot()) }
            generation = revision.get(); attempts = diagnostics.get().getInt("sourceAttempts")
        }
        @Throws(Exception::class)
        fun stats(): JSONObject { return app.playbackTelemetry.submit(Callable { engine!!.stats(torrent) }).get() }
        @Throws(Exception::class)
        fun assertRetained() {
            instrumentation.runOnMainSync {
                assertSame("Media3 was not recreated", player, AppFlowTest.session(activity))
                assertEquals("Media was not reloaded", generation.toLong(), player.generation().toLong())
                assertSame("Native engine is retained", engine, coordinator.engine)
                assertEquals("Torrent handle is retained", torrent, coordinator.torrent)
                assertFalse(player.snapshot().playWhenReady)
                assertEquals("Paused position is retained", position.toFloat(), player.snapshot().positionMs.toFloat(), 250f)
                assertEquals(speed, player.snapshot().speed, .001f)
                assertEquals("No new source resolution", attempts.toLong(), activity!!.diagnosticSnapshot().optInt("sourceAttempts", -1).toLong())
            }
            assertTrue("Payload was not deleted", payload.isDirectory)
            val stats = stats()
            assertTrue("Download counters did not restart", stats.getLong("totalDownload") >= downloaded)
            assertTrue("Verified cache reads did not restart", stats.getLong("verifiedReadBytes") >= verified)
            assertTrue("Downloaded storage stays bounded", stats.getJSONObject("cache").getLong("allocatedBytes") <= (16L shl 20))
        }
        @Throws(Exception::class)
        fun assertReturned() {
            assertRetained()
            instrumentation.runOnMainSync {
                assertNull(player.snapshot().error)
                assertFalse("Returning does not show the loading page", activity!!.findViewById<View>(R.id.loading).isShown)
            }
        }
        fun resumeAndVerifyFrames() {
            val before = AtomicReference(0)
            instrumentation.runOnMainSync { before.set(player.player().videoDecoderCounters!!.renderedOutputBufferCount) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY)
            AppHarness.waitUntil("retained film actually renders new frames", 10000) {
                val advancing = AtomicReference(false)
                instrumentation.runOnMainSync {
                    val counters: androidx.media3.exoplayer.DecoderCounters = player.player().videoDecoderCounters!!
                    counters.ensureUpdated()
                    advancing.set(player.snapshot().playing && player.snapshot().positionMs > position + 500
                            && counters.renderedOutputBufferCount > before.get())
                }
                advancing.get()
            }
        }
    }
    private fun snapshot(activity: WatchActivity?): PlaybackSnapshot? {
        val result = AtomicReference<PlaybackSnapshot>()
        instrumentation.runOnMainSync { result.set(activity!!.playbackSnapshot()) }; return result.get()
    }
    private fun stopped(activity: WatchActivity?): Boolean {
        val result = AtomicReference(false)
        instrumentation.runOnMainSync { result.set(ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) == Stage.STOPPED) }
        return result.get()
    }
    private fun pressHome() {
        val now = SystemClock.uptimeMillis()
        assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0), true))
        assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0), true))
    }
    @Throws(Exception::class)
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { `in` ->
            ByteArrayOutputStream().use { out ->
                val buffer = ByteArray(4096); var size: Int; while (`in`.read(buffer).also { size = it } != -1) out.write(buffer, 0, size)
                val output = String(out.toByteArray(), StandardCharsets.UTF_8)
                assertFalse(output, output.contains("Error:") || output.contains("Exception"))
            }
        }
    }
}
