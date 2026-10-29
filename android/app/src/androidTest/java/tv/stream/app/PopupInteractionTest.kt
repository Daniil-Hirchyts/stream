package tv.stream.app

import org.junit.Assert.*
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayList
import java.util.function.Consumer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import tv.stream.app.ui.Tv

/** Actual remote/pointer inputs and rendered geometry; no public providers or owner data. */
class PopupInteractionTest : AppHarness() {
    @Test @Throws(Exception::class) fun nestedLevelsMorphWithoutABackdropAndRestoreParentFocus() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        val popup = AtomicReference<Tv.Alert>(); val accepted = AtomicBoolean()
        browse!!.onActivity { activity ->
            val menu = Tv.alert(activity, "Episode options", null as View?); popup.set(menu)
            menu.submenu("playback", "Playback", Consumer { child ->
                child.submenu("quality", "Quality", Consumer { grandchild ->
                    grandchild.button("balanced", "Balanced HD", Runnable { accepted.set(true); grandchild.dialog.dismiss() })
                })
                child.button("source", "Choose source", Runnable { })
            })
            menu.button("watched", "Mark watched", Runnable { }); menu.show()
        }
        AppHarness.waitUntil("root options", 5000) { visible("Mark watched") && visible("Playback") }
        SystemClock.sleep(350); assertNoExitRows()
        val dialog = popup.get().dialog
        instrumentation.runOnMainSync {
            assertEquals(0, dialog.window!!.attributes.flags and (WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_BLUR_BEHIND))
            val root = dialog.window!!.decorView.findViewWithTag<ViewGroup>("popup-root")
            assertEquals("No page screenshot or tint layer", 1, root.childCount.toLong())
            val rows: ViewGroup = popup.get().buttons
            assertEquals("Only the two actions", 2, rows.childCount.toLong())
            assertTrue("Action rows include meaningful icons", (rows.getChildAt(1) as ViewGroup).getChildAt(0) is ImageView)
        }
        val original = bounds(dialog)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        AppHarness.waitUntil("nested level", 5000) { visible("Quality") && visible("Choose source") }
        SystemClock.sleep(300); assertNoExitRows(); assertFalse(visible("Mark watched"))
        tap("Quality", true); AppHarness.waitUntil("third level", 5000) { visible("Balanced HD") }
        SystemClock.sleep(300); assertNoExitRows(); screenshot("validation-popup-nested.png")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        AppHarness.waitUntil("Left returns one level", 5000) { visible("Choose source") && !visible("Balanced HD") }
        SystemClock.sleep(300)
        instrumentation.runOnMainSync { assertEquals("quality", dialog.currentFocus!!.tag) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        AppHarness.waitUntil("Back returns to root", 5000) { visible("Mark watched") && !visible("Choose source") }
        SystemClock.sleep(300); assertEquals("Root geometry restored", original, bounds(dialog))
        instrumentation.runOnMainSync { assertEquals("playback", dialog.currentFocus!!.tag) }
        tap("Playback", false); tap("Quality", false); tap("Balanced HD", true)
        AppHarness.waitUntil("selection closes the whole window", 5000) { accepted.get() && !dialog.isShowing }
        windowReturned()
    }

