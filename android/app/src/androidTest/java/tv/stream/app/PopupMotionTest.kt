package tv.stream.app

import org.junit.Assert.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayList
import java.util.function.Consumer
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import tv.stream.app.ui.Tv
import tv.stream.app.ui.Item

/** Actual painted frames and rapid remote navigation, not just final animation properties. */
class PopupMotionTest : AppHarness() {
    @Test @Throws(Exception::class) fun focusedOpenerRemainsWhiteThroughoutOpeningAndClosing() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        val menu = AtomicReference<Tv.Alert>()
        val button = AtomicReference<View>()
        browse!!.onActivity { activity ->
            val action = Item.Action("test-popup", Item.Action.Style.ICON, "Settings", Runnable {
                val alert = Tv.alert(activity, "Settings", null as View?); menu.set(alert)
                alert.button("speed", "Speed", Runnable { }); alert.show()
            }).icon(R.drawable.ic_tv_settings)
            val control = Tv.button(activity, action, Consumer { ignored -> }); button.set(control)
            val layout = android.widget.FrameLayout.LayoutParams(Tv.dp(context, 44f), Tv.dp(context, 44f), android.view.Gravity.TOP or android.view.Gravity.RIGHT)
            layout.topMargin = Tv.dp(context, 24f); layout.rightMargin = Tv.dp(context, 100f)
            activity.addContentView(control, layout); control.requestFocus()
        }
        SystemClock.sleep(300)
        val source = Rect()
        instrumentation.runOnMainSync { assertTrue(button.get().getGlobalVisibleRect(source)) }
        val screenFrames: MutableList<Bitmap> = java.util.concurrent.CopyOnWriteArrayList()
        val screenColours: MutableList<Int> = java.util.concurrent.CopyOnWriteArrayList()
        val screenWidths: MutableList<Int> = java.util.concurrent.CopyOnWriteArrayList()
        val recording = java.util.concurrent.atomic.AtomicBoolean(true)
        val capture = Thread({
            while (recording.get() && screenFrames.size < 80) {
                val screen = instrumentation.uiAutomation.takeScreenshot()
                if (screen == null) continue
                val x = source.left + source.width() / 4; val y = source.centerY()
                screenColours.add(screen.getPixel(x, y))
                var left = source.centerX(); var right = left
                while (left > source.left - 8 && android.graphics.Color.red(screen.getPixel(left - 1, source.top + source.height() / 3)) > 230) left--
                while (right < source.right + 8 && android.graphics.Color.red(screen.getPixel(right + 1, source.top + source.height() / 3)) > 230) right++
                screenWidths.add(right - left)
                val margin = Tv.dp(context, 12f)
                val l = Math.max(0, source.left - margin); val t = Math.max(0, source.top - margin)
                val r = Math.min(screen.width, source.right + margin); val b = Math.min(screen.height, source.bottom + margin)
                screenFrames.add(Bitmap.createBitmap(screen, l, t, r - l, b - t)); screen.recycle()
            }
        }, "popup-screen-capture")
        capture.start()
        AppHarness.waitUntil("initial screen frame", 5000) { !screenFrames.isEmpty() }
        val frames: MutableList<Bitmap> = java.util.concurrent.CopyOnWriteArrayList()
        val colours: MutableList<Int> = java.util.concurrent.CopyOnWriteArrayList()
        val listener = AtomicReference<ViewTreeObserver.OnPreDrawListener>()
        instrumentation.runOnMainSync {
            button.get().performClick()
            val root = menu.get().dialog.window!!.decorView.findViewWithTag<View>("popup-root")
            val position = IntArray(2); val window = IntArray(2); button.get().getLocationOnScreen(position); root.getLocationOnScreen(window)
            val x = position[0] - window[0] + button.get().width / 4
            val y = position[1] - window[1] + button.get().height / 2
            listener.set(ViewTreeObserver.OnPreDrawListener {
                val origin = root.findViewWithTag<View>("popup-origin")
                if (origin == null || !origin.isLaidOut) return@OnPreDrawListener true
                colours.add(pixel(root, x, y))
                if (frames.size < 50) {
                    val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap); canvas.scale(320f / root.width, 180f / root.height)
                    root.draw(canvas); frames.add(bitmap)
                }
                true
            })
            root.viewTreeObserver.addOnPreDrawListener(listener.get())
        }
        AppHarness.waitUntil("popup opened", 5000) { visible("Speed") }; SystemClock.sleep(450)
        val openingFrames = colours.size
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        AppHarness.waitUntil("popup closed", 5000) { !menu.get().dialog.isShowing }
        SystemClock.sleep(350); recording.set(false); capture.join(5000)
        try {
            val cell = 120; val rows = (screenFrames.size + 7) / 8
            val strip = Bitmap.createBitmap(cell * 8, cell * rows, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(strip)
            for (i in 0 until screenFrames.size) canvas.drawBitmap(screenFrames[i], null,
                    Rect(i % 8 * cell, i / 8 * cell, (i % 8 + 1) * cell, (i / 8 + 1) * cell), null)
            FileOutputStream(File(context.filesDir, "validation-popup-screen-frames.png")).use { out -> strip.compress(Bitmap.CompressFormat.PNG, 100, out) }
            strip.recycle()
        } finally { for (frame in screenFrames) frame.recycle() }
        try {
            val strip = Bitmap.createBitmap(320 * 4, 180 * ((frames.size + 3) / 4), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(strip)
            for (i in 0 until frames.size) canvas.drawBitmap(frames[i], null,
                    Rect(i % 4 * 320, i / 4 * 180, (i % 4 + 1) * 320, (i / 4 + 1) * 180), null)
            FileOutputStream(File(context.filesDir, "validation-popup-opener-frames.png")).use { out -> strip.compress(Bitmap.CompressFormat.PNG, 100, out) }
            strip.recycle()
        } finally { for (frame in frames) frame.recycle() }
        assertTrue("Opening produced painted frames", openingFrames >= 4)
        assertTrue("Closing produced painted frames", colours.size - openingFrames >= 4)
        for (colour in colours) assertTrue("The opener stays white in every popup frame: $colours",
                android.graphics.Color.alpha(colour) >= 240 && android.graphics.Color.red(colour) >= 240)
        for (colour in screenColours) assertTrue("The actual screen keeps the opener white: $screenColours",
                android.graphics.Color.red(colour) >= 230 && android.graphics.Color.green(colour) >= 230 && android.graphics.Color.blue(colour) >= 230)
        val widest = java.util.Collections.max(screenWidths); val narrowest = java.util.Collections.min(screenWidths)
        assertTrue("The cursor does not change size at window handoff: $screenWidths", widest - narrowest <= Tv.dp(context, 3f))
    }
    @Test @Throws(Exception::class) fun refreshingUnchangedMenuNeverFlashesTheFocusedAction() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        val menu = AtomicReference<Tv.Alert>()
        browse!!.onActivity { activity ->
            val alert = Tv.alert(activity, "Playback", null as View?); menu.set(alert)
            alert.button("speed", "Speed", Runnable { }); alert.button("audio", "Audio", Runnable { }); alert.show()
        }
        AppHarness.waitUntil("menu", 5000) { visible("Speed") }; SystemClock.sleep(400)
        val colours: MutableList<Int> = java.util.concurrent.CopyOnWriteArrayList()
        val listener = AtomicReference<ViewTreeObserver.OnPreDrawListener>()
        instrumentation.runOnMainSync {
            val panel = panel(menu.get()); val selected = menu.get().dialog.currentFocus; assertNotNull(selected)
            val sample = Rect(0, 0, selected!!.width, selected.height)
            panel.offsetDescendantRectToMyCoords(selected, sample)
            val x = sample.left + Tv.dp(context, 4f); val y = sample.centerY()
            colours.add(pixel(panel, x, y))
            listener.set(ViewTreeObserver.OnPreDrawListener { colours.add(pixel(panel, x, y)); true })
            panel.viewTreeObserver.addOnPreDrawListener(listener.get())
            val until = SystemClock.uptimeMillis() + 360
            val frames: Runnable = object : Runnable {
                override fun run() { panel.invalidate(); if (SystemClock.uptimeMillis() < until) panel.postOnAnimation(this) }
            }
            menu.get().refresh(); panel.postOnAnimation(frames)
        }
        SystemClock.sleep(450)
        instrumentation.runOnMainSync { panel(menu.get()).viewTreeObserver.removeOnPreDrawListener(listener.get()) }
        assertTrue("Several rendered frames observed", colours.size >= 6)
        java.io.FileWriter(File(context.filesDir, "validation-popup-refresh-frames.json")).use { writer -> writer.write(colours.toString()) }
        val baseline = android.graphics.Color.red(colours[0])
        val minimum = colours.stream().mapToInt { android.graphics.Color.red(it) }.min().asInt
        assertTrue("Focused platter stays painted, baseline=$baseline, minimum=$minimum, frames=$colours",
                baseline >= 240 && minimum >= baseline - 10)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        AppHarness.waitUntil("closed", 5000) { !menu.get().dialog.isShowing }
    }

    @Test @Throws(Exception::class) fun rapidSubmenuBackAndReopenSettleOnTheRightPageWithoutGhostLayers() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        val menu = AtomicReference<Tv.Alert>(); val frames: MutableList<Bitmap> = ArrayList()
        browse!!.onActivity { activity ->
            val alert = Tv.alert(activity, "Options", null as View?); menu.set(alert)
            alert.submenu("audio", "Audio", Consumer { child ->
                child.button("description", "English audio descriptions and accessibility support", Runnable { })
                child.submenu("speed", "Speed", Consumer { values -> values.choice("normal", "1×", "", true, Runnable { values.back() }); values.choice("faster", "1.5×", "", false, Runnable { values.back() }) })
            }); alert.button("watched", "Mark watched", Runnable { }); alert.show()
        }
        AppHarness.waitUntil("root", 5000) { visible("Audio") }; SystemClock.sleep(350)
        val listener = AtomicReference<ViewTreeObserver.OnPreDrawListener>()
        instrumentation.runOnMainSync {
            val panel = panel(menu.get()); val root = menu.get().dialog.window!!.decorView.findViewWithTag<View>("popup-root")
            val start = SystemClock.uptimeMillis()
            listener.set(ViewTreeObserver.OnPreDrawListener {
                if (frames.size < 10 && SystemClock.uptimeMillis() - start >= frames.size * 25L) {
                    val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap); canvas.drawColor(0xff0a0a0d.toInt()); canvas.scale(640f / root.width, 360f / root.height); root.draw(canvas); frames.add(bitmap)
                }
                true
            }); panel.viewTreeObserver.addOnPreDrawListener(listener.get())
            menu.get().buttons.findViewWithTag<View>("audio").performClick()
        }
        SystemClock.sleep(35); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        SystemClock.sleep(35)
        instrumentation.runOnMainSync { menu.get().buttons.findViewWithTag<View>("audio").performClick() }
        SystemClock.sleep(35); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        SystemClock.sleep(350)
        instrumentation.runOnMainSync {
            val panel = panel(menu.get()); panel.viewTreeObserver.removeOnPreDrawListener(listener.get())
            assertEquals("No outgoing content or snapshot left behind", 1, panel.childCount.toLong())
            assertEquals("Focus returns to the submenu opener", "audio", menu.get().dialog.currentFocus!!.tag)
            assertEquals("Final page is opaque", 1f, panel.getChildAt(0).alpha, .001f)
        }
        assertTrue(visible("Mark watched")); assertFalse(visible("Speed"))
        try {
            val strip = Bitmap.createBitmap(640 * 2, 360 * ((frames.size + 1) / 2), Bitmap.Config.ARGB_8888); val canvas = Canvas(strip)
            for (i in 0 until frames.size) canvas.drawBitmap(frames[i], (i % 2 * 640).toFloat(), (i / 2 * 360).toFloat(), null)
            FileOutputStream(File(context.filesDir, "validation-popup-motion-frames.png")).use { out -> strip.compress(Bitmap.CompressFormat.PNG, 100, out) }; strip.recycle()
        } finally { for (frame in frames) frame.recycle() }
        tap("Audio", true); AppHarness.waitUntil("child after interrupted transitions", 5000) { visible("Speed") }
        tap("Speed", false); AppHarness.waitUntil("value list", 5000) { visible("1×") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); AppHarness.waitUntil("wide parent", 5000) { visible("Speed") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); AppHarness.waitUntil("root restored", 5000) { visible("Mark watched") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); AppHarness.waitUntil("closed", 5000) { !menu.get().dialog.isShowing }
    }

    private fun panel(alert: Tv.Alert): ViewGroup { return alert.dialog.window!!.decorView.findViewWithTag("popup-panel") }
    private fun pixel(view: View, x: Int, y: Int): Int {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap); canvas.translate((-x).toFloat(), (-y).toFloat()); view.draw(canvas)
        val colour = bitmap.getPixel(0, 0); bitmap.recycle(); return colour
    }
}
