package tv.stream.app

import android.view.KeyEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class TransitionFocusTest : AppHarness() {
    /** Hold metadata work so the intermediate frame is observable without relying on network timing. */
    @Throws(Exception::class)
    private fun holdPageWork(): CountDownLatch {
        val release = CountDownLatch(1); val entered = CountDownLatch(2)
        for (i in 0 until 2) app.workers.submit(Runnable {
            entered.countDown()
            try { release.await(10, TimeUnit.SECONDS) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        })
        assertTrue("Page workers held", entered.await(3, TimeUnit.SECONDS)); return release
    }
    private fun focusTag(): String {
        val tag = AtomicReference("")
        browse!!.onActivity { activity -> val focus: View? = activity.currentFocus; if (focus != null) tag.set((focus.tag as Any?).toString()) }
        return tag.get()
    }
    @Test @Throws(Exception::class) fun openingFilmGoesDirectlyFromContentToPlayWithoutHighlightingNavigation() {
        readyLibrary(); launch(); AppHarness.waitUntil("home", 10000) { visible("Generated Film") }
        val focusEvents: MutableList<String> = CopyOnWriteArrayList()
        browse!!.onActivity { activity -> activity.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, next ->
            if (next != null) focusEvents.add((next.tag as Any?).toString())
        } }
        val release = holdPageWork()
        try {
            tap("Generated Film", true)
            browse!!.onActivity { activity ->
                assertFalse("No navigation fallback during metadata loading", activity.findViewById<View>(R.id.top_bar).hasFocus())
                assertTrue("Content holds neutral focus", activity.findViewById<View>(R.id.page_rows).isFocused)
            }
            assertFalse("No Home flash", focusEvents.contains("nav:Home"))
        } finally { release.countDown() }
        AppHarness.waitUntil("Play receives focus", 10000) { "play" == focusTag() }
        assertFalse("No intermediate navigation highlight", focusEvents.stream().anyMatch { tag -> tag.startsWith("nav:") })
    }
    @Test @Throws(Exception::class) fun deliberateToolbarFocusDuringLoadingIsNotOverriddenWhenPlayArrives() {
        readyLibrary(); launch(); AppHarness.waitUntil("home", 10000) { visible("Generated Film") }
        val release = holdPageWork()
        try {
            tap("Generated Film", false)
            // Details deliberately hides the rail; its Back control remains a real navigation choice.
            browse!!.onActivity { activity -> assertTrue(activity.findViewById<View>(R.id.page_back).requestFocus()) }
            AppHarness.waitUntil("viewer selected Back", 3000) {
                val selected = java.util.concurrent.atomic.AtomicBoolean()
                browse!!.onActivity { selected.set(it.findViewById<View>(R.id.page_back).hasFocus()) }
                selected.get()
            }
        } finally { release.countDown() }
        AppHarness.waitUntil("details finished", 10000) { visible("Play") }
        instrumentation.waitForIdleSync()
        browse!!.onActivity { assertTrue("Late content keeps the viewer's navigation choice", it.findViewById<View>(R.id.page_back).hasFocus()) }
    }
}
