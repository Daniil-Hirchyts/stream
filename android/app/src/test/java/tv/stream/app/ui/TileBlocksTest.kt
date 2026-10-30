package tv.stream.app.ui

import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import org.json.JSONArray
import org.json.JSONObject
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
import org.robolectric.shadows.ShadowLooper
import tv.stream.app.TitleScreens
import tv.stream.app.addon.Title
import tv.stream.app.data.TvStore

/** The real page composition must change seasons on focus and replace callbacks when episode progress changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TileBlocksTest {
    @get:Rule val composition = createEmptyComposeRule()

    private class Scene(activity: ComponentActivity) {
        val artwork = Artwork(activity.cacheDir)
        val focused = ArrayList<String>()
        val selections = ArrayList<String>()
        val clicks = ArrayList<Boolean>()
        val adapter = PageAdapter(activity, artwork, object : PageAdapter.Listener {
            override fun focused(key: String) { focused.add(key) }
            override fun featured(title: Title?) {}
        })
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            itemAnimator = null
            adapter = this@Scene.adapter
        }
        val root = FrameLayout(activity).apply { addView(list) }

        fun episodeItem(watched: Boolean): Item {
            val episodes = JSONArray()
            for (season in 1..2) for (number in 1..3) episodes.put(JSONObject()
                .put("id", "show:$season:$number").put("season", season).put("episode", number)
                .put("title", "Season $season episode $number"))
            val title = requireNotNull(Title.parse("local", JSONObject().put("id", "show").put("type", "series")
                .put("name", "Fixture series").put("videos", episodes)))
            val saved = if (watched) mapOf("show:1:1" to TvStore.Progress(title.key(), "show:1:1", 0, 0, true, 1)) else emptyMap()
            val panes = TitleScreens.seasonPanes(title, listOf(1, 2), saved, emptySet()) { _, completed, _ ->
                Runnable { clicks.add(completed) }
            }
            val actions = (1..2).map { season ->
                Item.Action("season:$season", Item.Action.Style.CHIP, "Season $season", Runnable {}).selected(season == 1)
            }
            return Item.episodes(actions, panes, "season:1", Consumer { selections.add(it) })
        }
    }

    private fun scene(block: (Scene) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val scene = Scene(controller.get())
        try {
            controller.get().setContentView(scene.root)
            controller.windowFocusChanged(true)
            block(scene)
        } finally {
            controller.pause().stop().destroy()
            scene.artwork.close()
        }
    }

    @Test fun rightFromASeasonSelectsItsEpisodeShelfWithoutAClick() = scene { scene ->
        scene.adapter.set(listOf(scene.episodeItem(false)), "season:1")
        settle(scene.root)
        assertTrue(PageFocus.request(scene.list, "season:1"))
        settle(scene.root)
        assertEquals("season:1", scene.root.findFocus()?.tag)
        composition.onNodeWithTag("episode:show:1:1").assertIsDisplayed()

        key(scene.root, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Focus callbacks: ${scene.focused}", "season:2", scene.root.findFocus()?.tag)
        assertEquals(listOf("season:2"), scene.selections)
        composition.onNodeWithTag("episode:show:2:1").assertIsDisplayed()
        assertFalse("The old season no longer owns focus targets", PageFocus.has(scene.list, "episode:show:1:1"))
        key(scene.root, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("episode:show:2:1", scene.root.findFocus()?.tag)
    }

    @Test fun rebindingWatchedProgressUpdatesTheCardAndItsRemoteAndTouchActions() = scene { scene ->
        scene.adapter.set(listOf(scene.episodeItem(false)), "episode:show:1:1")
        settle(scene.root)
        assertTrue(PageFocus.request(scene.list, "episode:show:1:1"))
        key(scene.root, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(false), scene.clicks)

        scene.adapter.set(listOf(scene.episodeItem(true)), "episode:show:1:1")
        settle(scene.root)
        assertEquals("episode:show:1:1", scene.root.findFocus()?.tag)
        composition.onNodeWithTag("episode:show:1:1")
            .assertContentDescriptionEquals("Episode 1. Season 1 episode 1. Watched")
        key(scene.root, KeyEvent.KEYCODE_ENTER)
        composition.onNodeWithTag("episode:show:1:1").performTouchInput { click() }
        settle(scene.root)
        assertEquals("Both input paths must use the newly published progress", listOf(false, true, true), scene.clicks)
    }

    private fun key(root: View, code: Int) {
        val handled = root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        val direction = when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
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
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
        }
        composition.waitForIdle()
    }
}
