package tv.stream.app

import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class NavigationChromeTest : AppHarness() {
    private val backPosition = Rect(); private val refreshPosition = Rect()

    private fun assertIcon(view: View) {
        assertTrue(view.isShown)
        assertEquals("Utility controls stay circular", view.height.toLong(), view.width.toLong())
        assertEquals("No text is added to Back or Refresh", 1, (view as ViewGroup).childCount.toLong())
        assertTrue(view.getChildAt(0) is ImageView)
        assertNull("No hover text", view.tooltipText)
    }
    private fun fixedIcon(view: View, position: Rect) {
        assertIcon(view)
        val bounds = Rect(); (view as ViewGroup).getChildAt(0).getGlobalVisibleRect(bounds)
        // Compare centres, independent of focus growth and Refresh's rotating glyph.
        val center = Rect(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY())
        if (position.left == 0) position.set(center)
        else { assertEquals(position.left.toFloat(), center.left.toFloat(), 1f); assertEquals(position.top.toFloat(), center.top.toFloat(), 1f) }
    }
    private fun assertBack() { browse!!.onActivity { a -> fixedIcon(a.findViewById(R.id.page_back), backPosition) } }
    private fun assertRefresh(label: String) {
        browse!!.onActivity { a ->
            val refresh = a.findViewById<View>(R.id.home_refresh); fixedIcon(refresh, refreshPosition)
            assertEquals(label, refresh.contentDescription)
            val button = Rect(); val clock = Rect(); refresh.getGlobalVisibleRect(button); a.findViewById<View>(R.id.clock).getGlobalVisibleRect(clock)
            assertTrue("Refresh sits left of the top-right clock", button.right < clock.left)
            assertTrue(button.centerX() > a.resources.displayMetrics.widthPixels * .7)
        }
    }
    @Test @Throws(Exception::class) fun browseIsAPrimaryDestinationAndBackRemainsIconOnlyOnEverySubpage() {
        readyLibrary(); launch(); waitUntil("Home", 10000) { visible("Generated Film") }
        tap("Browse", true); waitUntil("Browse page", 5000) { visible("Generated films") }
        browse!!.onActivity { a ->
            assertFalse("Primary Browse has no Back button", a.findViewById<View>(R.id.page_back).isShown)
            assertTrue(a.findViewById<View>(R.id.navigation).findViewWithTag<View>("nav:Browse").isActivated)
        }
        tap("Generated films", false); waitUntil("Catalogue", 10000) { visible("Generated Film") }; assertBack()
        browse!!.onActivity { a -> assertNotNull(focusKey(a, "filter:genre:")) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        browse!!.onActivity { a -> assertEquals("Up reaches the separate Back control", R.id.page_back.toLong(), a.currentFocus!!.id.toLong()) }
        tap("Back", false); waitUntil("Browse restored", 5000) { visible("Generated films") }
        browse!!.onActivity { a -> a.root("Home") }; tap("Generated Film", true); waitUntil("Details", 5000) { visible("Play") }; assertBack()
        tap("Sources", false); waitUntil("Sources", 10000) { visible("Size") }; assertBack()
        // Sources and Details deliberately hide navigation; Back returns to the tab rail.
        tap("Back", false); tap("Back", false)
        tap("Settings", true); tap("Add-on store", true); waitUntil("Store", 10000) { visible("All") }; assertBack()
        browse!!.onActivity { a -> a.openImports() }; waitUntil("Imports", 5000) { visible("Add magnet or torrent URL") }; assertBack()
    }
    @Test @Throws(Exception::class) fun topRightRefreshKeepsItsPositionAndRemoteFocusAcrossPages() {
        readyLibrary(); launch(); waitUntil("Home", 10000) { visible("Generated Film") }; assertRefresh("Refresh")
        tap("Browse", false); tap("Generated films", false); waitUntil("Catalogue", 10000) { visible("Generated Film") }; assertRefresh("Refresh")
        browse!!.onActivity { a -> a.findViewById<View>(R.id.home_refresh).requestFocus() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("Refreshed catalogue", 10000) { visible("Generated Film") }
        browse!!.onActivity { a -> assertEquals(R.id.home_refresh.toLong(), a.currentFocus!!.id.toLong()) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        browse!!.onActivity { a -> assertNotEquals("Down restores content", R.id.home_refresh.toLong(), a.currentFocus!!.id.toLong()) }
        browse!!.onActivity { a -> a.root("Home") }; tap("Generated Film", true); waitUntil("Details", 5000) { visible("Play") }; assertRefresh("Refresh details")
        tap("Sources", false); waitUntil("Sources", 10000) { visible("Size") }; assertRefresh("Refresh sources")
        browse!!.onActivity { a -> a.root("Settings") }; tap("Add-on store", true); waitUntil("Store", 10000) { visible("All") }; assertRefresh("Refresh add-on lists")
        tap("Back", false); waitUntil("Settings", 5000) { visible("Add-ons") }
        browse!!.onActivity { a -> assertFalse(a.findViewById<View>(R.id.home_tools).isShown) }
    }
    @Test @Throws(Exception::class) fun toolbarLeftAndDownReturnToNavigationAndRememberedContent() {
        readyLibrary(); launch(); waitUntil("Home", 10000) { visible("Generated Film") }
        browse!!.onActivity { a -> a.findViewById<View>(R.id.home_refresh).requestFocus() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        browse!!.onActivity { a -> assertEquals("nav:Home", a.currentFocus!!.tag) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        browse!!.onActivity { a -> assertFalse(a.findViewById<View>(R.id.top_bar).hasFocus()) }
        tap("Generated Film", false); waitUntil("Details", 5000) { visible("Play") }
        browse!!.onActivity { a -> a.findViewById<View>(R.id.page_back).requestFocus() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        browse!!.onActivity { a -> assertEquals(R.id.home_refresh.toLong(), a.currentFocus!!.id.toLong()) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        browse!!.onActivity { a -> assertEquals(R.id.page_back.toLong(), a.currentFocus!!.id.toLong()) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        browse!!.onActivity { a -> assertEquals("play", a.currentFocus!!.tag) }
    }
    @Test @Throws(Exception::class) fun sourcesWaitForAnExplicitChoice() {
        readyLibrary(); launch(); tap("Generated Film", false); tap("Sources", true)
        waitUntil("Source selection", 10000) { visible("Size") && visible("Sources") }
        assertNull(watch()); chooseFirstSource()
        waitUntil("Chosen source renders", 60000) { state() != null && state()!!.renderedFirstFrame && state()!!.positionMs > 1000 }
        assertFalse(watch()!!.intent.getStringExtra("source")!!.isEmpty())
        instrumentation.runOnMainSync(watch()!!::finish)
    }
}
