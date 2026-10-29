package tv.stream.app

import org.junit.Assert.*
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import tv.stream.app.data.TvStore

/** Generated torrent playback, real timeline gestures and durable completion choices on an isolated store. */
@RunWith(AndroidJUnit4::class)
class WatchCompletionInteractionTest : AppHarness() {
    @Test @Throws(Exception::class) fun timelineTapDragAndWatchedButtonPreserveTheViewersChoice() {
        playFilm()
        control(tv.stream.playback.R.id.player_view, false); control(R.id.progress, true)
        AppHarness.waitUntil("timeline mouse tap pauses", 5000) { !state()!!.playWhenReady }
        val paused = state()!!.positionMs
        assertTrue("A tap toggles transport without jumping halfway through the episode", paused < 15_000)
        control(R.id.progress, false)
        AppHarness.waitUntil("timeline touch tap resumes", 5000) { state()!!.playing }
        control(R.id.progress, true)
        AppHarness.waitUntil("pause again", 5000) { !state()!!.playWhenReady }
        dragTo(0.5f)
        AppHarness.waitUntil("midpoint is ready", 5000) { !state()!!.buffering && state()!!.positionMs > state()!!.durationMs * 0.45 }
        screenshot("validation-player-timeline-middle.png")
        dragTo(0.94f)
        AppHarness.waitUntil("drag seeks without resuming", 5000) { !state()!!.playWhenReady && state()!!.positionMs > state()!!.durationMs * 0.9 }
        assertTrue("Seeking near the end alone is unwatched", services.store.history(false).stream().noneMatch { p -> p.watched })
        val activity = watch()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        dragTo(0.94f)
        SystemClock.sleep(100)
        instrumentation.runOnMainSync {
            val info = activity!!.findViewById<View>(R.id.timeline_info); val end = activity.findViewById<View>(R.id.remaining)
            assertTrue("Time and feedback stay on screen", info.x >= 0)
            assertTrue("Time, pause and seek feedback clear the end label", info.x + info.width < end.left)
            assertEquals("Pause and seek share one status slot",
                    !activity.findViewById<View>(R.id.seek_bubble).isShown, activity.findViewById<View>(R.id.timeline_paused).isShown)
            assertEquals("Centre transport occupies no picture", 0, activity.findViewById<View>(R.id.center_icon).width.toLong())
        }
        screenshot("validation-player-timeline.png")
        control(R.id.control_watched, true)
        AppHarness.waitUntil("manual completion persisted", 5000) { services.store.history(false).stream().anyMatch { p -> p.watched } }
        assertTrue("Watched episode leaves Continue Watching", services.store.history(true).isEmpty())
        control(R.id.control_stop, false)
        AppHarness.waitUntil("manual completion exits without a prompt", 5000) { watch() == null }
        assertFalse(visible("Finished watching?"))
    }

    @Test @Throws(Exception::class) fun nearEndExitCanKeepThePlaceCancelOrMarkWatched() {
        playFilm()
        control(tv.stream.playback.R.id.player_view, false); control(R.id.progress, true)
        AppHarness.waitUntil("paused", 5000) { !state()!!.playWhenReady }
        dragTo(0.92f)
        AppHarness.waitUntil("near end", 5000) { state()!!.positionMs > state()!!.durationMs * 0.9 }
        control(R.id.control_stop, true)
        AppHarness.waitUntil("exit offers completion choice", 5000) { visible("Finished watching?") }
        android.os.SystemClock.sleep(350)
        screenshot("validation-player-exit.png")
        assertFalse("No cancellation row in the popup", visible("Continue watching"))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        AppHarness.waitUntil("cancel returns input to the paused player", 5000) {
            val activity = watch()
            if (activity == null || visible("Finished watching?")) false
            else {
                val focused = java.util.concurrent.atomic.AtomicBoolean()
                instrumentation.runOnMainSync { focused.set(activity.hasWindowFocus()) }; focused.get()
            }
        }
        assertFalse(state()!!.playWhenReady)
        control(R.id.control_stop, true)
        tap("Keep for later", true)
        AppHarness.waitUntil("keep exits", 5000) { watch() == null }
        app.persistence.submit(Runnable { }).get()
        val kept: TvStore.Progress = services.store.history(true)[0]
        assertFalse(kept.watched); assertTrue(kept.positionMs > kept.durationMs * 0.9)
        // Reopen the real saved media, pausing as soon as it starts so the credits do not run out.
        startAutomaticFixturePlayback()
        AppHarness.waitUntil("resumed media starts", 30_000) { state() != null && state()!!.started }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
        AppHarness.waitUntil("resumed media pauses", 5000) { !state()!!.playWhenReady }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        AppHarness.waitUntil("system Back also offers completion", 5000) { visible("Finished watching?") }
        tap("Mark watched", false)
        AppHarness.waitUntil("mark exits", 5000) { watch() == null }
        app.persistence.submit(Runnable { }).get()
        assertTrue(services.store.history(false)[0].watched)
        assertTrue(services.store.history(true).isEmpty())
    }

    @Test @Throws(Exception::class) fun naturalCompletionCanBeMarkedUnwatchedAndStaysUnwatchedOnExit() {
        playFilm()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
        AppHarness.waitUntil("paused", 5000) { !state()!!.playWhenReady }
        dragTo(0.99f)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY)
        AppHarness.waitUntil("natural completion", 15000) { state()!!.ended }
        AppHarness.waitUntil("automatic watched state saved", 5000) { services.store.history(false)[0].watched }
        control(R.id.control_watched, true)
        AppHarness.waitUntil("finished film unmarked", 5000) { !services.store.history(false)[0].watched }
        SystemClock.sleep(1500)
        assertFalse("Repeated ended snapshots must not undo the manual choice", services.store.history(false)[0].watched)
        control(R.id.control_stop, false)
        AppHarness.waitUntil("exit", 5000) { watch() == null }
        app.persistence.submit(Runnable { }).get()
        assertFalse("Final progress save keeps it unwatched", services.store.history(false)[0].watched)
    }

    private fun dragTo(fraction: Float) {
        val activity = watch(); val bounds = Rect()
        instrumentation.runOnMainSync { assertTrue(activity!!.findViewById<View>(R.id.progress).getGlobalVisibleRect(bounds)) }
        val time = SystemClock.uptimeMillis()
        for (i in 0..10) {
            val x = bounds.left + bounds.width() * (0.1f + (fraction - 0.1f) * i / 10f)
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), if (i == 0) MotionEvent.ACTION_DOWN
                    else if (i == 10) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE, x, bounds.centerY().toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(25)
        }
    }
    @Throws(Exception::class)
    private fun screenshot(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot(); assertNotNull(image)
        FileOutputStream(File(context.filesDir, name)).use { out -> image.compress(Bitmap.CompressFormat.PNG, 100, out) }
        image.recycle()
    }
}
