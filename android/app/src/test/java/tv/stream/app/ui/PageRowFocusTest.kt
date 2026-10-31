package tv.stream.app.ui

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import tv.stream.app.addon.Title

/**
 * Up and Down move one row of controls at a time through the page's own layout manager, even when the rows next to
 * the focused one have scrolled away, and Up from the first row goes to the top corner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PageRowFocusTest {
    @get:Rule val composition = createEmptyComposeRule()

    private fun tile(key: String) = Item.Tile(key, Item.Tile.Style.POSTER, key, "", null, null, Runnable {})
    private fun shelf(index: Int) = Item.shelf("shelf:$index", "Shelf $index", "", Item.Tile.Style.POSTER, (0 until 4).map { tile("shelf:$index:$it") })

    @Test fun controlRowsSkipBlocksWithoutControls() {
        val items = listOf(Item.spotlight(null), shelf(0), Item.header("Films"), Item.text("About", ""), shelf(1),
                Item.row("plain", 0, "Plain", "", null), Item.row("open", 0, "Open", "", Runnable {}),
                Item.actions("buttons", Item.Action("go", Item.Action.Style.SECONDARY, "Go", Runnable {})), Item.skeleton("Loading"))
        assertTrue(PageAdapter.interactive(items[1]))
        assertFalse("A heading is not a stop", PageAdapter.interactive(items[2]))
        assertFalse("A row without an action is not a stop", PageAdapter.interactive(items[5]))
        assertEquals(listOf(4, 1), PageAdapter.controlRows(items, 6, -1))
        assertEquals(listOf(6, 7), PageAdapter.controlRows(items, 4, 1))
        assertEquals(emptyList<Int>(), PageAdapter.controlRows(items, 1, -1))
    }

    @Test fun upWalksBackThroughEveryShelfAfterTheTopOnesScrolledAway() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val adapter = PageAdapter(activity, Artwork(activity.cacheDir), object : PageAdapter.Listener {
                override fun focused(key: String) {}
                override fun featured(title: Title?) {}
            })
            val list = RecyclerView(activity).apply {
                layoutManager = PageAdapter.Layout(activity, RecyclerView.VERTICAL, 40); itemAnimator = null
                this.adapter = adapter; clipToPadding = false
            }
            val top = View(activity).apply { id = tv.stream.app.R.id.home_refresh; isFocusable = true; isFocusableInTouchMode = true; tag = "refresh" }
            val root = FrameLayout(activity).apply {
                addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(top, FrameLayout.LayoutParams(80, 80).apply { topMargin = 40; leftMargin = 1780 })
            }
            activity.setContentView(root); controller.windowFocusChanged(true)
            adapter.set(listOf(Item.spotlight(null)) + (0 until 8).map { shelf(it) }, null)
            settle(root)
            assertTrue(PageFocus.request(list, "shelf:0:1")); settle(root)
            for (row in 1 until 8) {
                key(root, KeyEvent.KEYCODE_DPAD_DOWN)
                assertEquals("Down reaches shelf $row", "shelf:$row:1", root.findFocus()?.tag)
            }
            assertTrue("The page scrolled the first shelves away", list.canScrollVertically(-1))
            for (row in 6 downTo 0) {
                key(root, KeyEvent.KEYCODE_DPAD_UP)
                assertEquals("Up reaches shelf $row, never skipping to the top corner", "shelf:$row:1", root.findFocus()?.tag)
            }
            key(root, KeyEvent.KEYCODE_DPAD_UP)
            assertEquals("Only Up from the first shelf reaches the top corner", "refresh", root.findFocus()?.tag)
        } finally { controller.pause().stop().destroy() }
    }

    /** Dispatches like ViewRootImpl: an arrow nobody handles moves focus to the platform's focus search result. */
    private fun key(root: View, code: Int) {
        val handled = root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        val direction = if (code == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN
        if (!handled) {
            val focused = root.findFocus()
            val rect = android.graphics.Rect().also { focused?.getFocusedRect(it) }
            if (focused != null && root is ViewGroup) root.offsetDescendantRectToMyCoords(focused, rect)
            val next = focused?.focusSearch(direction)
            if (next != null && root is ViewGroup) { root.offsetRectIntoDescendantCoords(next, rect); next.requestFocus(direction, rect) }
        }
        root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        settle(root)
    }

    private fun settle(root: View) {
        repeat(3) {
            composition.waitForIdle()
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 1080)
            org.robolectric.shadows.ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        composition.waitForIdle()
    }
}
