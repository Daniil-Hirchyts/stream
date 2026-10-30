package tv.stream.app.ui

import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.doOnAttach
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import tv.stream.app.PlayerControlsState
import tv.stream.app.R
import tv.stream.playback.ControlsOverlay

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ComposePlayerControlsTest {
    @get:Rule val composition = createEmptyComposeRule()
    @Test fun startingDuringWindowAttachmentCreatesControlsBeforeOpeningMedia() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            val root = FrameLayout(activity)
            val layer = FrameLayout(activity).apply { id = R.id.controls }
            root.addView(layer, FrameLayout.LayoutParams(-1, -1))
            var ready = false
            lateinit var host: ComposePlayerControls
            layer.doOnAttach {
                host = ComposePlayerControls.install(layer, MutableStateFlow(PlayerControlsState("Film")), {}) {
                    assertTrue("Declarative controls register before opening media", host.focus.hasControls)
                    ready = true
                }
            }
            activity.setContentView(root)
            settle(root)
            assertTrue("The player can resolve media after its controls attach", ready)
            composition.onNodeWithTag(activity.resources.getResourceName(R.id.controls_title)).assertTextEquals("Film")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun compositionKeepsControlGeometryFocusAndCurrentActionsAcrossStateUpdates() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            val root = FrameLayout(activity)
            val layer = FrameLayout(activity)
            root.addView(layer, FrameLayout.LayoutParams(-1, -1)); activity.setContentView(root)
            val clicks = ArrayList<Int>()
            val state = MutableStateFlow(PlayerControlsState("Film", "S1 E2", false))
            val host = ComposePlayerControls.install(layer, state, { clicks.add(it) })
            host.render(ControlsOverlay.State(seekable = true, progress = 428, buffered = 520, elapsed = "41:02", remaining = "−55:21"))
            settle(root); controller.windowFocusChanged(true)
            val timeline = host.bounds(R.id.progress)!!
            assertEquals((48 * activity.resources.displayMetrics.density).toInt(), timeline.height())
            assertFalse("Composition boundary doesn't take remote focus", host.isFocusable)
            assertTrue(host.request(R.id.control_watched)); settle(root)
            assertEquals(R.id.control_watched, host.focusedId())
            host.activate(R.id.control_watched)
            assertEquals(listOf(R.id.control_watched), clicks)

            state.value = PlayerControlsState("Film", "S1 E3", true); settle(root)
            assertEquals("Recomposition keeps the focused action", R.id.control_watched, host.focusedId())
            composition.onNodeWithTag(activity.resources.getResourceName(R.id.control_watched)).assertContentDescriptionEquals("Mark unwatched")
            composition.onNodeWithTag(activity.resources.getResourceName(R.id.watching_info)).assertTextEquals("S1 E3")
            assertEquals("State updates don't move the timeline", timeline, host.bounds(R.id.progress))
            composition.onNodeWithTag(activity.resources.getResourceName(R.id.control_watched)).performTouchInput { click() }
            assertEquals(listOf(R.id.control_watched, R.id.control_watched), clicks)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun timelineTapTogglesAndADragSeeksOnlyWhenReleased() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val root = FrameLayout(activity); activity.setContentView(root)
            val host = ComposePlayerControls.install(root, MutableStateFlow(PlayerControlsState("Film")), {})
            var toggles = 0; var starts = 0; var stops = 0
            val previews = ArrayList<Int>()
            host.bind(object : ControlsOverlay.Input {
                override fun togglePlay() { toggles++ }
                override fun startScrub() { starts++ }
                override fun scrub(value: Int) { previews.add(value) }
                override fun stopScrub() { stops++ }
            }, Runnable {})
            host.render(ControlsOverlay.State(seekable = true))
            settle(root)
            val timeline = composition.onNodeWithTag(activity.resources.getResourceName(R.id.progress))
            timeline.performTouchInput { click() }
            assertEquals(1, toggles); assertEquals(0, starts)
            timeline.performTouchInput {
                down(Offset(width * .1f, height / 2f))
                moveTo(Offset(width * .5f, height / 2f), delayMillis = 100)
            }
            assertEquals(1, starts); assertEquals("The drag only previews before release", 0, stops)
            timeline.performTouchInput { up() }
            assertEquals(1, stops); assertEquals("A drag must not also toggle playback", 1, toggles)
            assertTrue(previews.isNotEmpty()); assertTrue(previews.last() in 490..510)
        } finally { controller.pause().stop().destroy() }
    }

    private fun settle(root: View) {
        repeat(3) {
            composition.waitForIdle()
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 1080)
        }
        composition.waitForIdle()
    }
}
