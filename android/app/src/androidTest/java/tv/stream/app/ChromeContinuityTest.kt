package tv.stream.app

import org.junit.Assert.*
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextClock
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import tv.stream.app.addon.Title
import tv.stream.app.ui.IntroView
import tv.stream.app.ui.Item
import tv.stream.app.ui.Page

@RunWith(AndroidJUnit4::class)
class ChromeContinuityTest : AppHarness() {
    @Test @Throws(Exception::class) fun browseClockLoadingAndCircularActions() {
        readyLibrary(); launch(); waitUntil("home", 10000) { visible("Generated Film") }
        val clock = clockBounds(R.id.clock)
        browse!!.onActivity { activity ->
            val tools = activity.findViewById<View>(R.id.home_tools); val bounds = Rect(); tools.getGlobalVisibleRect(bounds)
            assertTrue("Refresh sits left of the clock", bounds.right < clock.left)
            assertFalse("Default Home has no Back button", activity.findViewById<View>(R.id.page_back).isShown)
        }
        tap("Refresh", true); SystemClock.sleep(100)
        browse!!.onActivity { activity ->
            val refresh = activity.findViewById<ViewGroup>(R.id.home_refresh)
            assertTrue("Refresh icon rotates on click", refresh.getChildAt(0).rotation > 0)
        }
        waitUntil("refreshed home", 10000) { visible("Generated Film") }
        tap("Generated Film", true); waitUntil("film", 10000) { visible("Play") }
        assertEquals(clock, clockBounds(R.id.clock))
        browse!!.onActivity { activity ->
            for (key in arrayOf("favourite", "watched")) {
                val button = activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>(key)
                assertNotNull(button)
                assertEquals("Icon buttons have a circular footprint", button.height.toLong(), button.width.toLong())
                assertEquals(dp(44).toLong(), button.width.toLong())
                assertFalse("Icon buttons keep accessible action labels", button.contentDescription!!.toString().isEmpty())
            }
        }
        tap("Add favourite", true); waitUntil("favourite set", 5000) { visible("Remove favourite") }
        browse!!.onActivity { activity -> assertTrue(activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("favourite").isActivated) }
        tap("Remove favourite", false); waitUntil("favourite cleared", 5000) { visible("Add favourite") }
        tap("Mark watched", false)
        waitUntil("watched", 5000) { visible("Mark unwatched") }
        browse!!.onActivity { activity -> assertTrue(activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("watched").isActivated) }
        screenshot("chrome-circular-actions.png")
        tap("Back", true); waitUntil("home restored", 5000) { visible("Browse") }
        browse!!.onActivity { activity -> invoke(activity, "loading") }
        browse!!.onActivity { activity -> assertTrue("Navigation loading keeps the clock steady", activity.findViewById<View>(R.id.clock).isShown) }
        browse!!.onActivity { activity -> activity.refresh() }
        waitUntil("browse clock returns", 10000) { clockShown(R.id.clock) }
        // Preview the real intro view without enabling starter providers in the isolated harness.
        val intro = arrayOfNulls<IntroView>(1)
        browse!!.onActivity { activity ->
            intro[0] = IntroView(activity)
            val root = activity.findViewById<FrameLayout>(R.id.browse_root)
            root.addView(intro[0], FrameLayout.LayoutParams(-1, -1))
            setIntro(activity, intro[0])
            invoke(activity, "updateClock")
            assertFalse("Startup hides the clock", activity.findViewById<View>(R.id.clock).isShown)
        }
        SystemClock.sleep(950)
        for (i in 0 until 5) { screenshot("chrome-intro-$i.png"); SystemClock.sleep(140) }
        browse!!.onActivity { activity ->
            if (intro[0]!!.parent is ViewGroup) (intro[0]!!.parent as ViewGroup).removeView(intro[0])
            setIntro(activity, null)
            invoke(activity, "updateClock")
            assertTrue("Browse clock returns after startup", activity.findViewById<View>(R.id.clock).isShown)
        }
    }

