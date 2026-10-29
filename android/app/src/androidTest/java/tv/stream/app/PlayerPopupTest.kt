package tv.stream.app

import org.junit.Assert.*
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import java.lang.reflect.Field
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import tv.stream.playback.PlaybackSession
import tv.stream.torrent.TorrentEngine

/** Real app popup actions and cancellation with generated media and loopback peers. */
class PlayerPopupTest : AppHarness() {
    @Test @Throws(Exception::class) fun settingsOpenerKeepsItsFocusAndScaleAcrossPopup() {
        readyLibrary("http-mkv"); launch(); tap("Generated Film", false); tap("Play", true); chooseFirstSource()
        AppHarness.waitUntil("playing", 30000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        val activity = watch(); val button = activity!!.findViewById<View>(R.id.control_settings)
        instrumentation.runOnMainSync { button.requestFocus() }; SystemClock.sleep(300)
        val source = android.graphics.Rect()
        instrumentation.runOnMainSync { assertTrue(button.isFocused); assertTrue(button.getGlobalVisibleRect(source)) }
        val scales: MutableList<Float> = java.util.concurrent.CopyOnWriteArrayList()
        val frames: MutableList<android.graphics.Bitmap> = java.util.concurrent.CopyOnWriteArrayList()
        val colours: MutableList<Int> = java.util.concurrent.CopyOnWriteArrayList()
        val recording = java.util.concurrent.atomic.AtomicBoolean(true)
        val root: android.view.View = activity.window.decorView
        val listener = android.view.ViewTreeObserver.OnPreDrawListener { scales.add(button.scaleX); true }
        instrumentation.runOnMainSync { root.viewTreeObserver.addOnPreDrawListener(listener) }
        val capture = Thread {
            while (recording.get() && frames.size < 100) {
                val screen = instrumentation.uiAutomation.takeScreenshot() ?: continue
                colours.add(screen.getPixel(source.left + source.width() / 4, source.centerY()))
                val margin = tv.stream.app.ui.Tv.dp(context, 12f)
                val l = Math.max(0, source.left - margin); val t = Math.max(0, source.top - margin)
                val r = Math.min(screen.width, source.right + margin); val b = Math.min(screen.height, source.bottom + margin)
                frames.add(android.graphics.Bitmap.createBitmap(screen, l, t, r - l, b - t)); screen.recycle()
            }
        }
        capture.start(); AppHarness.waitUntil("initial player frame", 5000) { !frames.isEmpty() }
        instrumentation.runOnMainSync { button.performClick() }
        AppHarness.waitUntil("settings popup", 5000) { visible("Subtitle style") }; SystemClock.sleep(400)
        val held = AtomicReference<Boolean>(); instrumentation.runOnMainSync { held.set(button.isFocused) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); returned(activity); SystemClock.sleep(350)
        recording.set(false); capture.join(5000)
        instrumentation.runOnMainSync { root.viewTreeObserver.removeOnPreDrawListener(listener) }
        try {
            val cell = 120; val rows = (frames.size + 7) / 8
            val strip = android.graphics.Bitmap.createBitmap(cell * 8, cell * rows, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(strip)
            for (i in 0 until frames.size) canvas.drawBitmap(frames[i], null,
                    android.graphics.Rect(i % 8 * cell, i / 8 * cell, (i % 8 + 1) * cell, (i / 8 + 1) * cell), null)
            java.io.FileOutputStream(java.io.File(context.filesDir, "validation-player-popup-screen-frames.png")).use { out -> strip.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
            strip.recycle()
        } finally { for (frame in frames) frame.recycle() }
        assertTrue("The player opener retains focus while its popup is open", held.get())
        assertTrue("The player opener never shrinks and scales back up: $scales",
                !scales.isEmpty() && java.util.Collections.min(scales) >= tv.stream.app.ui.Tv.LIFT_ICON - .01f)
        for (colour in colours) assertTrue("The player cursor stays white on screen: $colours",
                android.graphics.Color.red(colour) >= 230 && android.graphics.Color.green(colour) >= 230 && android.graphics.Color.blue(colour) >= 230)
    }
    @Test @Throws(Exception::class) fun subtitleFocusOnlyPreviewsAndSelectionCommitsToBothSettingsStores() {
        readyLibrary("http-mkv"); launch(); tap("Generated Film", false); tap("Play", true); chooseFirstSource()
        AppHarness.waitUntil("playing", 30000) { state() != null && state()!!.renderedFirstFrame && state()!!.playing }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
        control(R.id.control_settings, true); AppHarness.waitUntil("new settings", 5000) { visible("Subtitle style") }
        val activity = watch()
        instrumentation.runOnMainSync { assertFalse("The old native menu must never be drawn", activity!!.findViewById<View>(R.id.menu).isShown) }
        val before = services.playerPreferences.getString("subtitle_font", "standard")
        tap("Subtitle style", false); tap("Font", true)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        SystemClock.sleep(300)
        assertEquals("Focus previews without changing the saved value", before, services.playerPreferences.getString("subtitle_font", "standard"))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        AppHarness.waitUntil("returns to style", 5000) { visible("Size") }
        assertEquals("Back cancels the preview", before, services.playerPreferences.getString("subtitle_font", "standard"))
        tap("Font", false); tap("Bold", true)
        AppHarness.waitUntil("committed font", 5000) { services.playerPreferences.getString("subtitle_font", "") == "bold" }
        app.persistence.submit(Runnable { }).get()
        assertEquals("The Settings page receives the same saved value", "bold", services.store.preference("subtitle_font", ""))
        tap("Font", false); tap("Atkinson Hyperlegible Next", true)
        AppHarness.waitUntil("bundled accessibility font committed", 5000) { services.playerPreferences.getString("subtitle_font", "") == "atkinson" }
        app.persistence.submit(Runnable { }).get(); assertEquals("atkinson", services.store.preference("subtitle_font", ""))
        tap("Color", true); tap("Yellow", false)
        AppHarness.waitUntil("another style saved", 5000) { services.playerPreferences.getString("subtitle_color", "") == "yellow" }
        assertEquals("Changing another style preserves the app-only font", "atkinson", services.playerPreferences.getString("subtitle_font", ""))
        instrumentation.runOnMainSync {
            try {
                val subtitles = activity!!.findViewById<android.view.ViewGroup>(R.id.subtitles)
                val font: Field = androidx.media3.ui.StreamSubtitleOutput::class.java.getDeclaredField("fontIndex"); font.isAccessible = true
                assertEquals("The live renderer keeps Atkinson after shared style updates", tv.stream.app.ui.SubtitleFonts.ATKINSON.toLong(), font.getInt(subtitles.getChildAt(0)).toLong())
            } catch (e: ReflectiveOperationException) { throw AssertionError(e) }
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
        returned(activity)
        assertFalse("Popup dismissal preserves pause", state()!!.playWhenReady)
    }

    @Test @Throws(Exception::class) fun sourcePopupsPreserveTheCacheOnCancelAndViewerStateOnSelection() {
        playFilm(); val activity = watch(); val player = AppFlowTest.session(activity)
        instrumentation.runOnMainSync { player.pause() }
        AppHarness.waitUntil("paused", 5000) { !state()!!.playWhenReady && !state()!!.buffering }
        val field = WatchActivity::class.java.getDeclaredField("coordinator"); field.isAccessible = true
        val coordinator = field.get(activity) as PlaybackCoordinator
        val engine = coordinator.engine; val torrent = coordinator.torrent
        assertNotNull("A real torrent, not an HTTP substitute", engine); assertTrue(torrent != 0L)
        val downloaded: Long = app.playbackTelemetry.submit(Callable { engine!!.stats(torrent).getLong("totalDownload") }).get()
        assertTrue(downloaded > 0)
        val generation = AtomicReference<Int>(); instrumentation.runOnMainSync { generation.set(player.generation()) }
        val position = state()!!.positionMs
        control(R.id.control_settings, false); tap("Choose source", true)
        AppHarness.waitUntil("source rows", 5000) { visible("Choose source") && !visible("Speed") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        AppHarness.waitUntil("back to settings", 5000) { visible("Speed") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); returned(activity)
        assertRetained(activity, player, coordinator, engine, torrent, generation.get(), position, downloaded)
        // Also exercise the fallback picker used by the error/source route, not just the settings submenu.
        val choose = WatchActivity::class.java.getDeclaredMethod("chooseSources"); choose.isAccessible = true
        instrumentation.runOnMainSync { try { choose.invoke(activity) } catch (e: Exception) { throw AssertionError(e) } }
        AppHarness.waitUntil("fallback source picker", 5000) { visible("Choose source") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); returned(activity)
        assertRetained(activity, player, coordinator, engine, torrent, generation.get(), position, downloaded)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY)
        AppHarness.waitUntil("cached film resumes", 10000) { state()!!.playing && state()!!.positionMs > position + 500 }
        instrumentation.runOnMainSync { player.pause(); player.seekTo(6000); player.setSpeed(1.25f) }
        AppHarness.waitUntil("paused seek ready", 10000) { !state()!!.buffering && state()!!.positionMs >= 6000 }
        control(R.id.control_settings, true); tap("Choose source", false); tap("Local magnet", true)
        AppHarness.waitUntil("selected source ready at the same paused position", 30000) { state() != null && state()!!.loaded
                && state()!!.renderedFirstFrame && !state()!!.buffering && !state()!!.playWhenReady && Math.abs(state()!!.positionMs - 6000) < 500 }
        assertEquals("Source changes also preserve speed", 1.25f, state()!!.speed, .001f)
        instrumentation.runOnMainSync { assertTrue("An actual source change was performed", player.generation() > generation.get()) }
    }
    @Throws(Exception::class)
    private fun assertRetained(activity: WatchActivity?, player: PlaybackSession, coordinator: PlaybackCoordinator, engine: TorrentEngine?,
                               torrent: Long, generation: Int, position: Long, downloaded: Long) {
        assertSame(activity, watch())
        instrumentation.runOnMainSync {
            assertSame(player, AppFlowTest.session(activity)); assertEquals(generation.toLong(), player.generation().toLong())
            assertSame(engine, coordinator.engine); assertEquals(torrent, coordinator.torrent)
            assertFalse(player.snapshot().playWhenReady); assertEquals(position.toFloat(), player.snapshot().positionMs.toFloat(), 250f)
            assertFalse("No reload after cancelling", activity!!.findViewById<View>(R.id.loading).isShown)
        }
        assertTrue("Download counters retained", app.playbackTelemetry.submit(Callable { engine!!.stats(torrent).getLong("totalDownload") }).get() >= downloaded)
    }
    private fun returned(activity: WatchActivity?) {
        AppHarness.waitUntil("player window returns", 5000) {
            val focus = AtomicReference(false); instrumentation.runOnMainSync { focus.set(activity!!.hasWindowFocus()) }; focus.get()
        }
    }
}
