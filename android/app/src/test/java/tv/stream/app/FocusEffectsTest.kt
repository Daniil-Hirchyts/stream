package tv.stream.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.ArrayList
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import tv.stream.app.ui.Item
import tv.stream.app.ui.Page
import tv.stream.app.ui.PageAdapter
import tv.stream.app.ui.Tv

/**
 * Offline checks of focus growth on the real activities at 1920×1080. Every control that grows when focused must be
 * drawn whole: no ancestor may clip its grown bounds. The first tile of a row grows from its leading edge, and
 * moving focus along a shelf never scrolls it. Snapshots go to build/focus-effects for review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FocusEffectsTest {
    private lateinit var activity: Activity
    private lateinit var out: File

    @Before fun start() {
        FakeKeyStore.install()
        out = File("build/focus-effects"); out.mkdirs()
    }

    @Test @Throws(Exception::class) fun everyGrownBrowseControlIsDrawnWhole() {
        val main = browse()
        val tiles: MutableList<Item.Tile> = ArrayList()
        for (i in 0 until 8) tiles.add(Item.Tile("tile:" + i, Item.Tile.Style.POSTER, "Film " + i, "2026", null, null, Runnable {}))
        val chips: MutableList<Item.Action> = ArrayList()
        for (g in arrayOf("Any", "Action", "Comedy")) chips.add(Item.Action("chip:" + g, Item.Action.Style.CHIP, g, Runnable {}).selected(g.equals("Any")))
        val page = Page()
        page.tools = Arrays.asList(Item.Action("browse", Item.Action.Style.ICON, "Browse", Runnable {}).icon(R.drawable.ic_tv_list),
                Item.Action("refresh", Item.Action.Style.ICON, "Refresh", Runnable {}).icon(R.drawable.ic_tv_refresh))
        page.add(Item.catalogueHeader("Popular Films", "Films · Page 2"))
        page.add(Item.actions("buttons", Arrays.asList(Item.Action("play", Item.Action.Style.PRIMARY, "Play", Runnable {}),
                Item.Action("sources", Item.Action.Style.SECONDARY, "Sources", Runnable {}),
                Item.Action("favourite", Item.Action.Style.ICON, "Add favourite", Runnable {}).icon(R.drawable.ic_tv_heart_outline))))
        page.add(Item.chips("chips", "", chips))
        page.add(Item.shelf("shelf", "Popular", "", Item.Tile.Style.POSTER, tiles))
        page.addAll(Item.grid("grid", Item.Tile.Style.POSTER, 6, tiles.subList(0, 6)))
        page.add(Item.pagination("paging", "Page 2", Arrays.asList(
                Item.Action("previous-page:x", Item.Action.Style.SECONDARY, "Previous page", Runnable {}),
                Item.Action("next-page:x", Item.Action.Style.SECONDARY, "Next page", Runnable {}))))
        show(main, page)
        main.findViewById<View>(R.id.page_back).setVisibility(View.VISIBLE)
        settle(300)
        val clipped = sweep("browse")
        main.search(); settle(1500)
        clipped.addAll(sweep("search"))
        main.root("Settings"); settle(2500)
        clipped.addAll(sweep("settings"))
        assertTrue("clipped while focused:\n" + java.lang.String.join("\n", clipped), clipped.isEmpty())
    }

    @Test @Throws(Exception::class) fun theFirstTileGrowsFromItsLeadingEdgeAndTheShelfStaysStill() {
        val main = browse()
        val tiles: MutableList<Item.Tile> = ArrayList()
        for (i in 0 until 12) tiles.add(Item.Tile("tile:" + i, Item.Tile.Style.POSTER, "Film " + i, "2026", null, null, Runnable {}))
        val page = Page()
        page.add(Item.header("Fixture"))
        page.add(Item.shelf("shelf", "Popular", "", Item.Tile.Style.POSTER, tiles))
        show(main, page)
        val first = find("tile:0"); val second = find("tile:1")
        val shelf = first.getParent().getParent() as RecyclerView
        val restingLeft = drawn(art(first)).left
        second.requestFocus(); settle(500)
        val scroll = shelf.computeHorizontalScrollOffset()
        val thirdLeft = drawn(find("tile:2")).left
        // By remote, then by a pointer click as on the emulator.
        first.requestFocus(); settle(500)
        snap("shelf-first-focused")
        assertTrue("first tile grew", art(first).getScaleX() > 1.05f)
        assertEquals("first tile's left edge while grown", restingLeft, drawn(art(first)).left, 1f)
        second.requestFocus(); settle(500)
        val at = IntArray(2); first.getLocationInWindow(at)
        val `when` = android.os.SystemClock.uptimeMillis()
        val x = at[0] + first.getWidth() / 2f; val y = at[1] + first.getHeight() / 3f
        main.getWindow().getDecorView().dispatchTouchEvent(android.view.MotionEvent.obtain(`when`, `when`, android.view.MotionEvent.ACTION_DOWN, x, y, 0))
        main.getWindow().getDecorView().dispatchTouchEvent(android.view.MotionEvent.obtain(`when`, `when` + 60, android.view.MotionEvent.ACTION_UP, x, y, 0))
        settle(500)
        assertTrue("first tile focused by the pointer", first.isFocused())
        assertEquals("shelf scroll", scroll, shelf.computeHorizontalScrollOffset())
        assertEquals("third tile", thirdLeft, drawn(find("tile:2")).left, 1f)
        assertEquals("first tile's left edge after a click", restingLeft, drawn(art(first)).left, 1f)
    }

    @Test @Throws(Exception::class) fun playerButtonsGrowWholeAndTheSkipLabelMovesNothing() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(R.layout.activity_watch)
        activity.findViewById<View>(R.id.controls).setVisibility(View.VISIBLE)
        activity.findViewById<View>(R.id.center_icon).setVisibility(View.VISIBLE)
        activity.findViewById<View>(R.id.player_clock).setVisibility(View.VISIBLE)
        settle(300)
        val strip = activity.findViewById<View>(R.id.progress).getParent() as View
        val before = intArrayOf(strip.getTop(), strip.getHeight(), activity.findViewById<View>(R.id.elapsed).getTop())
        activity.findViewById<View>(R.id.seek_bubble).setVisibility(View.VISIBLE)
        activity.findViewById<android.widget.TextView>(R.id.seek_label).setText("+30 s")
        settle(300)
        assertEquals("timeline strip top", before[0], strip.getTop())
        assertEquals("timeline strip height", before[1], strip.getHeight())
        assertEquals("elapsed time", before[2], activity.findViewById<View>(R.id.elapsed).getTop())
        assertNull("no box behind the skip label", activity.findViewById<View>(R.id.seek_bubble).getBackground())
        for (id in intArrayOf(R.id.control_rewind, R.id.control_forward))
            assertTrue("skip buttons stay hidden", activity.findViewById<View>(id).getVisibility() != View.VISIBLE)
        val clipped: MutableList<String> = ArrayList()
        for (id in WatchActivity.LIFTED) {
            val button = activity.findViewById<View>(id)
            button.setScaleX(Tv.LIFT_ICON); button.setScaleY(Tv.LIFT_ICON)
            val problem = clipping(button)
            if (problem != null) clipped.add(name(button) + ": " + problem)
        }
        settle(100)
        snap("player-grown")
        assertTrue("clipped while grown:\n" + java.lang.String.join("\n", clipped), clipped.isEmpty())
    }

    // The sweep.

    /** Focuses every focusable control on screen in turn; reports each grown view an ancestor clips. */
    @Throws(Exception::class)
    private fun sweep(screen: String): MutableList<String> {
        val clipped: MutableList<String> = ArrayList()
        val controls: ArrayList<View> = activity.getWindow().getDecorView().getFocusables(View.FOCUS_FORWARD)
        var grown = 0
        for (control in controls) {
            if (!control.isShown() || control.getWidth() == 0) continue
            control.requestFocus(); settle(260)
            if (!control.isFocused()) continue
            for (v in grownIn(control)) {
                grown++
                val problem = clipping(v)
                if (problem != null) clipped.add(screen + " " + name(control) + ": " + problem)
            }
        }
        println(screen + ": " + controls.size + " focusable, " + grown + " grown views checked, " + clipped.size + " clipped")
        snap(screen)
        return clipped
    }

    // The browse activity.

    @Throws(Exception::class)
    private fun browse(): MainActivity {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity = main
        settle(2500)
        // The start screen covers the browse shell on an owner launch; these checks are about the shell beneath.
        val root: ViewGroup = main.findViewById(R.id.browse_root)
        var i = root.getChildCount() - 1
        while (i >= 0) { if (root.getChildAt(i) is tv.stream.app.ui.IntroView) root.removeViewAt(i); i-- }
        val intro = MainActivity::class.java.getDeclaredField("intro"); intro.setAccessible(true); intro.set(main, null)
        settle(300)
        return main
    }

    /** Shows `page` in place of Home; Home's own late refresh (starter add-ons) may replace it, so it shows again. */
    @Throws(Exception::class)
    private fun show(main: MainActivity, page: Page) {
        val show = MainActivity::class.java.getDeclaredMethod("show", Page::class.java, java.lang.Boolean.TYPE)
        show.setAccessible(true)
        val marker = page.items.get(page.items.size - 1).key
        for (attempt in 0 until 20) {
            main.runOnUiThread { try { show.invoke(main, page, false) } catch (e: Exception) { throw AssertionError(e) } }
            settle(1200)
            if (shown(main, marker)) { settle(1500); if (shown(main, marker)) return }
        }
        throw AssertionError("page never stayed")
    }
    private fun find(tag: String): View { return activity.getWindow().getDecorView().findViewWithTag(tag) }

    @Throws(Exception::class)
    private fun snap(name: String) {
        val decor = activity.getWindow().getDecorView()
        val bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888)
        val done = AtomicBoolean()
        PixelCopy.request(activity.getWindow(), bitmap, { result -> done.set(true) }, Handler(Looper.getMainLooper()))
        var i = 0
        while (i < 200 && !done.get()) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10)); i++ }
        FileOutputStream(File(out, name + ".png")).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
    }

    companion object {
        private fun grownIn(root: View): List<View> {
            val found: MutableList<View> = ArrayList()
            if (root.getScaleX() > 1.001f || root.getScaleY() > 1.001f) found.add(root)
            if (root is ViewGroup) for (i in 0 until root.getChildCount()) found.addAll(grownIn(root.getChildAt(i)))
            return found
        }

        /**
         * Where `view`'s drawn (transformed) bounds escape a clip: a parent with clipChildren cuts each child at the
         * child's own bounds, clipToPadding at its padding, and clipToOutline at its outline (its bounds here).
         */
        private fun clipping(view: View): String? {
            val drawn = drawn(view)
            var child: View = view
            while (child.getParent() is ViewGroup) {
                val parent = child.getParent() as ViewGroup
                val bounds = window(child)
                if (parent.getClipChildren() && !within(drawn, bounds)) return "cut at " + name(child) + " (" + name(parent) + " clips children) " + drawn + " outside " + bounds
                if (child !== view && child.getClipToOutline() && !within(drawn, bounds)) return "cut at outline of " + name(child)
                // ViewGroup clips to its padding only when it has some.
                val padded = child.getPaddingLeft() > 0 || child.getPaddingTop() > 0 || child.getPaddingRight() > 0 || child.getPaddingBottom() > 0
                if (child !== view && child is ViewGroup && child.getClipToPadding() && padded) {
                    val inside = RectF(bounds.left + child.getPaddingLeft(), bounds.top + child.getPaddingTop(),
                            bounds.right - child.getPaddingRight(), bounds.bottom - child.getPaddingBottom())
                    if (!within(drawn, inside)) return "cut at padding of " + name(child)
                }
                if (parent.getId() == android.R.id.content) break
                child = child.getParent() as View
            }
            return null
        }
        private fun within(inner: RectF, outer: RectF): Boolean {
            return inner.left >= outer.left - 1 && inner.top >= outer.top - 1 && inner.right <= outer.right + 1 && inner.bottom <= outer.bottom + 1
        }
        /** Bounds in the window, without the view's own transform. */
        private fun window(v: View): RectF {
            val r = RectF(0f, 0f, v.getWidth().toFloat(), v.getHeight().toFloat())
            mapToWindow(v, r, false)
            return r
        }
        /** Bounds in the window as drawn, with the view's own scale and its ancestors'. */
        private fun drawn(v: View): RectF {
            val r = RectF(0f, 0f, v.getWidth().toFloat(), v.getHeight().toFloat())
            mapToWindow(v, r, true)
            return r
        }
        private fun mapToWindow(v: View, r: RectF, own: Boolean) {
            var at: View? = v
            while (at != null) {
                if (at !== v || own) { val m: Matrix = at.getMatrix(); if (!m.isIdentity()) m.mapRect(r) }
                val parent: View? = if (at.getParent() is View) at.getParent() as View else null
                r.offset((at.getLeft() - (if (parent == null) 0 else parent.getScrollX())).toFloat(), (at.getTop() - (if (parent == null) 0 else parent.getScrollY())).toFloat())
                at = if (at.getParent() is View) at.getParent() as View else null
            }
        }
        private fun name(v: View): String {
            val id: String? = if (v.getId() > 0) v.getResources().getResourceEntryName(v.getId()) else null
            val tag: Any? = v.getTag()
            val label: CharSequence? = v.getContentDescription()
            return v.javaClass.getSimpleName() + (if (id != null) "#" + id else "") + (if (tag is String) "[" + tag + "]" else "") +
                    (if (label != null) "'" + label + "'" else "")
        }

        private fun shown(main: MainActivity, key: String): Boolean {
            val rows: RecyclerView = main.findViewById(R.id.page_rows)
            val adapter = rows.getAdapter() as PageAdapter?
            return adapter != null && adapter.position(key) >= 0
        }
        private fun art(tile: View): View { return (tile as ViewGroup).getChildAt(0) }

        @Throws(InterruptedException::class)
        private fun settle(ms: Long) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
                Thread.sleep(25)
            }
        }
    }
}
