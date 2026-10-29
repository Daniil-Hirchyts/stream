package tv.stream.app

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test
import org.junit.runner.RunWith
import tv.stream.app.addon.Cancellation
import tv.stream.app.data.InstalledAddon
import tv.stream.app.ui.Item
import tv.stream.app.ui.PageAdapter
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class LoadingTest : AppHarness() {
    private fun adapter(activity: MainActivity): PageAdapter {
        try { val f = MainActivity::class.java.getDeclaredField("adapter"); f.isAccessible = true; return f.get(activity) as PageAdapter }
        catch (e: Exception) { throw AssertionError(e) }
    }
    @Suppress("UNCHECKED_CAST") private fun items(activity: MainActivity): List<Item> {
        try { val f = PageAdapter::class.java.getDeclaredField("items"); f.isAccessible = true; return f.get(adapter(activity)) as List<Item> }
        catch (e: Exception) { throw AssertionError(e) }
    }
    @Test @Throws(Exception::class) fun slowSourcesKeepHeaderAndBackAndCancelWithoutReplacingDetails() {
        readyLibrary(); launch(); tap("Generated Film", false); waitUntil("details", 5000) { visible("Play") }
        for (a: InstalledAddon in services.store.addons()) services.addons.remove(a.id)
        services.addons.newRequest().use { request ->
            val url = "$origin/addon/slow/loading/manifest.json"
            services.addons.install(url, services.addons.preview(url, request))
        }
        tap("Sources", false)
        waitUntil("source header", 5000) { visible("Back") && visible("Generated Film") }
        waitUntil("slow source rows need placeholders", 5000) {
            val skeleton = AtomicBoolean()
            browse!!.onActivity { activity -> skeleton.set(items(activity).stream().anyMatch { item -> item.kind == Item.Kind.SKELETON }) }
            skeleton.get()
        }
        browse!!.onActivity { activity ->
            var partial = false
            for (item in items(activity)) if (item.kind == Item.Kind.SKELETON) {
                assertEquals("Only source rows load", "Source results", item.value); partial = true
            }
            assertTrue("Slow source request is still loading", partial)
        }
        tap("Back", true); waitUntil("back to media", 5000) { visible("Play") && visible("Sources") }
    }
    @Test @Throws(Exception::class) fun pointerOverVisiblePaginationDoesNotShiftPage() {
        readyLibrary(); launch(); tap("Browse", false); tap("Generated films", false)
        browse!!.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.page_rows)
            list.scrollToPosition(adapter(activity).getItemCount() - 1)
        }
        android.os.SystemClock.sleep(500); instrumentation.waitForIdleSync()
        val bounds = android.graphics.Rect(); val before = IntArray(2)
        browse!!.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.page_rows)
            val next = list.findViewWithTag<View>("next-page:bottom"); assertNotNull(next)
            assertTrue(next.getGlobalVisibleRect(bounds))
            before[0] = (list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition()
            before[1] = list.getChildAt(0).top
        }
        val time = android.os.SystemClock.uptimeMillis()
        val p = android.view.MotionEvent.PointerProperties()
        p.id = 0; p.toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE
        val c = android.view.MotionEvent.PointerCoords()
        c.x = bounds.centerX().toFloat(); c.y = bounds.centerY().toFloat(); c.pressure = 1f; c.size = 1f
        for (action in intArrayOf(android.view.MotionEvent.ACTION_HOVER_MOVE, android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_CANCEL)) {
            val event = android.view.MotionEvent.obtain(time, android.os.SystemClock.uptimeMillis(), action, 1,
                    arrayOf(p), arrayOf(c), 0,
                    if (action == android.view.MotionEvent.ACTION_DOWN) android.view.MotionEvent.BUTTON_PRIMARY else 0,
                    1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
            assertTrue("Inject mouse action $action", instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
        }
        android.os.SystemClock.sleep(400); instrumentation.waitForIdleSync()
        browse!!.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.page_rows)
            assertEquals("Pointer retains the visible row", before[0].toLong(), (list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition().toLong())
            assertEquals("Pointer doesn't move the content", before[1].toLong(), list.getChildAt(0).top.toLong())
        }
    }

}
