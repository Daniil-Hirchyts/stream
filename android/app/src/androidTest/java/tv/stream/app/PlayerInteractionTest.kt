package tv.stream.app

import org.junit.Assert.*

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Actual pointer/remote interactions with Stream's player resource overrides, including its hidden transport row. */
@RunWith(AndroidJUnit4::class)
class PlayerInteractionTest : AppHarness() {
    @Test @Throws(Exception::class) fun pointerMenusTracksStyleAndTimelineWorkInTheAppPlayer() {
        readyLibrary("http-mkv"); launch(); tap("Generated Film", false); tap("Play", true); chooseFirstSource()
        AppHarness.waitUntil("Matroska renders", 30_000) { state() != null && state()!!.playing && state()!!.renderedFirstFrame }
        control(tv.stream.playback.R.id.player_view, false); control(tv.stream.playback.R.id.progress, true)
        AppHarness.waitUntil("pointer pauses", 5000) { !state()!!.playWhenReady }
        val image = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(image)
        try {
            java.io.FileOutputStream(java.io.File(context.filesDir, "validation-player-paused.png")).use { out ->
                assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally { image.recycle() }
        val audio = state()!!.audioTracks[1].label; val subtitle = state()!!.textTracks[0].label
        control(tv.stream.playback.R.id.control_options, false)
        tap("Audio", true); tap(audio, false)
        AppHarness.waitUntil("audio selected through menu", 5000) { state()!!.audio != null && state()!!.audio!!.label == audio }
        tap(subtitle, true)
        AppHarness.waitUntil("subtitle selected through menu", 5000) { state()!!.subtitles != null && state()!!.subtitles!!.label == subtitle }
        control(tv.stream.playback.R.id.player_view, false)
        control(R.id.control_settings, true)
        tap("Speed", true); tap("1.5×", false)
        AppHarness.waitUntil("speed selected through menu", 5000) { state()!!.speed == 1.5f }
        tap("Subtitle style", false); tap("Size", true); tap("Large", false)
        AppHarness.waitUntil("subtitle style persisted", 5000) { services.playerPreferences.getString("subtitle_size", "") == "large" }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        val menuActivity = watch()
        instrumentation.runOnMainSync { assertTrue("The toolbar stays visible beneath the popup menu",
                menuActivity!!.findViewById<View>(tv.stream.playback.R.id.controls).isShown) }
        // The centre of the video lies outside the right-hand menu; clicking it dismisses the sheet.
        control(tv.stream.playback.R.id.player_view, false)
        AppHarness.waitUntil("menu closes by pointer", 5000) { !popupOpen() }
        assertFalse("Dismissing the menu must leave playback paused", state()!!.playWhenReady)
        control(tv.stream.playback.R.id.player_view, true)
        val activity = watch(); val timeline = Rect()
        instrumentation.runOnMainSync {
            assertTrue(activity!!.findViewById<View>(tv.stream.playback.R.id.progress).getGlobalVisibleRect(timeline))
            val captions = activity.findViewById<View>(tv.stream.playback.R.id.subtitles)
            assertTrue("Captions clear the timeline touch target", captions.height - captions.paddingBottom < timeline.top)
        }
        // Keep this interaction test near the start; video EOS and longer subtitle/audio tails have their own regression.
        val target = state()!!.durationMs * 2 / 10
        assertTrue("Known duration for a real timeline drag", target > 0)
        val started = SystemClock.uptimeMillis()
        for (i in 0..10) {
            val x = timeline.left + timeline.width() * (0.1f + 0.1f * i / 10)
            val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), if (i == 0) MotionEvent.ACTION_DOWN
                    else if (i == 10) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE, x, timeline.centerY().toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(30)
        }
        AppHarness.waitUntil("timeline drag seeks while paused", 10_000) { !state()!!.playWhenReady && Math.abs(state()!!.positionMs - target) < 2000 }
        control(tv.stream.playback.R.id.progress, true)
        AppHarness.waitUntil("pointer resumes after drag", 10_000) { state()!!.playing }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
        AppHarness.waitUntil("remote opens menu after touch", 5000) { popupOpen() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
        AppHarness.waitUntil("remote closes menu", 5000) { !popupOpen() }
        // Closing the menu brings the toolbar back; a tap on the video now would hide it again. The menu's window keeps
        // focus (and ignores Back) until its closing animation ends.
        AppHarness.waitUntil("toolbar after the menu", 5000) { shown(tv.stream.playback.R.id.controls) && watch()!!.hasWindowFocus() }
        val playing = watch()
        instrumentation.runOnMainSync { assertTrue("Back exits from the playback toolbar",
                playing!!.findViewById<View>(tv.stream.playback.R.id.control_stop).isShown) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        if (watch() != null) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        AppHarness.waitUntil("Back closes playback", 10_000) { watch() == null }
    }

    @Test @Throws(Exception::class) fun arrowsReachOptionsAndSelectSubtitlesWithoutAMenuKey() {
        readyLibrary("http-mkv"); launch(); tap("Generated Film", false); tap("Play", true); chooseFirstSource()
        AppHarness.waitUntil("video renders", 30_000) { state() != null && state()!!.playing && state()!!.renderedFirstFrame }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        val activity = watch()
        instrumentation.runOnMainSync { assertTrue("Up focuses the first action, watched",
                activity!!.findViewById<View>(R.id.control_watched).hasFocus()) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.runOnMainSync { assertTrue("Right reaches Audio & subtitles",
                activity!!.findViewById<View>(tv.stream.playback.R.id.control_options).hasFocus()) }
        AppHarness.waitUntil("Active controls include the clock", 2000) { shown(R.id.player_clock) }
        SystemClock.sleep(2500)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        SystemClock.sleep(2000)
        assertTrue("Real remote activity restarts the timeout", shown(R.id.controls))
        val fadingTogether = java.util.concurrent.atomic.AtomicBoolean()
        val fadeMismatch = java.util.concurrent.atomic.AtomicBoolean()
        val observer = android.view.ViewTreeObserver.OnDrawListener {
            val controls = activity!!.findViewById<View>(R.id.controls); val clock = activity.findViewById<View>(R.id.player_clock)
            if (controls.alpha > .1f && controls.alpha < .9f) {
                fadingTogether.set(true)
                if (!clock.isShown || Math.abs(clock.alpha - controls.alpha) > .25f) fadeMismatch.set(true)
            }
        }
        instrumentation.runOnMainSync { activity!!.findViewById<View>(R.id.player_root).viewTreeObserver.addOnDrawListener(observer) }
        AppHarness.waitUntil("Focused controls hide after inactivity", 4000) { !shown(R.id.controls) }
        AppHarness.waitUntil("Clock hides with active controls", 2000) { !shown(R.id.player_clock) }
        instrumentation.runOnMainSync { activity!!.findViewById<View>(R.id.player_root).viewTreeObserver.removeOnDrawListener(observer) }
        assertTrue("Observed the controls fading", fadingTogether.get())
        assertFalse("Clock and controls fade together", fadeMismatch.get())
        instrumentation.runOnMainSync { assertTrue("Focus returns to the video", activity!!.findViewById<View>(R.id.player_root).hasFocus()) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        AppHarness.waitUntil("OK opens options", 5000) { popupOpen() }
        // Subtitle choices are directly on the Audio & subtitles page: Off, then the first track.
        AppHarness.waitUntil("subtitle choices", 5000) { visible("Off") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        AppHarness.waitUntil("remote subtitle selected", 5000) { state()!!.subtitles != null }
        AppHarness.waitUntil("selection dismisses menu", 5000) { !popupOpen() }
        AppHarness.waitUntil("closing morph returns the player window", 5000) {
            val focus = java.util.concurrent.atomic.AtomicBoolean()
            instrumentation.runOnMainSync { focus.set(activity!!.hasWindowFocus()) }; focus.get()
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.runOnMainSync { assertTrue("Down focuses the timeline",
                activity!!.findViewById<View>(tv.stream.playback.R.id.progress).hasFocus()) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        AppHarness.waitUntil("remote pauses", 5000) { !state()!!.playWhenReady }
        SystemClock.sleep(4500)
        assertTrue("Paused controls stay visible", shown(R.id.controls))
        assertTrue("Paused clock stays visible", shown(R.id.player_clock))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        AppHarness.waitUntil("remote timeline seeks", 5000) { state()!!.positionMs >= 9000 }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        AppHarness.waitUntil("remote resumes", 5000) { state()!!.playing }
        AppHarness.waitUntil("Focused timeline hides after resume", 6000) { !shown(R.id.controls) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        AppHarness.waitUntil("Remote reopens transport", 2000) { shown(R.id.controls) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        AppHarness.waitUntil("Back hides focused transport", 5000) { !shown(tv.stream.playback.R.id.controls) }
        SystemClock.sleep(1200)
        assertFalse("The focus keeper must not reopen dismissed controls", shown(tv.stream.playback.R.id.controls))
    }

    @Test @Throws(Exception::class) fun mouseMovementRestoresControlsAndIdlePointerLetsThemHide() {
        readyLibrary("http-mkv"); launch(); tap("Generated Film", false); tap("Play", true); chooseFirstSource()
        AppHarness.waitUntil("video renders", 30_000) { state() != null && state()!!.playing && state()!!.renderedFirstFrame }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
        AppHarness.waitUntil("paused clock and controls", 5000) { shown(R.id.controls) && shown(R.id.player_clock) }
        val activity = watch(); val timeline = Rect()
        instrumentation.runOnMainSync { assertTrue(activity!!.findViewById<View>(R.id.progress).getGlobalVisibleRect(timeline)) }
        control(R.id.progress, true)
        AppHarness.waitUntil("timeline click resumes", 5000) { state()!!.playing }
        AppHarness.waitUntil("Pointer transport hides after resume", 6000) { !shown(R.id.controls) }
        assertFalse("Clock clears the picture too", shown(R.id.player_clock))
        // Use the timeline's last visible bounds to move the real pointer over it.
        hoverMouse((timeline.centerX() + 30).toFloat(), timeline.centerY().toFloat())
        AppHarness.waitUntil("Mouse movement restores controls", 3000) { shown(R.id.controls) }
        AppHarness.waitUntil("Mouse movement restores clock", 2000) { shown(R.id.player_clock) }
        AppHarness.waitUntil("Stationary mouse does not keep the timeline open", 6000) { !shown(R.id.controls) }
        assertTrue("Video keeps playing after the overlay hides", state()!!.playing)
    }

    private fun hoverMouse(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val pointer = MotionEvent.PointerProperties()
        pointer.id = 0; pointer.toolType = MotionEvent.TOOL_TYPE_MOUSE
        val coords = MotionEvent.PointerCoords(); coords.x = x; coords.y = y
        val event = MotionEvent.obtain(time, time, MotionEvent.ACTION_HOVER_MOVE, 1,
                arrayOf(pointer), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
    }

    private fun popupOpen(): Boolean { return visible("Settings") || visible("Audio & subtitles") || visible("Subtitle style") }

    private fun shown(id: Int): Boolean {
        val shown = java.util.concurrent.atomic.AtomicBoolean()
        val activity = watch()
        if (activity != null) instrumentation.runOnMainSync { shown.set(activity.findViewById<View>(id).visibility == View.VISIBLE) }
        return shown.get()
    }
}
