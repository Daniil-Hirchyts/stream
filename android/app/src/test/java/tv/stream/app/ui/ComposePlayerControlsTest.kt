package tv.stream.app.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.doOnAttach
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Rule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import tv.stream.app.PlayerControlsState
import tv.stream.app.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ComposePlayerControlsTest {
    @get:Rule val composition = createEmptyComposeRule()
    @Test fun startingDuringWindowAttachmentCreatesControlsBeforeOpeningMedia() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            activity.setTheme(R.style.TvTheme)
            val root = activity.layoutInflater.inflate(R.layout.activity_watch, null) as FrameLayout
            root.setViewTreeLifecycleOwner(activity)
            root.setViewTreeViewModelStoreOwner(activity)
            root.setViewTreeSavedStateRegistryOwner(activity)
            val controls = root.findViewById<FrameLayout>(R.id.controls)
            var ready = false
            controls.doOnAttach {
                ComposePlayerControls.install(controls, MutableStateFlow(PlayerControlsState("Film"))) {
                    assertNotNull(root.findViewById<View>(R.id.progress))
                    assertNotNull(root.findViewById<View>(R.id.control_options))
                    ready = true
                }
            }
            activity.setContentView(root)
            settle(root)
            assertTrue("The player can resolve media after its native controls attach", ready)
            assertEquals("Film", root.findViewById<TextView>(R.id.controls_title).text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun compositionKeepsNativeControlGeometryParentsFocusAndActions() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            activity.setTheme(R.style.TvTheme)
            activity.setContentView(R.layout.activity_watch)
            val root = activity.findViewById<FrameLayout>(R.id.player_root)
            val controls = activity.findViewById<FrameLayout>(R.id.controls).apply { visibility = View.VISIBLE }
            activity.findViewById<TextView>(R.id.controls_title).text = "Film"
            activity.findViewById<TextView>(R.id.watching_info).apply { text = "S1 E2"; visibility = View.VISIBLE }
            settle(root)
            val ids = intArrayOf(R.id.player_header, R.id.control_stop, R.id.control_watched,
                R.id.control_options, R.id.control_settings, R.id.progress, R.id.elapsed, R.id.remaining)
            val views = ids.associateWith { activity.findViewById<View>(it) }
            val before = views.mapValues { bounds(root, it.value) }
            val timelineParent = views.getValue(R.id.progress).parent
            val controlsParent = controls.parent
            val button = views.getValue(R.id.control_watched)
            var clicks = 0
            button.isFocusable = true
            // onKey grants the native header target touch-mode focus before requesting it.
            button.isFocusableInTouchMode = true
            assertTrue("Baseline native header accepts remote focus", button.requestFocus())
            assertSame(button, root.findFocus())
            root.requestFocus()
            button.setOnClickListener { clicks++ }
            val state = MutableStateFlow(PlayerControlsState("Film", "S1 E2", false))

            val composition = ComposePlayerControls.install(controls, state)
            settle(root)

            assertFalse(composition.isFocusable)
            assertSame(controlsParent, controls.parent)
            assertSame(timelineParent, activity.findViewById<View>(R.id.progress).parent)
            for (id in ids) {
                assertSame("Native view identity $id", views.getValue(id), activity.findViewById<View>(id))
                assertEquals("Control bounds $id", before.getValue(id), bounds(root, views.getValue(id)))
            }
            assertEquals((48 * activity.resources.displayMetrics.density).toInt(), views.getValue(R.id.progress).height)
            assertEquals("Film", activity.findViewById<TextView>(R.id.controls_title).text.toString())
            assertTrue(button.requestFocus())
            assertSame(button, root.findFocus())
            button.performClick()
            assertEquals(1, clicks)

            state.value = PlayerControlsState("Film", "S1 E3", true)
            settle(root)
            assertSame(button, root.findFocus())
            assertSame(button, activity.findViewById<View>(R.id.control_watched))
            assertTrue(button.isActivated)
            assertEquals("Mark unwatched", button.contentDescription)
            assertEquals("Mark unwatched", button.tooltipText)
            assertEquals("S1 E3", activity.findViewById<TextView>(R.id.watching_info).text.toString())
            assertEquals(before.getValue(R.id.progress), bounds(root, views.getValue(R.id.progress)))
            button.performClick()
            assertEquals(2, clicks)
        } finally { controller.pause().stop().destroy() }
    }

    private fun settle(root: View) {
        composition.waitForIdle()
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        composition.waitForIdle()
    }

    private fun bounds(root: View, view: View): Rect {
        val at = IntArray(2); val origin = IntArray(2)
        view.getLocationInWindow(at); root.getLocationInWindow(origin)
        return Rect(at[0] - origin[0], at[1] - origin[1], at[0] - origin[0] + view.width, at[1] - origin[1] + view.height)
    }
}
