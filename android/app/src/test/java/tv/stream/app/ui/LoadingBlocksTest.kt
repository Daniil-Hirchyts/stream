package tv.stream.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
 * Loading surfaces draw their page's shape: a skeleton item never falls back to the spinner, which is only for pages
 * without a known layout. Also the Search results' focus scrolling and the focus shadow's exit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class LoadingBlocksTest {
    @get:Rule val composition = createEmptyComposeRule()

    private fun spinners(adapterItems: List<Item>): Int {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val adapter = PageAdapter(activity, Artwork(activity.cacheDir), object : PageAdapter.Listener {
                override fun focused(key: String) {}
                override fun featured(title: Title?) {}
            })
            val list = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity); itemAnimator = null; this.adapter = adapter }
            val root = FrameLayout(activity).apply { addView(list) }
            activity.setContentView(root)
            adapter.set(adapterItems, null)
            repeat(3) {
                composition.waitForIdle()
                root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 1920, 1080)
                org.robolectric.shadows.ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            composition.waitForIdle()
            return count(root)
        } finally { controller.pause().stop().destroy() }
    }
    private fun count(view: View): Int {
        if (view is ProgressBar) return 1
        if (view !is ViewGroup) return 0
        var found = 0
        for (i in 0 until view.childCount) found += count(view.getChildAt(i))
        return found
    }

    @Test fun everySkeletonDrawsItsPageShapeInsteadOfTheSpinner() {
        // The catalogue grid below a changed filter chip, the regression that showed a centred spinner.
        assertEquals("Grid skeleton under the chips", 0, spinners(listOf(
            Item.chips("filters:year", "", listOf(Item.Action("filter:year:2025", Item.Action.Style.CHIP, "2025", Runnable {}).selected(true))),
            Item.skeleton("Catalogue results"))))
        for (screen in SkeletonBlocks.SHAPES) assertEquals("$screen skeleton", 0, spinners(listOf(Item.skeleton(screen))))
        assertEquals("A page without a known layout keeps the spinner", 1, spinners(listOf(Item.loading())))
    }

    @Test fun skeletonNamesUsedByScreensAllHaveShapes() {
        for (screen in listOf("Home", "Home results", "Catalogue", "Catalogue results", "Add-ons", "Add-on review", "Source results", "Store results"))
            assertTrue(screen, SkeletonBlocks.SHAPES.contains(screen))
    }

    @Test fun focusedSearchResultsScrollClearOfTheViewportEdge() {
        val margin = 28f
        assertEquals("A card already inside the margins stays still", 0f, SearchBlocks.ResultScroll.distance(40f, 200f, 500f, margin), 0f)
        assertEquals("A card cut at the top moves down to the margin", -38f, SearchBlocks.ResultScroll.distance(-10f, 200f, 500f, margin), 0f)
        assertEquals("A card cut at the bottom moves up past the margin", 18f, SearchBlocks.ResultScroll.distance(290f, 200f, 500f, margin), 0f)
        assertEquals("A card just short of the bottom margin still moves", 8f, SearchBlocks.ResultScroll.distance(280f, 200f, 500f, margin), 0f)
        assertEquals("A card taller than the room keeps its artwork in view", 72f, SearchBlocks.ResultScroll.distance(100f, 480f, 500f, margin), 0f)
    }

    @Test fun focusShadowLeavesAtOnceWithThePlatter() {
        assertTrue("The shadow rises with focus", PageControls.raiseSpec(true) is TweenSpec)
        assertTrue("A settling shadow under a clear platter would show as a dark pill", PageControls.raiseSpec(false) is SnapSpec)
        assertFalse(PageControls.raiseSpec(false) is TweenSpec)
    }
}
