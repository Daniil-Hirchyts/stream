package tv.stream.app.ui

import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.LinearLayoutManager
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
import tv.stream.app.R
import tv.stream.app.addon.Title

/**
 * Declarative page buttons inside a real page RecyclerView: exact labels and sizes, focus by key, OK/Enter and touch
 * clicks after a rebind, and keeping the same control focused when the buttons around it change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PageControlsTest {
    @get:Rule val composition = createEmptyComposeRule()

    private class Page(activity: ComponentActivity) {
        val focused = ArrayList<String>()
        val adapter = PageAdapter(activity, Artwork(activity.cacheDir), object : PageAdapter.Listener {
            override fun focused(key: String) { focused.add(key) }
            override fun featured(title: Title?) {}
        })
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity); itemAnimator = null
            adapter = this@Page.adapter
        }
        val root = FrameLayout(activity).apply { addView(list) }
    }

    private fun page(block: (Page) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val page = Page(controller.get())
            controller.get().setContentView(page.root)
            controller.windowFocusChanged(true)
            block(page)
        } finally { controller.pause().stop().destroy() }
    }

    private fun action(key: String, style: Item.Action.Style, run: Runnable) = Item.Action(key, style, key, run)

    @Test fun buttonsKeepLabelsSizesFocusAndCurrentActions() = page { page ->
        val clicks = ArrayList<String>()
        page.adapter.set(listOf(Item.actions("title-actions",
            action("Play", Item.Action.Style.PRIMARY, Runnable { clicks.add("play") }),
            action("Sources", Item.Action.Style.SECONDARY, Runnable { clicks.add("sources") }),
        )), null)
        settle(page.root)
        composition.onNodeWithContentDescription("Play").assertIsDisplayed().assertHeightIsEqualTo(44.dp).assertWidthIsAtLeast(148.dp)
        composition.onNodeWithContentDescription("Sources").assertIsDisplayed().assertHeightIsEqualTo(44.dp).assertWidthIsAtLeast(96.dp)

        assertTrue(PageFocus.request(page.list, "Play")); settle(page.root)
        assertEquals("Play", page.root.findFocus()?.tag)
        key(page.root, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Sources", page.root.findFocus()?.tag)
        assertEquals("Sources", page.focused.last())
        key(page.root, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("sources"), clicks)

        // A new action list around the focused control keeps it focused and runs the current action.
        page.adapter.set(listOf(Item.actions("title-actions",
            action("Resume", Item.Action.Style.PRIMARY, Runnable { clicks.add("resume") }),
            action("Play", Item.Action.Style.SECONDARY, Runnable { clicks.add("play") }),
            action("Sources", Item.Action.Style.SECONDARY, Runnable { clicks.add("sources2") }),
        )), "Sources")
        settle(page.root)
        assertEquals("Sources", page.root.findFocus()?.tag)
        key(page.root, KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("sources", "sources2"), clicks)
        composition.onNodeWithContentDescription("Resume").performTouchInput { click() }
        settle(page.root)
        assertEquals(listOf("sources", "sources2", "resume"), clicks)
        assertEquals("A touch focuses too", "Resume", page.root.findFocus()?.tag)
    }

    @Test fun iconButtonsAndChipsStayKeyedAcrossBlocks() = page { page ->
        val clicks = ArrayList<String>()
        page.adapter.set(listOf(
            Item.actions("title-actions", action("Add favourite", Item.Action.Style.ICON, Runnable { clicks.add("favourite") }).icon(R.drawable.ic_tv_more)),
            Item.chips("filter", "Genre", listOf(action("All", Item.Action.Style.CHIP, Runnable { clicks.add("all") }).selected(true))),
        ), null)
        settle(page.root)
        composition.onNodeWithContentDescription("Add favourite").assertHeightIsEqualTo(44.dp).assertWidthIsEqualTo(44.dp)
        composition.onNodeWithContentDescription("All").assertHeightIsEqualTo(36.dp)
        assertTrue(PageFocus.request(page.list, "Add favourite")); settle(page.root)
        key(page.root, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Down leaves one composition for the next block", "All", page.root.findFocus()?.tag)
        key(page.root, KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("all"), clicks)
    }

    @Test fun paginationHasNoTargetWhereThereIsNoPage() = page { page ->
        page.adapter.set(listOf(Item.pagination("pages", "Page 1", listOf(action("next-page:x", Item.Action.Style.SECONDARY, Runnable {})))), null)
        settle(page.root)
        assertTrue(PageFocus.has(page.list, "next-page:x"))
        assertFalse(PageFocus.has(page.list, "previous-page:x"))
        assertTrue(PageFocus.request(page.list, "next-page:x")); settle(page.root)
        assertEquals("next-page:x", page.root.findFocus()?.tag)
    }

    private fun key(root: View, code: Int) {
        val handled = root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        val direction = when (code) {
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> 0
        }
        if (!handled && direction != 0) {
            val focused = root.findFocus()
            val rect = android.graphics.Rect().also { focused?.getFocusedRect(it) }
            if (focused != null && root is android.view.ViewGroup) root.offsetDescendantRectToMyCoords(focused, rect)
            val next = focused?.focusSearch(direction)
            if (next != null && root is android.view.ViewGroup) { root.offsetRectIntoDescendantCoords(next, rect); next.requestFocus(direction, rect) }
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
            org.robolectric.shadows.ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        composition.waitForIdle()
    }
}
