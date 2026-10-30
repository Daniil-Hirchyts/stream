package tv.stream.app.ui

import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
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
import tv.stream.app.addon.Title

/**
 * Settings and add-on rows drawn by Compose inside the page's RecyclerView: exact labels, focus by key (restored and
 * reported as `currentFocus.tag`), D-pad movement between blocks, OK/click actions, and the Settings split view's
 * category selection and sideways steering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PageRowsTest {
    @get:Rule val composition = createEmptyComposeRule()

    private class Page(val activity: ComponentActivity) {
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

    @Test fun rowsKeepLabelsFocusByKeyMoveByRemoteAndClick() = page { page ->
        val clicks = ArrayList<String>()
        page.adapter.set(listOf(
            Item.row("cache-size", 0, "Playback cache", "256 MiB", Runnable { clicks.add("cache") }),
            Item.source("source:1", "Generated Film", "1080p · MKV", "", "Ready", true, Runnable { clicks.add("source") }),
            Item.field("query", "Search", "Type a title", Runnable { clicks.add("field") }),
        ), null)
        settle(page.root)
        composition.onNodeWithContentDescription("Playback cache. 256 MiB").assertIsDisplayed()
        composition.onNodeWithContentDescription("Generated Film. 1080p · MKV. Ready").assertIsDisplayed()
        composition.onNodeWithContentDescription("Search. Type a title").assertIsDisplayed()

        assertTrue(PageFocus.has(page.list, "source:1"))
        assertTrue(PageFocus.request(page.list, "cache-size")); settle(page.root)
        assertEquals("cache-size", page.root.findFocus()?.tag)
        assertEquals("cache-size", page.focused.last())

        key(page.root, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Down leaves one composition for the next block", "source:1", page.root.findFocus()?.tag)
        key(page.root, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("source"), clicks)

        // A rebind with new values keeps focus on the same key and runs the new action.
        page.adapter.set(listOf(
            Item.row("cache-size", 0, "Playback cache", "512 MiB", Runnable { clicks.add("cache2") }),
            Item.source("source:1", "Generated Film", "1080p · MKV", "", "Ready", true, Runnable { clicks.add("source2") }),
            Item.field("query", "Search", "Type a title", Runnable { clicks.add("field") }),
        ), "source:1")
        settle(page.root)
        composition.onNodeWithContentDescription("Playback cache. 512 MiB").assertIsDisplayed()
        assertEquals("source:1", page.root.findFocus()?.tag)
        key(page.root, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("cache-size", page.root.findFocus()?.tag)
        key(page.root, KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("source", "cache2"), clicks)
        // Pointer input outlives the rebind of a changed row and still runs its current action.
        composition.onNodeWithContentDescription("Playback cache. 512 MiB").performTouchInput { click() }
        settle(page.root)
        assertEquals(listOf("source", "cache2", "cache2"), clicks)
        assertEquals("The first focus target is the first declarative block", page.list.getChildAt(0).let { (it as FrameLayout).getChildAt(0) },
                PageFocus.first(page.list))
    }

    @Test fun settingsSplitSelectsOnFocusAndSteersBetweenColumns() = page { page ->
        val chosen = ArrayList<String>()
        val opened = ArrayList<String>()
        fun category(key: String, title: String) = Item.row(key, 0, title, "", null)
        val categories = listOf(category("Add-ons", "Add-ons"), category("Languages", "Languages"), category("Clock", "Clock"))
        val panes = linkedMapOf(
            "Add-ons" to listOf(Item.header("Installed"), Item.row("addon:cinemeta", 0, "Cinemeta", "Catalogues", Runnable { opened.add("cinemeta") })),
            "Languages" to listOf(Item.row("audio-language", 0, "Audio", "English", Runnable { opened.add("audio") }),
                    Item.text("", "Used when a source offers several.")),
            "Clock" to listOf(Item.row("clock-format", 0, "Format", "Automatic", Runnable { opened.add("clock") })),
        )
        page.adapter.set(listOf(Item.split("settings", categories, panes, "Add-ons", { chosen.add(it) })), null)
        settle(page.root)
        composition.onNodeWithContentDescription("Cinemeta. Catalogues").assertIsDisplayed()

        assertTrue(PageFocus.request(page.list, "Add-ons")); settle(page.root)
        key(page.root, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Languages", page.root.findFocus()?.tag)
        assertEquals("Focusing a category selects it", listOf("Languages"), chosen)
        composition.onNodeWithContentDescription("Audio. English").assertIsDisplayed()

        key(page.root, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Right enters the pane at its first row", "audio-language", page.root.findFocus()?.tag)
        key(page.root, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Left returns to the lit category", "Languages", page.root.findFocus()?.tag)

        key(page.root, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Clock", page.root.findFocus()?.tag)
        key(page.root, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("OK on a category opens its pane", "clock-format", page.root.findFocus()?.tag)
        key(page.root, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("clock"), opened)
        assertFalse(PageFocus.has(page.list, "addon:cinemeta"))

        // A rebuilt page restores a pane row by key, selecting its category first.
        page.adapter.set(emptyList(), null); settle(page.root)
        page.adapter.set(listOf(Item.split("settings", categories, panes, "Add-ons", { chosen.add(it) })), "audio-language")
        settle(page.root)
        assertEquals("audio-language", page.root.findFocus()?.tag)
    }

    /** Dispatches like ViewRootImpl: an arrow nobody handles moves focus to the platform's focus search result. */
    private fun key(root: View, code: Int) {
        val handled = root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        val direction = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP; KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
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
