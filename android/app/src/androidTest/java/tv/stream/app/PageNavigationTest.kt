package tv.stream.app

import org.junit.Assert.*
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import org.junit.Test

/** Observe rendered frames: restoring a cached page must still animate the visible content. */
class PageNavigationTest : AppHarness() {
    @Test @Throws(Exception::class) fun tabsPushBackSearchAndCachedHomeAnimateAfterTheirContentIsLaidOut() {
        launch(); AppHarness.waitUntil("Home", 10000) { visible("Make yourself at home") }; SystemClock.sleep(350)
        motion(Consumer { activity -> activity.root("Settings") }, 0)
        motion(Consumer { activity -> activity.push(Route("Keyboard languages")) }, 1)
        motion(Consumer { activity -> activity.back() }, -1)
        motion(Consumer { activity -> activity.root("Home") }, 0)
        motion(Consumer { activity -> activity.search() }, 0)
        motion(Consumer { activity -> activity.back() }, 0)
    }
    private fun motion(navigate: Consumer<MainActivity>, direction: Int) {
        val movingContent = AtomicBoolean()
        val observer = AtomicReference<ViewTreeObserver.OnDrawListener>()
        browse!!.onActivity { activity ->
            val rows = activity.findViewById<RecyclerView>(R.id.page_rows)
            observer.set(ViewTreeObserver.OnDrawListener {
                val distance = if (direction == 0) rows.translationY else direction * rows.translationX
                if (rows.childCount > 0 && rows.alpha > 0.01f && rows.alpha < .99f && distance > 1f) movingContent.set(true)
            })
            rows.viewTreeObserver.addOnDrawListener(observer.get()); navigate.accept(activity)
        }
        AppHarness.waitUntil("visible page slides and fades in direction $direction", 5000) { movingContent.get() }
        AppHarness.waitUntil("page transition settles", 5000) {
            val settled = AtomicBoolean()
            browse!!.onActivity { activity ->
                val rows = activity.findViewById<View>(R.id.page_rows)
                settled.set(rows.alpha == 1f && rows.translationX == 0f && rows.translationY == 0f)
            }
            settled.get()
        }
        browse!!.onActivity { activity -> activity.findViewById<View>(R.id.page_rows).viewTreeObserver.removeOnDrawListener(observer.get()) }
    }
}
