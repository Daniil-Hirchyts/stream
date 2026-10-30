package tv.stream.app

import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import tv.stream.app.ui.ComposePlayerControls
import tv.stream.playback.ControlsOverlay

/** Rendered Compose geometry: feedback keeps its time anchor, clears the end label and preserves the full touch target. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerTimelineLayoutTest {
    @get:Rule val composition = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var root: FrameLayout
    private lateinit var controls: ComposePlayerControls
    private var state = ControlsOverlay.State(elapsed = "1:22:03", remaining = "−1:14:35", progress = 500, seekable = true)

    @Before fun start() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        root = FrameLayout(activity).apply { clipChildren = false }
        activity.setContentView(root)
        controls = ComposePlayerControls.install(root, MutableStateFlow(PlayerControlsState("A film")), {})
        controls.render(state)
        controller.windowFocusChanged(true)
        settle()
    }
    @After fun stop() { controller.pause().stop().destroy() }

    @Test fun transientFeedbackKeepsTheTimeAnchoredAndUsesOneStatus() {
        val before = center()
        assertEquals(playhead(), before, 1f)
        assertNotNull(controls.bounds(R.id.timeline_paused))
        state = state.copy(seekLabel = "+1:20"); controls.render(state); settle()
        assertEquals("Feedback must not recenter the timestamp", before, center(), 1f)
        assertNull("Seek feedback replaces the paused badge", controls.bounds(R.id.timeline_paused))
        assertNotNull(controls.bounds(R.id.seek_bubble))
        state = state.copy(seekLabel = ""); controls.render(state); settle()
        assertNotNull(controls.bounds(R.id.timeline_paused))
        assertNull(controls.bounds(R.id.seek_bubble))
        assertEquals(before, center(), 1f)
    }

    @Test fun longTimesAndFeedbackStayClearAtBothEdgesAndInRtl() {
        for (direction in intArrayOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            root.layoutDirection = direction
            for (position in intArrayOf(0, 50, 500, 900, 995, 1000)) {
                state = state.copy(progress = position, seekLabel = "+1:20"); controls.render(state); settle()
                val info = controls.bounds(R.id.timeline_info)!!; val end = controls.bounds(R.id.remaining)!!; val track = controls.bounds(R.id.progress)!!
                assertTrue("Readout stays inside the timeline: direction=$direction position=$position info=$info track=$track", info.left >= track.left && info.right <= track.right)
                if (direction == View.LAYOUT_DIRECTION_LTR) assertTrue("Readout clears remaining time", info.right < end.left)
                else assertTrue("RTL readout clears remaining time", info.left > end.right)
                if (position == 500) assertEquals("Midpoint anchor, direction=$direction", playhead(), center(), 1f)
            }
        }
    }

    @Test fun completeTimelineTouchTargetClearsCaptionsAndHeaderButtonsCanGrow() {
        composition.onNodeWithTag(root.resources.getResourceName(R.id.progress)).assertHeightIsEqualTo(48.dp)
        val track = controls.bounds(R.id.progress)!!
        assertTrue("Caption clearance includes the whole timeline and its readout", root.height - controls.bottomClearance() < track.top)
        for (id in intArrayOf(R.id.control_stop, R.id.control_watched, R.id.control_options, R.id.control_settings)) {
            assertTrue(controls.request(id)); settle()
            assertEquals(id, controls.focusedId())
            val button = controls.bounds(id)!!
            assertTrue("Grown button is inside the picture", button.left >= 0 && button.top >= 0 && button.right <= root.width && button.bottom <= root.height)
        }
        assertNull("There is no centre control or hidden transport layout", controls.bounds(R.id.center_icon))
    }

    private fun center(): Float = controls.bounds(R.id.elapsed)!!.exactCenterX()
    private fun playhead(): Float {
        val track = controls.bounds(R.id.progress)!!
        val inset = 4 * root.resources.displayMetrics.density
        val fraction = if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) 1 - state.progress / 1000f else state.progress / 1000f
        return track.left + inset + (track.width() - 2 * inset) * fraction
    }
    private fun settle() {
        repeat(3) {
            composition.waitForIdle()
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 1080)
        }
        composition.waitForIdle()
    }
}