    @Test @Throws(Exception::class) fun menusGrowFromTheirOpenerAndStayInsideEveryScreenEdge() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        val popup = AtomicReference<Tv.Alert>(); val opener = AtomicReference<View>()
        val start = AtomicReference<Rect>(); val frames: MutableList<Rect> = ArrayList()
        browse!!.onActivity { activity ->
            val button = ImageView(activity); button.setImageResource(R.drawable.ic_tv_more)
            button.setPadding(dp(11f), dp(11f), dp(11f), dp(11f)); button.setBackgroundResource(R.drawable.tv_button_round)
            button.contentDescription = "Open menu"
            val params = FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.TOP or Gravity.LEFT)
            params.leftMargin = dp(40f); params.topMargin = dp(30f)
            (activity.findViewById<View>(android.R.id.content) as FrameLayout).addView(button, params); opener.set(button)
            Tv.control(button, "popup-opener", Runnable {
                val actual = Rect(); button.getGlobalVisibleRect(actual); start.set(actual); frames.clear()
                val menu = Tv.choices(activity, "Episode options", arrayOf("Play", "Choose source", "Mark watched"), Consumer { i -> }, null)
                popup.set(menu); menu.show()
                val panel = menu.dialog.window!!.decorView.findViewWithTag<View>("popup-panel")
                panel.viewTreeObserver.addOnPreDrawListener(ViewTreeObserver.OnPreDrawListener {
                    val rect = Rect(); if (panel.isShown && panel.getGlobalVisibleRect(rect)) frames.add(rect); true
                })
            }, Consumer { key -> }, 1f, false)
        }
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        for (position in 0 until 4) {
            val corner = position
            browse!!.onActivity { activity ->
                val params = opener.get().layoutParams as FrameLayout.LayoutParams
                params.leftMargin = if (corner % 2 == 0) dp(40f) else screenWidth - dp(84f)
                params.topMargin = if (corner < 2) dp(30f) else screenHeight - dp(74f)
                opener.get().layoutParams = params
            }
            tap("Open menu", position % 2 == 0); AppHarness.waitUntil("menu", 5000) { visible("Choose source") }; SystemClock.sleep(350)
            assertNoExitRows()
            instrumentation.runOnMainSync {
                assertTrue("Morph opener=" + start.get() + ", first=" + (if (frames.isEmpty()) "none" else frames[0]), !frames.isEmpty() && Rect.intersects(start.get(), frames[0]))
                val end = boundsNow(popup.get().dialog)
                assertTrue(end.left >= dp(23f)); assertTrue(end.right <= screenWidth - dp(23f))
                assertTrue(end.top >= dp(23f)); assertTrue(end.bottom <= screenHeight - dp(23f))
                assertTrue("The menu expands beyond its control", frames.stream().anyMatch { rect -> rect.width() < end.width() * .8f })
                assertTrue("Header controls open below; bottom controls open above", if (corner < 2) end.top >= start.get().bottom else end.bottom <= start.get().top)
                val rows: ViewGroup = popup.get().buttons
                for (i in 0 until rows.childCount) {
                    val row = rows.getChildAt(i); val visible = Rect(); assertTrue(row.getGlobalVisibleRect(visible))
                    assertEquals("The whole action is inside the final panel", row.height.toLong(), visible.height().toLong())
                    assertTrue((row as ViewGroup).getChildAt(0) is ImageView)
                }
            }
            if (position == 1) screenshot("validation-popup-anchored.png")
            instrumentation.sendKeyDownUpSync(if (position % 2 == 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_BACK)
            AppHarness.waitUntil("remote dismisses root menu", 5000) { !popup.get().dialog.isShowing }; windowReturned()
            browse!!.onActivity { activity -> assertTrue("Focus returns to the opener", opener.get().hasFocus()) }
        }
    }

    @Test @Throws(Exception::class) fun longChoicesKeepTheSelectionAndBothRemoteExitKeysCancel() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        val popup = AtomicReference<Tv.Alert>(); val cancelled = AtomicInteger()
        val choices = arrayOfNulls<String>(24); for (i in choices.indices) choices[i] = "Source " + (i + 1)
        for (key in intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK)) {
            browse!!.onActivity { activity ->
                @Suppress("UNCHECKED_CAST")
                val menu = Tv.choices(activity, "Choose source", null, choices as Array<String>, 17, Consumer { i -> }, null, Runnable { cancelled.incrementAndGet() })
                popup.set(menu); menu.show()
            }
            AppHarness.waitUntil("initial selection scrolls into view", 5000) { visible("Source 18") }; SystemClock.sleep(350); assertNoExitRows()
            instrumentation.runOnMainSync {
                val selected = popup.get().dialog.currentFocus; assertEquals("choice:17", selected!!.tag)
                val visible = Rect(); assertTrue(selected.getGlobalVisibleRect(visible))
                assertEquals("Whole focus row is visible", selected.height.toLong(), visible.height().toLong())
            }
            val before = cancelled.get(); instrumentation.sendKeyDownUpSync(key)
            AppHarness.waitUntil("cancellation callback", 5000) { cancelled.get() == before + 1 && !popup.get().dialog.isShowing }; windowReturned()
        }
    }

    @Test @Throws(Exception::class) fun textEntryValidatesCommitsAndCanBeLeftWithoutSaving() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        val accepted = AtomicReference<String>()
        browse!!.onActivity { activity -> activity.edit("Rename title", "Title", "", Consumer { accepted.set(it) }) }
        AppHarness.waitUntil("entry form", 5000) { visible("Continue") }; assertNoExitRows()
        tap("Continue", true); AppHarness.waitUntil("empty value stays in the form", 5000) { visible("Enter a value") }
        assertNull(accepted.get()); enter("A new title"); tap("Continue", false)
        AppHarness.waitUntil("accepted value", 5000) { "A new title" == accepted.get() && !visible("Continue") }; windowReturned()
        browse!!.onActivity { activity -> activity.edit("Rename title", "Title", "Unchanged", Consumer { accepted.set(it) }) }
        AppHarness.waitUntil("second form", 5000) { visible("Continue") }; enter("Do not save")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        AppHarness.waitUntil("Left dismisses even from a focused input", 5000) { !visible("Continue") }
        assertEquals("A new title", accepted.get())
    }

    @Test @Throws(Exception::class) fun messageOnlyPopupsNeedNoDismissButton() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        val cancelled = AtomicInteger(); val popup = AtomicReference<Tv.Alert>()
        browse!!.onActivity { activity ->
            val message = Tv.alert(activity, "A quiet message", "Details are readable without a redundant confirmation action." as CharSequence)
            popup.set(message); message.dialog.setOnCancelListener { d -> cancelled.incrementAndGet() }; message.show()
        }
        AppHarness.waitUntil("message", 5000) { visible("A quiet message") }; assertNoExitRows()
        AppHarness.waitUntil("message window accepts remote input", 5000) {
            val ready = AtomicBoolean()
            instrumentation.runOnMainSync { ready.set(popup.get().dialog.window!!.decorView.hasWindowFocus() && popup.get().dialog.currentFocus != null) }
            ready.get()
        }
        SystemClock.sleep(300)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        AppHarness.waitUntil("Left closes message", 5000) { cancelled.get() == 1 && !popup.get().dialog.isShowing }
    }

    @Test @Throws(Exception::class) fun playbackFailureKeepsItsActionsButLeavesWithLeft() {
        readyLibrary("failover"); launch(); tap("Generated Film", false); tap("Sources", true); tap("Unavailable source", false)
        AppHarness.waitUntil("manual source failure", 20000) { state() != null && state()!!.error != null }
        assertFalse("No Close row on playback failures", visible("Close"))
        AppHarness.waitUntil("failure actions are readable", 5000) { visible("Try again") && visible("Choose source") }
        SystemClock.sleep(300)
        screenshot("validation-player-error-popup.png")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        AppHarness.waitUntil("Left exits the player from its error popup", 5000) { watch() == null }
    }

    @Test @Throws(Exception::class) fun nestedPagesMeasureTheirContentAndMorphNarrowWideNarrow() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }
        val popup = AtomicReference<Tv.Alert>(); val widths: MutableList<Int> = java.util.concurrent.CopyOnWriteArrayList()
        browse!!.onActivity { activity ->
            val root = Tv.alert(activity, "Options", null as View?); popup.set(root)
            root.submenu("audio", "Audio", Consumer { child ->
                child.button("description", "English subtitles with audio descriptions and accessibility support", Runnable { })
                child.submenu("speed", "Speed", Consumer { compact ->
                    compact.choice("normal", "1×", "", true, Runnable { compact.back() })
                    compact.choice("faster", "1.5×", "", false, Runnable { compact.back() })
                })
            }); root.show()
            val panel = root.dialog.window!!.decorView.findViewWithTag<View>("popup-panel")
            panel.viewTreeObserver.addOnPreDrawListener(ViewTreeObserver.OnPreDrawListener { if (panel.isShown) widths.add(panel.width); true })
        }
        AppHarness.waitUntil("compact root", 5000) { visible("Audio") }; SystemClock.sleep(350); val narrow = bounds(popup.get().dialog); screenshot("validation-popup-content-root.png")
        widths.clear(); tap("Audio", false); AppHarness.waitUntil("wide page", 5000) { visible("Speed") }; SystemClock.sleep(300)
        val wide = bounds(popup.get().dialog); screenshot("validation-popup-content-wide.png")
        assertTrue("Content, not the parent width, determines this level", wide.width() > narrow.width() + dp(120f))
        assertTrue("Width changes through intermediate frames", widths.stream().anyMatch { w -> w > narrow.width() + dp(10f) && w < wide.width() - dp(10f) })
        tap("Speed", true); AppHarness.waitUntil("compact value list", 5000) { visible("1×") }; SystemClock.sleep(300)
        val compact = bounds(popup.get().dialog); assertTrue(compact.width() < wide.width() - dp(120f))
        assertTrue(compact.top >= dp(71f)); assertTrue(compact.bottom <= context.resources.displayMetrics.heightPixels - dp(23f))
        screenshot("validation-popup-content-width.png")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); AppHarness.waitUntil("wide parent", 5000) { visible("Speed") }; SystemClock.sleep(300)
        assertEquals(wide, bounds(popup.get().dialog))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); AppHarness.waitUntil("narrow parent", 5000) { visible("Audio") && !visible("Speed") }; SystemClock.sleep(300)
        assertEquals(narrow, bounds(popup.get().dialog))
        instrumentation.runOnMainSync { assertEquals("audio", popup.get().dialog.currentFocus!!.tag) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); windowReturned()
    }

    private fun assertNoExitRows() { assertFalse(visible("Cancel")); assertFalse(visible("Close")); assertFalse(visible("Back")) }
    private fun bounds(dialog: Dialog): Rect { val value = AtomicReference<Rect>(); instrumentation.runOnMainSync { value.set(boundsNow(dialog)) }; return value.get() }
    private fun boundsNow(dialog: Dialog): Rect { val rect = Rect(); assertTrue(dialog.window!!.decorView.findViewWithTag<View>("popup-panel").getGlobalVisibleRect(rect)); return rect }
    private fun dp(value: Float): Int { return Tv.dp(context, value) }
    private fun windowReturned() {
        AppHarness.waitUntil("Activity regains window focus", 5000) { val focused = AtomicBoolean(); browse!!.onActivity { activity -> focused.set(activity.hasWindowFocus()) }; focused.get() }
    }
    @Throws(Exception::class)
    private fun screenshot(name: String) {
        val image = instrumentation.uiAutomation.takeScreenshot(); assertNotNull(image)
        FileOutputStream(File(context.filesDir, name)).use { out -> image.compress(Bitmap.CompressFormat.PNG, 100, out) }; image.recycle()
    }
}
