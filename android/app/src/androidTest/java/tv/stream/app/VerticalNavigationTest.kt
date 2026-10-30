package tv.stream.app

import org.junit.Assert.*
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import tv.stream.app.addon.Title
import tv.stream.app.ui.NavigationRail
import tv.stream.app.ui.Tv

/** Real input and painted-frame checks: navigation overlays a fixed page; visibility follows page composition. */
class VerticalNavigationTest : AppHarness() {
    @Test @Throws(Exception::class) fun railMorphsThroughHiddenWrappedAndExpandedStates() {
        readyLibrary(); launch(); AppHarness.waitUntil("Home", 10000) { visible("Generated Film") }
        val card = AtomicReference<View>()
        browse!!.onActivity { a -> card.set(a.currentFocus) }
        collapsed("01-home")
        val paintedMorph = AtomicBoolean(); val movedPage = AtomicBoolean()
        val observer = AtomicReference<android.view.ViewTreeObserver.OnDrawListener>()
        browse!!.onActivity { a ->
            val rail = a.findViewById<View>(R.id.top_bar)
            val content = a.findViewById<View>(R.id.browse_content)
            val viewport = bounds(content); val poster = bounds(card.get())
            observer.set(android.view.ViewTreeObserver.OnDrawListener {
                if (rail.width > dp(NavigationRail.COLLAPSED) && rail.width < dp(NavigationRail.EXPANDED)) paintedMorph.set(true)
                if (viewport != bounds(content) || poster != bounds(card.get())) movedPage.set(true)
            })
            rail.viewTreeObserver.addOnDrawListener(observer.get())
        }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        wrappedFocus("nav:Home", "01-home-rail-focused")
        assertFalse("Moving focus onto navigation never starts the width morph", paintedMorph.get())
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Home", "02-expanded")
        assertTrue("The morph paints intermediate widths", paintedMorph.get())
        assertFalse("No painted frame moves or resizes the page or its poster", movedPage.get())
        browse!!.onActivity { a ->
            val rail = a.findViewById<ViewGroup>(R.id.top_bar)
            assertTrue("Compact expansion avoids the former 188 dp empty tail", rail.width < dp(188))
            assertEquals("Navigation is vertically centred", (a.findViewById<View>(R.id.browse_root).height / 2).toFloat(),
                    ((a.findViewById<View>(R.id.top_bar_controls).top + a.findViewById<View>(R.id.top_bar_controls).bottom) / 2 + rail.top).toFloat(), 2f)
            assertCaptions(rail)
            val content = a.findViewById<View>(R.id.browse_content)
            assertEquals("Content keeps its wrapped inset", dp(NavigationRail.CONTENT_START).toLong(), a.contentStart().toLong())
            assertEquals("The page runs under the rail to the screen edge", 0, content.left.toLong())
            assertEquals("Shelves fade out under the rail", dp(NavigationRail.CONTENT_START).toLong(), content.horizontalFadingEdgeLength.toLong())
            assertTrue(a.findViewById<View>(R.id.navigation_dismiss).isShown)
        }
        // The frame observer only covers this first open/close, not subsequent page navigation.
        key(KeyEvent.KEYCODE_DPAD_RIGHT); collapsed("03-returned")
        browse!!.onActivity { a -> a.findViewById<View>(R.id.top_bar).viewTreeObserver.removeOnDrawListener(observer.get()) }
        assertFalse("Closing the popup leaves the page still", movedPage.get())
        browse!!.onActivity { a -> assertSame("Right restores the same attached card", card.get(), a.currentFocus) }
        // Rapid direction changes reverse an in-flight morph cleanly.
        key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_LEFT)
        expanded("nav:Home", "04-reversed")
        key(KeyEvent.KEYCODE_DPAD_DOWN); assertFocus("nav:Browse")
        key(KeyEvent.KEYCODE_DPAD_CENTER); AppHarness.waitUntil("Primary Browse", 5000) { visible("Generated films") }
        browse!!.onActivity { a -> assertFalse("Browse has no Back button", a.findViewById<View>(R.id.page_back).isShown) }
        key(KeyEvent.KEYCODE_DPAD_RIGHT); collapsed("04-browse-wrapped")
        tap("Generated films", false); AppHarness.waitUntil("Catalogue", 5000) { visible("Generated Film") }
        AppHarness.waitUntil("Catalogue keeps wrapped navigation", 5000) { railState() == NavigationRail.State.WRAPPED }
        shot("04-catalogue-wrapped")
        val catalogueFocus = AtomicReference<View>()
        browse!!.onActivity { a -> catalogueFocus.set(a.currentFocus) }
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Browse", "04-catalogue-expanded")
        key(KeyEvent.KEYCODE_BACK); collapsed("04-catalogue-wrapped-returned")
        browse!!.onActivity { a -> assertSame("Back dismisses navigation and restores the exact catalogue control", catalogueFocus.get(), a.currentFocus) }
        browse!!.onActivity { a -> assertNotNull(focusKey(a, "filter:genre:")) }
        key(KeyEvent.KEYCODE_DPAD_UP)
        browse!!.onActivity { a -> assertEquals("Up reaches Back, independent of rail state", R.id.page_back.toLong(), a.currentFocus!!.id.toLong()) }

    }

    @Test @Throws(Exception::class) fun railRowsGrowUnderFocusAndThePointerLikeOtherControls() {
        readyLibrary(); launch(); AppHarness.waitUntil("Home", 10000) { visible("Generated Film") }
        val focus = AtomicReference<Any>()
        browse!!.onActivity { a -> focus.set(if (a.currentFocus == null) null else a.currentFocus!!.tag) }
        val library = Rect()
        browse!!.onActivity { a -> assertTrue(a.findViewById<View>(R.id.top_bar).findViewWithTag<View>("nav:Library").getGlobalVisibleRect(library)) }
        hover(library.centerX().toFloat(), library.centerY().toFloat())
        AppHarness.waitUntil("the row under the pointer grows like a focused round control", 2000) {
            hovered("nav:Library") && Math.abs(rowScale("nav:Library") - NavigationRail.LIFT_WRAPPED) < .01f }
        shot("60-rail-hover")
        browse!!.onActivity { a -> assertEquals("Hover never moves focus", focus.get(), if (a.currentFocus == null) null else a.currentFocus!!.tag) }
        assertEquals("Hover never unwraps the rail", NavigationRail.State.WRAPPED, railState())
        hover(library.centerX().toFloat(), library.top - dp(6) - library.height() / 2f)
        AppHarness.waitUntil("the highlight follows the pointer", 2000) { hovered("nav:Browse") && !hovered("nav:Library")
                && Math.abs(rowScale("nav:Library") - 1f) < .01f }
        hover(dp(700).toFloat(), dp(300).toFloat())
        AppHarness.waitUntil("the rail settles when the pointer leaves", 2000) { !hovered("nav:Browse") && Math.abs(rowScale("nav:Browse") - 1f) < .01f }
        key(KeyEvent.KEYCODE_DPAD_LEFT); wrappedFocus("nav:Home", "61-rail-focus-wrapped")
        AppHarness.waitUntil("focus grows the wrapped circle", 2000) { Math.abs(rowScale("nav:Home") - NavigationRail.LIFT_WRAPPED) < .01f }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        AppHarness.waitUntil("focus travels: the next row grows, the last settles", 2000) { Math.abs(rowScale("nav:Browse") - NavigationRail.LIFT_WRAPPED) < .01f
                && Math.abs(rowScale("nav:Home") - 1f) < .01f }
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Browse", "62-rail-focus-expanded")
        AppHarness.waitUntil("labelled rows stay at the popup's even inset", 2000) { Math.abs(rowScale("nav:Browse") - 1f) < .01f }
        browse!!.onActivity { a ->
            val row = Rect(); val popup = Rect()
            val focused = a.findViewById<View>(R.id.top_bar).findViewWithTag<View>("nav:Browse")
            focused.getGlobalVisibleRect(row); a.findViewById<View>(R.id.top_bar_controls).getGlobalVisibleRect(popup)
            val grownWidth = focused.width * focused.scaleX
            assertTrue("The grown row stays inside the popup", grownWidth <= popup.width())
        }
    }
    private fun hover(x: Float, y: Float) {
        val p = android.view.MotionEvent.PointerProperties(); p.id = 0; p.toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE
        val c = android.view.MotionEvent.PointerCoords(); c.x = x; c.y = y
        val time = SystemClock.uptimeMillis()
        val e = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_HOVER_MOVE, 1, arrayOf(p),
                arrayOf(c), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
        assertTrue(instrumentation.uiAutomation.injectInputEvent(e, true)); e.recycle()
        instrumentation.waitForIdleSync()
    }
    private fun hovered(tag: String): Boolean {
        val yes = AtomicBoolean(); browse!!.onActivity { a -> yes.set(a.findViewById<View>(R.id.top_bar).findViewWithTag<View>(tag).isHovered) }; return yes.get()
    }
    private fun rowScale(tag: String): Float {
        val scale = AtomicReference(0f)
        browse!!.onActivity { a -> scale.set(a.findViewById<View>(R.id.top_bar).findViewWithTag<View>(tag).scaleX) }; return scale.get()
    }
    @Test @Throws(Exception::class) fun compactNavigationGrowsForLargerLabelsWithoutShiftingThePage() {
        readyLibrary(); launch(); AppHarness.waitUntil("Home", 10000) { visible("Generated Film") }
        val content = AtomicReference<Rect>()
        browse!!.onActivity { a ->
            content.set(bounds(a.findViewById(R.id.browse_content)))
            val rows = a.findViewById<ViewGroup>(R.id.tab_cluster)
            enlargeLabels(rows)
        }
        key(KeyEvent.KEYCODE_DPAD_LEFT); wrappedFocus("nav:Home", "56-larger-labels-wrapped")
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        AppHarness.waitUntil("Larger text fits expanded rail", 5000) {
            val ready = AtomicBoolean()
            browse!!.onActivity { a ->
                val rail = a.findViewById<NavigationRail>(R.id.top_bar)
                // The width reaches whole pixels a frame before the labels finish fading in.
                ready.set(rail.state() == NavigationRail.State.EXPANDED && rail.width == dp(rail.expandedWidth()) && labelsShown(rail))
            }
            ready.get()
        }
        browse!!.onActivity { a ->
            val rail = a.findViewById<NavigationRail>(R.id.top_bar)
            assertTrue("Extra width is earned by the larger labels", rail.expandedWidth() > NavigationRail.EXPANDED)
            assertCaptions(rail)
            assertEquals("Larger navigation still overlays the same content", content.get(), bounds(a.findViewById(R.id.browse_content)))
        }
        key(KeyEvent.KEYCODE_DPAD_RIGHT); collapsed("57-larger-labels-returned")
    }

    @Test @Throws(Exception::class) fun hiddenPagesKeepRemoteAndPointerNavigationInsideContent() {
        readyLibrary(); launch(); AppHarness.waitUntil("Home", 10000) { visible("Generated Film") }
        tap("Generated Film", true); AppHarness.waitUntil("Details", 10000) { visible("Play") }
        browse!!.onActivity { a -> a.findViewById<View>(R.id.page_rows).findViewWithTag<View>("play").requestFocus() }
        AppHarness.waitUntil("Details Play focused", 5000) { focused("play") }
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertFocus("play"); assertNavigationHidden()
        key(KeyEvent.KEYCODE_DPAD_RIGHT); assertFocus("sources")
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertFocus("play")
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER)
        AppHarness.waitUntil("Sources page", 10000) { visible("Size") }
        assertNavigationHidden()
        browse!!.onActivity { a -> a.findViewById<View>(R.id.page_rows).findViewWithTag<View>("sort:BEST").requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertFocus("sort:BEST")
        key(KeyEvent.KEYCODE_DPAD_RIGHT); assertFocus("sort:QUALITY")
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertFocus("sort:BEST")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        AppHarness.waitUntil("Down reaches a source", 5000) {
            val source = AtomicBoolean()
            browse!!.onActivity { a -> source.set(a.currentFocus != null && a.currentFocus!!.tag.toString().startsWith("source:")) }
            source.get()
        }
        key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertNavigationHidden()
        hover(dp(2).toFloat(), dp(300).toFloat()); assertNavigationHidden()
        browse!!.onActivity { a -> assertNotNull("Sources retains a focused control", a.currentFocus) }
        key(KeyEvent.KEYCODE_BACK); AppHarness.waitUntil("Back returns to Details", 10000) { visible("Play") }
        assertNavigationHidden()
        key(KeyEvent.KEYCODE_BACK); AppHarness.waitUntil("Back returns to Home", 10000) { railState() == NavigationRail.State.WRAPPED && visible("Generated Film") }
        key(KeyEvent.KEYCODE_DPAD_LEFT); wrappedFocus("nav:Home", "hidden-pages-return-to-home")
    }

    private fun assertNavigationHidden() {
        browse!!.onActivity { a ->
            assertEquals(NavigationRail.State.HIDDEN, (a.findViewById<View>(R.id.top_bar) as NavigationRail).state())
            assertFalse(a.findViewById<View>(R.id.top_bar).isShown)
            assertFalse(a.findViewById<View>(R.id.navigation_reveal).isShown)
            assertFalse(a.findViewById<View>(R.id.navigation_dismiss).isShown)
            assertFalse(a.findViewById<View>(R.id.top_bar).hasFocus())
        }
    }

    private fun enlargeLabels(view: View) {
        if (view is TextView) view.textSize = 22f
        if (view is ViewGroup) for (i in 0 until view.childCount) enlargeLabels(view.getChildAt(i))
    }

    @Test @Throws(Exception::class) fun wrappedFocusAndHeldLeftNeverUnwrapUntilASeparateLeftPress() {
        readyLibrary(); launch(); AppHarness.waitUntil("Home", 10000) { visible("Generated Film") }
        // Focus can arrive from a cursor/pointer as well as from the remote.
        browse!!.onActivity { a -> a.findViewById<View>(R.id.navigation).findViewWithTag<View>("nav:Home").requestFocus() }
        wrappedFocus("nav:Home", "47-wrapped-pointer-focus")
        key(KeyEvent.KEYCODE_DPAD_UP); wrappedFocus("nav:search", "48-wrapped-search-focus")
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN)
        wrappedFocus("nav:Browse", "49-wrapped-browse-focus")
        browse!!.onActivity { a ->
            val now = SystemClock.uptimeMillis()
            a.dispatchKeyEvent(KeyEvent(now - 500, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 2))
            a.dispatchKeyEvent(KeyEvent(now - 500, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, 0))
        }
        wrappedFocus("nav:Browse", "50-wrapped-held-left")
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Browse", "51-second-left-expanded")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        AppHarness.waitUntil("Right restores the film", 5000) { railState() == NavigationRail.State.WRAPPED && !focused("nav:Browse") }
        key(KeyEvent.KEYCODE_DPAD_LEFT); wrappedFocus("nav:Home", "52-next-entry-wrapped")
        key(KeyEvent.KEYCODE_BACK)
        AppHarness.waitUntil("Back from wrapped navigation restores content", 5000) { !focused("nav:Home") && visible("Generated Film") }
        tap("Generated Film", false); AppHarness.waitUntil("Details", 5000) { visible("Play") }
        key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertFocus("play"); assertNavigationHidden()
    }

    @Test @Throws(Exception::class) fun settingsSearchAndDialogsRespectTheirOwnFocusBeforeEnteringRail() {
        readyLibrary(); launch(); AppHarness.waitUntil("Home", 10000) { visible("Generated Film") }
        tap("Settings", true)
        AppHarness.waitUntil("Settings category", 5000) { focused("settings:addons") }; collapsed("05-settings")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        browse!!.onActivity { a -> assertFalse("Right enters Settings pane", "settings:addons" == a.currentFocus!!.tag) }
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertFocus("settings:addons")
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Settings", "06-settings-rail")
        key(KeyEvent.KEYCODE_DPAD_RIGHT); assertFocus("settings:addons")
        tap("Subtitles", false); tap("Subtitle color", true)
        AppHarness.waitUntil("Subtitle popup", 5000) { visible("White") }; shot("07-popup")
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        AppHarness.waitUntil("Popup dismisses before rail navigation", 5000) { visible("Subtitle color") }
        browse!!.onActivity { a -> assertFalse(a.findViewById<View>(R.id.top_bar).hasFocus()) }
        tap("Search", true); AppHarness.waitUntil("Keyboard", 5000) { visible("Type a") }; collapsed("08-search")
        browse!!.onActivity { a -> a.findViewById<View>(R.id.page_rows).findViewWithTag<View>("keyboard:a").requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertFocus("keyboard:a")
        browse!!.onActivity { a ->
            assertEquals(NavigationRail.State.HIDDEN, (a.findViewById<View>(R.id.top_bar) as NavigationRail).state())
            assertFalse(a.findViewById<View>(R.id.top_bar).isShown)
            assertFalse(a.findViewById<View>(R.id.navigation_reveal).isShown)
        }
        shot("09-search-no-rail")
        browse!!.onActivity { a -> a.searchInput("Generated") }
        AppHarness.waitUntil("Search results", 10000) { visible("Generated Film") }; collapsed("10-search-results")
        browse!!.onActivity { a -> a.findViewById<View>(R.id.page_rows).findViewWithTag<View>("keyboard:f").requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        browse!!.onActivity { a -> assertFalse("Right from keyboard reaches results", a.currentFocus!!.tag.toString().startsWith("keyboard:")) }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        browse!!.onActivity { a -> assertFalse("Results first return to the keyboard", a.findViewById<View>(R.id.top_bar).hasFocus()) }
    }

    @Test @Throws(Exception::class) fun backDismissesNavigationBeforeLeavingItsPageAndNeverTrapsTheViewer() {
        readyLibrary(); launch(); AppHarness.waitUntil("Home", 10000) { visible("Generated Film") }
        tap("Generated Film", false); AppHarness.waitUntil("Details", 5000) { visible("Play") }
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertNavigationHidden()
        key(KeyEvent.KEYCODE_BACK)
        AppHarness.waitUntil("Back restores Home from Details", 5000) { visible("Generated Film") && !visible("Play") }
        tap("Settings", false); AppHarness.waitUntil("Settings", 5000) { visible("Add-ons") }
        key(KeyEvent.KEYCODE_BACK)
        AppHarness.waitUntil("Root-page Back returns Home", 5000) { visible("Generated Film") && !visible("Add-ons") }
        val owner = AtomicReference<MainActivity>(); browse!!.onActivity { owner.set(it) }
        key(KeyEvent.KEYCODE_BACK)
        AppHarness.waitUntil("Back from Home exits", 5000) { owner.get().isFinishing || owner.get().isDestroyed }
    }

    @Test @Throws(Exception::class) fun scrollingKeepsWrappedNavigationAndDetailsKeepsItHidden() {
        readyLibrary(); launch(); AppHarness.waitUntil("Home", 10000) { visible("Generated Film") }; collapsed("40-home-wrapped")
        browse!!.onActivity { a -> (a.findViewById<View>(R.id.page_rows) as androidx.recyclerview.widget.RecyclerView).scrollBy(0, dp(280)) }
        AppHarness.waitUntil("Scrolling preserves wrapped navigation", 5000) { railState() == NavigationRail.State.WRAPPED }
        SystemClock.sleep(300); shot("41-scrolled-wrapped")
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Home", "42-scrolled-overlay")
        key(KeyEvent.KEYCODE_BACK)
        browse!!.onActivity { a -> a.root("Home") }
        tap("Generated Film", true); AppHarness.waitUntil("Details", 5000) { visible("Play") }
        AppHarness.waitUntil("Details uses hidden navigation", 5000) { railState() == NavigationRail.State.HIDDEN }
        hover(dp(2).toFloat(), dp(300).toFloat()); assertNavigationHidden()
        browse!!.onActivity { a ->
            val search = a.findViewById<ViewGroup>(R.id.search_button)
            assertEquals("Search always has its icon and label", 2, search.childCount.toLong())
            assertEquals("Search label is present", "Search", (search.getChildAt(1) as TextView).text.toString())
            val back = a.findViewById<ViewGroup>(R.id.page_back); val refresh = a.findViewById<ViewGroup>(R.id.home_refresh)
            assertEquals(1, back.childCount.toLong()); assertEquals(1, refresh.childCount.toLong())
        }
    }

    @Test @Throws(Exception::class) fun outsideClicksDismissNavigationBeforeActivatingCardsOrActions() {
        readyLibrary(); launch(); AppHarness.waitUntil("Home", 10000) { visible("Generated Film") }
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Home", "43-expanded-before-card")
        tap("Close navigation", false)
        AppHarness.waitUntil("Outside tap wraps navigation", 5000) { railState() == NavigationRail.State.WRAPPED }
        assertFalse("The outside tap never opens the card beneath", visible("Play"))
        tap("Generated Film", false); AppHarness.waitUntil("Card click opens Details", 5000) { visible("Play") }
        tap("Add favourite", false); AppHarness.waitUntil("Saved film actions", 5000) { visible("More") }
        val before = AtomicReference<Rect>()
        browse!!.onActivity { a -> before.set(bounds(a.findViewById(R.id.browse_content))) }
        key(KeyEvent.KEYCODE_DPAD_LEFT); assertNavigationHidden()
        browse!!.onActivity { a -> assertEquals("Hidden navigation leaves content unchanged", before.get(), bounds(a.findViewById(R.id.browse_content))) }
        assertFalse("More is not opened by Left", visible("Remove from library"))
        tap("More", true); AppHarness.waitUntil("Small action opens its menu", 5000) { visible("Remove from library") }; shot("45-menu-after-expansion")
        assertEquals("Details navigation stays hidden behind its action menu", NavigationRail.State.HIDDEN, railState())
        key(KeyEvent.KEYCODE_BACK)
        AppHarness.waitUntil("Popup closes", 5000) { val focus = AtomicBoolean(); browse!!.onActivity { a -> focus.set(a.hasWindowFocus()) }; focus.get() }
        AppHarness.waitUntil("Details returns to hidden navigation", 5000) { railState() == NavigationRail.State.HIDDEN }
    }

    @Test @Throws(Exception::class) fun collapsedViewportCoversBrowseSubpagesAndEpisodeMenus() {
        readyLibrary(); launch(); AppHarness.waitUntil("Home", 10000) { visible("Generated Film") }
        tap("Browse", false); AppHarness.waitUntil("Catalogues", 5000) { visible("Generated films") }; collapsed("12-catalogues")
        tap("Generated films", true); AppHarness.waitUntil("Catalogue", 10000) { visible("Generated Film") }; collapsed("13-catalogue")
        tap("Generated Film", false); AppHarness.waitUntil("Details", 10000) { visible("Play") }; collapsed("14-details")
        tap("Add favourite", false); AppHarness.waitUntil("Saved film actions", 5000) { visible("More") }
        tap("More", true); AppHarness.waitUntil("Details menu", 5000) { visible("Remove from library") }; shot("15-details-menu")
        key(KeyEvent.KEYCODE_BACK)
        tap("Sources", true); AppHarness.waitUntil("Sources", 10000) { visible("Size") }; collapsed("16-sources")
        browse!!.onActivity { a -> a.root("Library") }; AppHarness.waitUntil("Library", 5000) { visible("Generated Film") }; collapsed("17-library")
        browse!!.onActivity { a -> a.root("Settings") }; tap("Add-on store", false)
        AppHarness.waitUntil("Store", 10000) { visible("All") }; collapsed("18-store")
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Settings", "18-store-rail")
        browse!!.onActivity { a -> assertCaptions(a.findViewById(R.id.top_bar)) }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        browse!!.onActivity { a -> a.openImports() }; AppHarness.waitUntil("Imports", 5000) { visible("Add magnet or torrent URL") }; collapsed("19-imports")
        tap("Add magnet or torrent URL", true); AppHarness.waitUntil("Import dialog", 5000) { visible("Continue") }; shot("20-import-dialog"); key(KeyEvent.KEYCODE_BACK)
        val episodes = JSONArray()
        for (i in 1..4) episodes.put(JSONObject().put("id", "rail:episode:$i").put("season", 1).put("episode", i).put("title", "Episode $i"))
        val series = Title.parse("local", JSONObject().put("id", "rail:series").put("type", "series").put("name", "Rail series").put("videos", episodes))!!
        browse!!.onActivity { a -> a.openTitle(series, "") }; AppHarness.waitUntil("Series", 5000) { visible("Play") }; collapsed("21-series")
        key(KeyEvent.KEYCODE_DPAD_DOWN); AppHarness.waitUntil("Episodes", 5000) { visible("Season 1") && !visible("Play") }; collapsed("22-episodes")
        tap("Episode 1", false); AppHarness.waitUntil("Episode menu", 5000) { visible("Choose source") }; shot("23-episode-menu")
        key(KeyEvent.KEYCODE_BACK)
        AppHarness.waitUntil("Episode popup closed", 5000) { val focus = AtomicBoolean(); browse!!.onActivity { a -> focus.set(a.hasWindowFocus()) }; focus.get() }
        key(KeyEvent.KEYCODE_BACK); AppHarness.waitUntil("Series overview", 5000) { visible("Play") }
    }

    @Test @Throws(Exception::class) fun emptySecondaryAndFailurePagesAndClockFormatsFitTheRail() {
        launch(); AppHarness.waitUntil("Empty Home", 10000) { visible("Make yourself at home") }; collapsed("24-empty-home")
        browse!!.onActivity { a -> a.root("Library") }; AppHarness.waitUntil("Empty Library", 5000) { visible("Your library is empty") }; collapsed("25-empty-library")
        key(KeyEvent.KEYCODE_DPAD_LEFT); expanded("nav:Library", "26-empty-library-rail")
        key(KeyEvent.KEYCODE_DPAD_RIGHT); collapsed("27-empty-library-returned")
        readyLibrary(); browse!!.onActivity { a -> a.root("Settings") }; AppHarness.waitUntil("Settings", 5000) { visible("Local fixtures") }
        tap("Local fixtures", true); AppHarness.waitUntil("Add-on management", 5000) { visible("Remove add-on") }; collapsed("28-addon-management")
        browse!!.onActivity { a -> val review = Route("Add-on review"); review.value = origin + "/addon/good/review/manifest.json"; a.push(review) }
        AppHarness.waitUntil("Add-on review", 10000) { visible("Manage add-on") }; collapsed("29-addon-review")
        services.addons.remove(services.store.addons()[0].id)
        browse!!.onActivity { a -> a.refresh() }
        AppHarness.waitUntil("Uninstalled add-on review", 5000) { visible("Install") }; collapsed("29-uninstalled-review")
        readyLibrary()
        browse!!.onActivity { a -> a.root("Settings") }; tap("Languages", false); tap("Keyboard languages", true)
        AppHarness.waitUntil("Keyboard languages", 5000) { visible("English") }; collapsed("30-keyboard-languages")
        services.store.setPreference("developerMode", "true")
        browse!!.onActivity { a -> a.push(Route("Developer")) }; AppHarness.waitUntil("Developer", 5000) { visible("Open source notices") }; collapsed("31-developer")
        for (format in arrayOf("12", "24", "", "off")) {
            services.store.setPreference("clock", format); browse!!.onActivity { a -> a.refresh() }
            AppHarness.waitUntil("Clock format applied", 5000) {
                val applied = AtomicBoolean(); browse!!.onActivity { a ->
                    val clock = a.findViewById<android.widget.TextClock>(R.id.clock)
                    applied.set(if (format == "off") !clock.isShown else clock.isShown
                            && (if (format == "24") clock.format24Hour.toString() == "HH:mm" else clock.format12Hour.toString() == "h:mm a"))
                }; applied.get()
            }
            collapsed("32-clock-" + (if (format.isEmpty()) "automatic" else format))
        }
        for (addon in services.store.addons()) services.addons.remove(addon.id)
        services.addons.newRequest().use { request ->
            val url = origin + "/addon/broken/rail/manifest.json"; services.addons.install(url, services.addons.preview(url, request))
        }
        browse!!.onActivity { a -> a.root("Home") }; AppHarness.waitUntil("Home error", 10000) { visible("Unable to load Home") }; collapsed("33-home-error")
        browse!!.onActivity { a -> a.search() }; browse!!.onActivity { a -> a.searchInput("Generated") }
        AppHarness.waitUntil("Search error", 10000) { visible("Search unavailable") }; collapsed("34-search-error")
    }

    @Throws(Exception::class)
    private fun collapsed(name: String) {
        // Page actions clicked with a pointer can keep focus; enter the visible content before measuring.
        browse!!.onActivity { a ->
            if (a.findViewById<View>(R.id.top_bar).hasFocus()) a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        }
        AppHarness.waitUntil("Wrapped or hidden rail", 5000) { width(NavigationRail.COLLAPSED) && railState() != NavigationRail.State.EXPANDED }
        SystemClock.sleep(350)
        val edge = java.util.concurrent.atomic.AtomicInteger()
        browse!!.onActivity { a ->
            val rail = a.findViewById<View>(R.id.top_bar); val content = a.findViewById<View>(R.id.browse_content)
            val hidden = (rail as NavigationRail).state() == NavigationRail.State.HIDDEN
            assertEquals("Page composition sets its fixed inset", dp(if (hidden) NavigationRail.HIDDEN_CONTENT_START else NavigationRail.CONTENT_START).toLong(), a.contentStart().toLong())
            assertEquals("Only the rail's band fades", (if (hidden) 0 else dp(NavigationRail.CONTENT_START)).toLong(), content.horizontalFadingEdgeLength.toLong())
            val clock = a.findViewById<View>(R.id.clock); val bounds = Rect()
            if (clock.isShown) {
                assertTrue(clock.getGlobalVisibleRect(bounds)); assertEquals("Clock keeps the top-right safe inset", (a.resources.displayMetrics.widthPixels - dp(40)).toLong(), bounds.right.toLong())
                val text: android.text.Layout = (clock as TextView).layout
                for (line in 0 until text.lineCount) assertEquals("Clock never truncates", 0, text.getEllipsisCount(line).toLong())
            }
            if (rail.state() == NavigationRail.State.WRAPPED) {
                val rows = a.findViewById<ViewGroup>(R.id.tab_cluster)
                val search = a.findViewById<View>(R.id.search_button)
                assertEquals("Wrapped rows are circles", search.height.toLong(), search.width.toLong())
                val nav = a.findViewById<ViewGroup>(R.id.navigation)
                for (i in 0 until nav.childCount) assertEquals("Each destination is circular", nav.getChildAt(i).height.toLong(), nav.getChildAt(i).width.toLong())
            }
            assertControlsInside(content, if (hidden) 0 else rail.right)
            edge.set(if (hidden) 0 else rail.right)
        }
        assertKeyedControlsInside(edge.get())
        shot(name)
    }
    private fun assertControlsInside(view: View, edge: Int) {
        if (!view.isShown) return
        // A shelf scrolled along passes its earlier cards under the rail on purpose; its focused card still clears it.
        if (view is androidx.recyclerview.widget.RecyclerView && view.computeHorizontalScrollOffset() > 0) {
            val focused = view.findFocus()
            if (focused != null) assertControlsInside(focused, edge)
            return
        }
        // A composition owner spans its whole block; its declarative controls are measured by assertKeyedControlsInside.
        if (view.javaClass.name.endsWith("AndroidComposeView")) return
        if (view.isFocusable && view.tag is String) {
            val bounds = Rect()
            if (view.getGlobalVisibleRect(bounds)) assertTrue("Visible control clears rail: " + view.tag, bounds.left > edge)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) assertControlsInside(view.getChildAt(i), edge)
    }
    /** Declarative page controls, by their own accessibility bounds (queried off the main thread). */
    private fun assertKeyedControlsInside(edge: Int) {
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val id = node.viewIdResourceName
            if (id != null && node.isFocusable && !id.contains(":id/")) {
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                assertTrue("Visible control clears rail: $id", bounds.left > edge)
            }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        for (window in windows()) if (window.isFocused) visit(window.root)
    }
    private fun assertCaptions(view: View) {
        if (!view.isShown) return
        if (view is TextView && view !is android.widget.TextClock) {
            val text = view
            assertTrue("Expanded label is visible", text.alpha == 1f)
            assertNotNull(text.layout)
            assertEquals("Expanded caption fits: " + text.text, 0, text.layout.getEllipsisCount(0).toLong())
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) assertCaptions(view.getChildAt(i))
    }
    @Throws(Exception::class)
    private fun wrappedFocus(tag: String, name: String) {
        assertFocus(tag)
        AppHarness.waitUntil("Focused rail stays wrapped", 5000) { railState() == NavigationRail.State.WRAPPED && width(NavigationRail.COLLAPSED) }
        browse!!.onActivity { a -> assertFalse("Wrapped focus never covers the page with a dismissal target", a.findViewById<View>(R.id.navigation_dismiss).isShown) }
        shot(name)
    }
    @Throws(Exception::class)
    private fun expanded(tag: String, name: String) {
        assertFocus(tag)
        if (railState() == NavigationRail.State.WRAPPED) key(KeyEvent.KEYCODE_DPAD_LEFT)
        AppHarness.waitUntil("Expanded rail", 5000) {
            val shown = AtomicBoolean(); browse!!.onActivity { a -> shown.set(labelsShown(a.findViewById(R.id.top_bar))) }
            railState() == NavigationRail.State.EXPANDED && width(NavigationRail.EXPANDED) && shown.get()
        }
        shot(name)
    }
    private fun railState(): NavigationRail.State? { val state = AtomicReference<NavigationRail.State>(); browse!!.onActivity { a -> state.set((a.findViewById<View>(R.id.top_bar) as NavigationRail).state()) }; return state.get() }
    private fun width(dp: Int): Boolean { val yes = AtomicBoolean(); browse!!.onActivity { a -> yes.set(a.findViewById<View>(R.id.top_bar).width == dp(dp)) }; return yes.get() }
    private fun focused(tag: String): Boolean { val yes = AtomicBoolean(); browse!!.onActivity { a -> yes.set(a.currentFocus != null && tag == a.currentFocus!!.tag) }; return yes.get() }
    private fun assertFocus(tag: String) {
        try { AppHarness.waitUntil("Focus $tag", 5000) { focused(tag) } }
        catch (failure: AssertionError) {
            val actual = AtomicReference<String>()
            browse!!.onActivity { a ->
                val focus = a.currentFocus
                actual.set((if (focus == null) "none" else focus.javaClass.simpleName + " tag=" + focus.tag + " id=" + focus.id)
                        + " window=" + a.hasWindowFocus() + " touch=" + a.window.decorView.isInTouchMode)
            }
            throw AssertionError("Expected focus " + tag + ", actual " + actual.get(), failure)
        }
    }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); instrumentation.waitForIdleSync() }
    private fun dp(value: Int): Int { return Tv.dp(context, value.toFloat()) }
    @Throws(Exception::class)
    private fun shot(name: String) {
        SystemClock.sleep(300); val image = instrumentation.uiAutomation.takeScreenshot(); assertNotNull(image)
        val folder = File(context.filesDir, "vertical-navigation"); assertTrue(folder.isDirectory || folder.mkdirs())
        try { FileOutputStream(File(folder, "$name.png")).use { out -> image.compress(Bitmap.CompressFormat.PNG, 100, out) } }
        finally { image.recycle() }
    }

    companion object {
        private fun bounds(view: View): Rect {
            val location = IntArray(2); view.getLocationOnScreen(location)
            return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }
        private fun labelsShown(view: View): Boolean {
            if (!view.isShown) return true
            if (view is TextView && view !is android.widget.TextClock) return view.alpha == 1f
            if (view is ViewGroup) for (i in 0 until view.childCount) if (!labelsShown(view.getChildAt(i))) return false
            return true
        }
    }
}
