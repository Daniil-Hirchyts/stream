package tv.stream.app.ui

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import tv.stream.app.R
import tv.stream.playback.PlayerScreen

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ComposePlayerLayersTest {
    @get:Rule val composition = createEmptyComposeRule()
    @Test fun normalLoadingShowsTheTitleWithoutTransportDiagnostics() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            activity.setContentView(R.layout.activity_watch)
            val layers = ComposePlayerLayers(activity, false, {})
            layers.install(); layers.render(PlayerScreen.LayerState(loading = true, title = "Film", step = "Opening sockets", message = "Private diagnostic"))
            val root = activity.findViewById<View>(R.id.player_root)
            activity.findViewById<View>(R.id.loading).visibility = View.VISIBLE
            settle(root)
            composition.onNodeWithTag(activity.resources.getResourceName(R.id.loading_title)).assertTextEquals("Film")
            composition.onNodeWithTag(activity.resources.getResourceName(R.id.loading_step)).assertDoesNotExist()
            composition.onNodeWithTag(activity.resources.getResourceName(R.id.loading_message)).assertDoesNotExist()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun failureAndNextEpisodeActionsKeepTheirLabelsFocusAndCallbacks() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            activity.setContentView(R.layout.activity_watch)
            val chosen = ArrayList<String>()
            val layers = ComposePlayerLayers(activity, true, { chosen.add("offer") })
            layers.bind(object : PlayerScreen.LayerInput {
                override fun retry() { chosen.add("retry") }
                override fun sources() { chosen.add("sources") }
                override fun exit() { chosen.add("exit") }
                override fun playNext() { chosen.add("play") }
                override fun cancelNext() { chosen.add("cancel") }
            })
            layers.install(); layers.render(PlayerScreen.LayerState(errorTitle = "Could not open", errorMessage = "Try another source", retry = true, sources = true,
                nextTitle = "Pilot", nextDetail = "Season 1 · Playing in 10 seconds"))
            val root = activity.findViewById<View>(R.id.player_root)
            val error = activity.findViewById<View>(R.id.error); error.visibility = View.VISIBLE
            settle(root)
            assertTrue(layers.request(R.id.error_retry)); assertEquals(R.id.error_retry, layers.focusedId())
            composition.onNodeWithTag(activity.resources.getResourceName(R.id.error_title)).assertTextEquals("Could not open")
            composition.onNodeWithTag(activity.resources.getResourceName(R.id.error_retry)).performTouchInput { click() }
            composition.onNodeWithTag(activity.resources.getResourceName(R.id.error_sources)).performTouchInput { click() }
            error.visibility = View.GONE; activity.findViewById<View>(R.id.up_next).visibility = View.VISIBLE; settle(root)
            composition.onNodeWithTag(activity.resources.getResourceName(R.id.up_next_title)).assertTextEquals("Pilot")
            assertTrue(layers.request(R.id.up_next_cancel)); assertEquals(R.id.up_next_cancel, layers.focusedId())
            composition.onNodeWithTag(activity.resources.getResourceName(R.id.up_next_play)).performTouchInput { click() }
            composition.onNodeWithTag(activity.resources.getResourceName(R.id.up_next_cancel)).performTouchInput { click() }
            activity.findViewById<View>(R.id.up_next).visibility = View.GONE
            activity.findViewById<View>(R.id.next_episode_offer).visibility = View.VISIBLE; settle(root)
            composition.onNodeWithTag(activity.resources.getResourceName(R.id.next_episode_play)).performTouchInput { click() }
            assertEquals(listOf("retry", "sources", "play", "cancel", "offer"), chosen)
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
