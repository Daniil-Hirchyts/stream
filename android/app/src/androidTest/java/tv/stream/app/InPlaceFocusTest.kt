package tv.stream.app

import org.junit.Assert.*
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier
import org.json.JSONObject
import org.junit.Test
import tv.stream.app.addon.Cancellation
import tv.stream.app.addon.Title

/** Record every focus change, not just the final restored key after an in-place update. */
class InPlaceFocusTest : AppHarness() {
    @Test @Throws(Exception::class) fun favouriteAndWatchedButtonsKeepTheSameFocusedViewForRemoteAndPointer() {
        launch(); waitUntil("Home", 10000) { visible("Make yourself at home") }
        val film = Title.parse("local", JSONObject().put("id", "focus-film").put("type", "movie").put("name", "Focus film"))!!
        browse!!.onActivity { activity -> activity.openTitle(film, "") }
        waitUntil("film", 5000) { visible("Add favourite") }
        toggle("favourite", null) { visible("Remove favourite") }
        toggle("favourite", "Remove favourite") { visible("Add favourite") }
        assertTrue(services.store.library(true).isEmpty())
        toggle("watched", null) { visible("Mark unwatched") }
        toggle("watched", "Mark unwatched") { visible("Mark watched") }
        assertFalse(services.store.progress(film.key(), film.id)!!.watched)
    }

    @Test @Throws(Exception::class) fun settingsTogglesKeepTheirPaneAndFocusedRowAttached() {
        launch(); waitUntil("Home", 10000) { visible("Make yourself at home") }
        tap("Settings", true); tap("Playback", true)
        toggle("interface-sounds", null) { services.store.preference("interfaceSounds", "true") == "false" }
        toggle("interface-sounds", "Interface sounds") { services.store.preference("interfaceSounds", "false") == "true" }
        toggle("autoplay", null) { services.store.preference("autoplay", "true") == "false" }
        toggle("autoplay", "Play next episode") { services.store.preference("autoplay", "false") == "true" }
        assertTrue("Still in Playback", visible("Playback cache"))
    }

    @Test @Throws(Exception::class) fun addonEnableAndCatalogueVisibilityDoNotHandFocusToTheirFirstRow() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            val id = services.store.addons()[0].id
            launch(); waitUntil("Home", 10000) { visible("Film 00") }
            browse!!.onActivity { activity -> val route = Route("Add-on"); route.value = id; activity.push(route) }
            waitUntil("add-on", 5000) { visible("Disable add-on") }
            toggle("enable", null) { !services.store.addons()[0].enabled }
            toggle("enable", "Enable add-on") { services.store.addons()[0].enabled }
            toggle("visibility:moviebrowse", null) { !services.store.catalogVisible(id, "movie", "browse") }
            toggle("visibility:moviebrowse", null) { services.store.catalogVisible(id, "movie", "browse") }
        }
    }

    private fun toggle(key: String, pointerLabel: String?, changed: BooleanSupplier) {
        val original = AtomicReference<View>()
        val observer = AtomicReference<ViewTreeObserver.OnGlobalFocusChangeListener>()
        val changes: MutableList<String> = ArrayList()
        browse!!.onActivity { activity ->
            val control = activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>(key)
            assertNotNull("Control $key", control); control.requestFocus(); original.set(control)
        }
        SystemClock.sleep(300)
        browse!!.onActivity { activity ->
            observer.set(ViewTreeObserver.OnGlobalFocusChangeListener { before, after -> if (after !== original.get()) changes.add(if (after == null) "none" else (after.tag as Any?).toString()) })
            activity.findViewById<View>(R.id.page_rows).viewTreeObserver.addOnGlobalFocusChangeListener(observer.get())
        }
        if (pointerLabel == null) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        else tap(pointerLabel, true)
        waitUntil("toggle saved: $key", 5000, changed)
        SystemClock.sleep(400) // Include both progressive/final renders and delayed restoration callbacks.
        browse!!.onActivity { activity ->
            val rows = activity.findViewById<View>(R.id.page_rows)
            rows.viewTreeObserver.removeOnGlobalFocusChangeListener(observer.get())
            assertSame("Focusable surface survives: $key", original.get(), rows.findViewWithTag<View>(key))
            assertSame("Focus stays on $key", original.get(), activity.currentFocus)
            assertTrue("No intermediate focus jumps: $changes", changes.isEmpty())
        }
    }
}
