package tv.stream.app

import android.view.KeyEvent
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

/** Real lifecycle/focus/click paths through the retained screen state and Compose/native boundary. */
class ScreenStateInteractionTest : AppHarness() {
    @Test fun settingsRecreationKeepsCategoryFocusAndBindsActionsToTheNewActivity() {
        launch()
        waitUntil("Home", 10_000) { visible("Make yourself at home") }
        browse!!.onActivity { it.root("Settings") }
        tap("Playback", false)
        waitUntil("Playback settings", 5_000) { visible("Play next episode") }
        browse!!.onActivity { activity ->
            assertNotNull("autoplay row", focusKey(activity, "autoplay"))
        }
        browse!!.recreate()
        waitUntil("Playback category and focused action restored", 10_000) {
            visible("Playback cache") && focused("autoplay")
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("remote toggles playback preference", 5_000) { services.store.preference("autoplay", "true") == "false" }
        waitUntil("updated row keeps focus", 5_000) { focused("autoplay") }
        tap("Play next episode", false)
        waitUntil("touch toggles playback preference back", 5_000) { services.store.preference("autoplay", "true") == "true" }
    }

    @Test fun detailsRecreationRestoresItsActionsAndBackPopsOneScreen() {
        readyLibrary()
        launch()
        waitUntil("Home", 10_000) { visible("Generated Film") }
        tap("Generated Film", false)
        waitUntil("Details", 10_000) { visible("Play") && visible("Sources") }
        browse!!.onActivity { activity ->
            assertTrue(activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("sources").requestFocus())
        }
        browse!!.recreate()
        waitUntil("Details actions restored", 10_000) { visible("Play") && visible("Sources") }
        try {
            waitUntil("Details Sources focus restored", 10_000) { focused("sources") }
        } catch (failure: AssertionError) {
            browse!!.onActivity { assertEquals("Restored Details focus", "sources", it.currentFocus?.tag) }
            throw failure
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("Sources opens from the recreated action", 10_000) { visible("Local torrent") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitUntil("Back restores exactly one Details screen", 10_000) { visible("Play") && visible("Sources") }
    }

    private fun focused(tag: String): Boolean {
        val result = AtomicBoolean()
        browse!!.onActivity { result.set(it.currentFocus?.tag == tag) }
        return result.get()
    }
}