    @Test @Throws(Exception::class) fun backRestoresTheExactShelfAndCardWithoutReloading() {
        readyLibrary(); launch(); waitUntil("home", 10000) { visible("Generated Film") }
        val provider = services.store.addons()[0].id
        val film = Title.parse(provider, JSONObject().put("id", "fixture:film").put("type", "movie").put("name", "Generated Film"))!!
        browse!!.onActivity { activity ->
            val page = Page()
            for (row in 0 until 2) {
                val cards: MutableList<Item.Tile> = ArrayList()
                for (i in 0 until 18) cards.add(Item.Tile("row$row:$i", Item.Tile.Style.POSTER,
                        "Generated $i", "2026", null, film, Runnable { activity.openTitle(film, "") }))
                page.add(Item.shelf("saved-shelf:$row", "Shelf $row", "", Item.Tile.Style.POSTER, cards))
            }
            try { val show = MainActivity::class.java.getDeclaredMethod("show", Page::class.java); show.isAccessible = true; show.invoke(activity, page) }
            catch (e: Exception) { throw AssertionError(e) }
        }
        instrumentation.waitForIdleSync()
        browse!!.onActivity { activity ->
            val page = activity.findViewById<RecyclerView>(R.id.page_rows); page.scrollBy(0, 320)
            val card = page.findViewWithTag<View>("row1:0"); assertNotNull(card)
            var parent: View = card
            while (parent !is RecyclerView) parent = parent.parent as View
            val shelf = parent as RecyclerView
            (shelf.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(6, 80)
        }
        instrumentation.waitForIdleSync()
        browse!!.onActivity { activity -> activity.findViewById<View>(R.id.page_rows).findViewWithTag<View>("row1:7").requestFocus() }
        SystemClock.sleep(400)
        val before = Rect()
        browse!!.onActivity { activity -> assertTrue(activity.currentFocus!!.getGlobalVisibleRect(before)) }
        pointer(before.centerX().toFloat(), before.centerY().toFloat(), true)
        waitUntil("details", 10000) { visible("Play") }
        // A re-fetch would replace these injected shelves with fixture Home; Back must use the retained page.
        tap("Back", false)
        waitUntil("same card focus", 5000) { focused("row1:7") }
        SystemClock.sleep(1500)
        browse!!.onActivity { activity ->
            val selected = activity.currentFocus; assertEquals("No background reload steals focus", "row1:7", selected!!.tag)
            val after = Rect(); assertTrue(selected.getGlobalVisibleRect(after))
            assertEquals("Same horizontal position", before.left.toFloat(), after.left.toFloat(), 2f)
            assertEquals("Same vertical position", before.top.toFloat(), after.top.toFloat(), 2f)
        }
        screenshot("chrome-restored-card.png")
    }

    @Test @Throws(Exception::class) fun playerClockOnlyShowsForPausedControlsAndMenus() {
        playFilm()
        val activity = watch()
        assertFalse("Active playback hides the clock", clockShown(R.id.player_clock))
        SystemClock.sleep(4500)
        instrumentation.runOnMainSync { assertFalse("Playback controls have faded", activity!!.findViewById<View>(R.id.controls).isShown) }
        assertFalse("Uninterrupted playback stays clear", clockShown(R.id.player_clock))
        control(tv.stream.playback.R.id.player_view, false); control(R.id.progress, true)
        waitUntil("paused", 5000) { !state()!!.playWhenReady }
        waitUntil("paused clock", 5000) { clockShown(R.id.player_clock) }
        val initial = clockBounds(R.id.player_clock)
        assertEquals("Fixed right inset", (context.resources.displayMetrics.widthPixels - dp(48)).toLong(), initial.right.toLong())
        assertEquals("Fixed top inset", dp(22).toLong(), initial.top.toLong())
        instrumentation.runOnMainSync {
            val button = Rect(); activity!!.findViewById<View>(R.id.control_settings).getGlobalVisibleRect(button)
            assertTrue("Player actions sit left of the clock", button.right < initial.left)
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
        waitUntil("player menu", 5000) { visible("Settings") }
        assertEquals(initial, clockBounds(R.id.player_clock))
        SystemClock.sleep(400)
        screenshot("chrome-player-menu-clock.png")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY)
        waitUntil("resumed clock hidden", 5000) { state()!!.playWhenReady && !clockShown(R.id.player_clock) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
        waitUntil("clock appears in menu during playback", 5000) { clockShown(R.id.player_clock) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
        waitUntil("closing menu clears active picture", 5000) { !clockShown(R.id.player_clock) }
    }
    private fun invoke(activity: MainActivity, name: String) {
        try { val method = MainActivity::class.java.getDeclaredMethod(name); method.isAccessible = true; method.invoke(activity) }
        catch (e: Exception) { throw AssertionError(e) }
    }
    private fun setIntro(activity: MainActivity, intro: IntroView?) {
        try { val field = MainActivity::class.java.getDeclaredField("intro"); field.isAccessible = true; field.set(activity, intro) }
        catch (e: Exception) { throw AssertionError(e) }
    }
    private fun clockShown(id: Int): Boolean {
        val result = AtomicBoolean()
        val playing = watch()
        if (playing != null) instrumentation.runOnMainSync { result.set(playing.findViewById<View>(id).isShown) }
        else browse!!.onActivity { activity -> result.set(activity.findViewById<View>(id).isShown) }
        return result.get()
    }
    @Test @Throws(Exception::class) fun introShineReachesTheWholeInteriorIncludingTheTip() {
        val intro = IntroView(context)
        val started = IntroView::class.java.getDeclaredField("started"); started.isAccessible = true
        val origin = started.getLong(intro)
        val draw = IntroView::class.java.getDeclaredMethod("drawMark", android.graphics.Canvas::class.java,
                java.lang.Long.TYPE, java.lang.Float.TYPE, java.lang.Float.TYPE, java.lang.Float.TYPE, java.lang.Float.TYPE, java.lang.Float.TYPE)
        draw.isAccessible = true
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val points = arrayOf(floatArrayOf(42.5f, 44f), floatArrayOf(42.5f, 64f), floatArrayOf(48f, 41f), floatArrayOf(48f, 66f), floatArrayOf(55f, 54f), floatArrayOf(68.5f, 54f))
        val baseline = IntArray(points.size); val brightest = IntArray(points.size)
        try {
            bitmap.eraseColor(android.graphics.Color.BLACK)
            draw.invoke(intro, canvas, origin + 3000, 0f, 256f, 256f, 1080f, 1f)
            for (i in points.indices) baseline[i] = brightness(bitmap, points[i])
            var age = 900
            while (age <= 2020) {
                bitmap.eraseColor(android.graphics.Color.BLACK)
                draw.invoke(intro, canvas, origin + age, 0f, 256f, 256f, 1080f, 1f)
                for (i in points.indices) brightest[i] = Math.max(brightest[i], brightness(bitmap, points[i]))
                age += 20
            }
            for (i in points.indices) assertTrue("Shine reaches interior sample $i including the tip",
                    brightest[i] >= baseline[i] + 25)
        } finally { bitmap.recycle() }
    }
    private fun brightness(bitmap: Bitmap, point: FloatArray): Int {
        val color = bitmap.getPixel(Math.round(256 + (point[0] - 56.75f) * 10), Math.round(256 + (point[1] - 54) * 10))
        return android.graphics.Color.red(color)
    }
    private fun dp(value: Int): Int { return Math.round(value * context.resources.displayMetrics.density) }
    private fun focused(key: String): Boolean {
        val found = AtomicBoolean()
        browse!!.onActivity { activity -> found.set(activity.currentFocus != null && key == activity.currentFocus!!.tag) }
        return found.get()
    }
    private fun clockBounds(id: Int): Rect {
        val result = Rect()
        val playing = watch()
        if (playing != null) instrumentation.runOnMainSync { val clock = playing.findViewById<TextClock>(id); assertNotNull(clock); assertTrue(clock.getGlobalVisibleRect(result)) }
        else browse!!.onActivity { activity -> val clock = activity.findViewById<TextClock>(id); assertTrue(clock.getGlobalVisibleRect(result)) }
        return result
    }
    @Throws(Exception::class)
    private fun screenshot(name: String) {
        val bitmap: Bitmap? = instrumentation.uiAutomation.takeScreenshot(); assertNotNull(bitmap)
        FileOutputStream(File(context.filesDir, name)).use { out -> bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap!!.recycle()
    }
}
