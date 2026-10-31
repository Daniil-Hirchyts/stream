package tv.stream.app.ui

import android.graphics.drawable.AnimatedVectorDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * The title page's icon buttons: a focused one's caption is centred directly under it at a fixed gap, whatever the
 * row's page margin, and toggling favourite morphs its heart while the accessible names stay exact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ActionCaptionTest {
    @get:Rule val composition = createEmptyComposeRule()

    private class Page(activity: ComponentActivity) {
        val adapter = PageAdapter(activity, Artwork(activity.cacheDir), object : PageAdapter.Listener {
            override fun focused(key: String) {}
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

    @After fun clearObserver() { PageControls.captionPlaced = null }

    private fun actions(favourite: Boolean) = Item.actions("title-actions",
        Item.Action("play", Item.Action.Style.PRIMARY, "Play", Runnable {}).icon(R.drawable.ic_tv_play).detail("S2 E2"),
        Item.Action("sources", Item.Action.Style.ICON, "Sources", Runnable {}).icon(R.drawable.ic_tv_list),
        Item.Action("favourite", Item.Action.Style.ICON, if (favourite) "Remove favourite" else "Add favourite", Runnable {})
            .icon(if (favourite) R.drawable.ic_tv_heart else R.drawable.ic_tv_heart_outline).selected(favourite),
        Item.Action("more", Item.Action.Style.ICON, "More", Runnable {}).icon(R.drawable.ic_tv_more))

    @Test fun captionIsCentredUnderTheFocusedIconButton() = page { page ->
        val placed = HashMap<String, Pair<Rect, Rect>>()
        PageControls.captionPlaced = { label, text, button -> placed[label] = text to button }
        page.adapter.set(listOf(actions(false)), null)
        settle(page.root)
        val density = page.root.resources.displayMetrics.density
        for (key in listOf("more", "favourite", "sources")) {
            assertTrue(PageFocus.request(page.list, key)); settle(page.root)
            val label = when (key) { "more" -> "More"; "favourite" -> "Add favourite"; else -> "Sources" }
            assertNotNull("$label has a caption", placed[label])
            val (text, button) = placed[label]!!
            assertEquals("$label's caption is centred under its button", button.center.x, text.center.x, 1.5f)
            val gap = (text.top - button.bottom) / density
            assertTrue("$label's caption sits just below its button, not ${gap}dp away", gap in 4f..12f)
        }
    }

    @Test fun favouriteMorphsInPlaceAndKeepsExactLabels() = page { page ->
        page.adapter.set(listOf(actions(false)), null)
        settle(page.root)
        composition.onNodeWithContentDescription("Add favourite").assertIsDisplayed()
        assertNull("A first draw shows the static glyph", page.root.findViewWithTag<View>("icon-morph"))
        page.adapter.set(listOf(actions(true)), "favourite")
        settle(page.root)
        composition.onNodeWithContentDescription("Remove favourite").assertIsDisplayed()
        val morph = page.root.findViewWithTag<ImageView>("icon-morph")
        assertNotNull("Toggling plays the heart's morph", morph)
        assertTrue(morph.drawable is AnimatedVectorDrawable)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, morph.importantForAccessibility)
    }

    @Test fun onlyAToggleGlyphPairMorphs() {
        assertEquals(R.drawable.avd_tv_favourite_on, PageControls.morph(R.drawable.ic_tv_heart_outline, R.drawable.ic_tv_heart))
        assertEquals(R.drawable.avd_tv_favourite_off, PageControls.morph(R.drawable.ic_tv_heart, R.drawable.ic_tv_heart_outline))
        assertEquals(R.drawable.avd_tv_watched_on, PageControls.morph(R.drawable.ic_tv_check, R.drawable.ic_tv_unwatched))
        assertEquals(R.drawable.avd_tv_watched_off, PageControls.morph(R.drawable.ic_tv_unwatched, R.drawable.ic_tv_check))
        assertEquals(0, PageControls.morph(R.drawable.ic_tv_more, R.drawable.ic_tv_heart))
    }

    private fun settle(root: View) {
        repeat(6) {
            composition.waitForIdle()
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 1080)
            org.robolectric.shadows.ShadowLooper.idleMainLooper(80, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        composition.waitForIdle()
    }
}
