package tv.daniil.stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import tv.daniil.stream.ui.Item;
import tv.daniil.stream.ui.Page;
import tv.daniil.stream.ui.PageAdapter;
import tv.daniil.stream.ui.Tv;

/**
 * Offline checks of focus growth on the real activities at 1920×1080. Every control that grows when focused must be
 * drawn whole: no ancestor may clip its grown bounds. The first tile of a row grows from its leading edge, and
 * moving focus along a shelf never scrolls it. Snapshots go to build/focus-effects for review.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class FocusEffectsTest {
    private Activity activity;
    private File out;

    @Before public void start() {
        FakeKeyStore.install();
        out = new File("build/focus-effects"); out.mkdirs();
    }

    @Test public void everyGrownBrowseControlIsDrawnWhole() throws Exception {
        MainActivity main = browse();
        List<Item.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < 8; i++) tiles.add(new Item.Tile("tile:" + i, Item.Tile.Style.POSTER, "Film " + i, "2026", null, null, () -> {}));
        List<Item.Action> chips = new ArrayList<>();
        for (String g : new String[]{"Any", "Action", "Comedy"}) chips.add(new Item.Action("chip:" + g, Item.Action.Style.CHIP, g, () -> {}).selected(g.equals("Any")));
        Page page = new Page();
        page.tools = Arrays.asList(new Item.Action("browse", Item.Action.Style.ICON, "Browse", () -> {}).icon(R.drawable.ic_tv_list),
                new Item.Action("refresh", Item.Action.Style.ICON, "Refresh", () -> {}).icon(R.drawable.ic_tv_refresh));
        page.add(Item.catalogueHeader("Popular Films", "Films · Page 2"));
        page.add(Item.actions("buttons", Arrays.asList(new Item.Action("play", Item.Action.Style.PRIMARY, "Play", () -> {}),
                new Item.Action("sources", Item.Action.Style.SECONDARY, "Sources", () -> {}),
                new Item.Action("favourite", Item.Action.Style.ICON, "Add favourite", () -> {}).icon(R.drawable.ic_tv_heart_outline))));
        page.add(Item.chips("chips", "", chips));
        page.add(Item.shelf("shelf", "Popular", "", Item.Tile.Style.POSTER, tiles));
        page.addAll(Item.grid("grid", Item.Tile.Style.POSTER, 6, tiles.subList(0, 6)));
        page.add(Item.pagination("paging", "Page 2", Arrays.asList(
                new Item.Action("previous-page:x", Item.Action.Style.SECONDARY, "Previous page", () -> {}),
                new Item.Action("next-page:x", Item.Action.Style.SECONDARY, "Next page", () -> {}))));
        show(main, page);
        main.findViewById(R.id.page_back).setVisibility(View.VISIBLE);
        settle(300);
        List<String> clipped = sweep("browse");
        main.search(); settle(1500);
        clipped.addAll(sweep("search"));
        main.root("Settings"); settle(2500);
        clipped.addAll(sweep("settings"));
        assertTrue("clipped while focused:\n" + String.join("\n", clipped), clipped.isEmpty());
    }

    @Test public void theFirstTileGrowsFromItsLeadingEdgeAndTheShelfStaysStill() throws Exception {
        MainActivity main = browse();
        List<Item.Tile> tiles = new ArrayList<>();
        for (int i = 0; i < 12; i++) tiles.add(new Item.Tile("tile:" + i, Item.Tile.Style.POSTER, "Film " + i, "2026", null, null, () -> {}));
        Page page = new Page();
        page.add(Item.header("Fixture"));
        page.add(Item.shelf("shelf", "Popular", "", Item.Tile.Style.POSTER, tiles));
        show(main, page);
        View first = find("tile:0"), second = find("tile:1");
        RecyclerView shelf = (RecyclerView) first.getParent().getParent();
        float restingLeft = drawn(art(first)).left;
        second.requestFocus(); settle(500);
        int scroll = shelf.computeHorizontalScrollOffset();
        float thirdLeft = drawn(find("tile:2")).left;
        // By remote, then by a pointer click as on the emulator.
        first.requestFocus(); settle(500);
        snap("shelf-first-focused");
        assertTrue("first tile grew", art(first).getScaleX() > 1.05f);
        assertEquals("first tile's left edge while grown", restingLeft, drawn(art(first)).left, 1f);
        second.requestFocus(); settle(500);
        int[] at = new int[2]; first.getLocationInWindow(at);
        long when = android.os.SystemClock.uptimeMillis();
        float x = at[0] + first.getWidth() / 2f, y = at[1] + first.getHeight() / 3f;
        main.getWindow().getDecorView().dispatchTouchEvent(android.view.MotionEvent.obtain(when, when, android.view.MotionEvent.ACTION_DOWN, x, y, 0));
        main.getWindow().getDecorView().dispatchTouchEvent(android.view.MotionEvent.obtain(when, when + 60, android.view.MotionEvent.ACTION_UP, x, y, 0));
        settle(500);
        assertTrue("first tile focused by the pointer", first.isFocused());
        assertEquals("shelf scroll", scroll, shelf.computeHorizontalScrollOffset());
        assertEquals("third tile", thirdLeft, drawn(find("tile:2")).left, 1f);
        assertEquals("first tile's left edge after a click", restingLeft, drawn(art(first)).left, 1f);
    }

    @Test public void playerButtonsGrowWholeAndTheSkipLabelMovesNothing() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(R.layout.activity_watch);
        activity.findViewById(R.id.controls).setVisibility(View.VISIBLE);
        activity.findViewById(R.id.center_icon).setVisibility(View.VISIBLE);
        activity.findViewById(R.id.player_clock).setVisibility(View.VISIBLE);
        settle(300);
        View strip = (View) activity.findViewById(R.id.progress).getParent();
        int[] before = {strip.getTop(), strip.getHeight(), activity.findViewById(R.id.elapsed).getTop()};
        activity.findViewById(R.id.seek_bubble).setVisibility(View.VISIBLE);
        ((android.widget.TextView) activity.findViewById(R.id.seek_label)).setText("+30 s");
        settle(300);
        assertEquals("timeline strip top", before[0], strip.getTop());
        assertEquals("timeline strip height", before[1], strip.getHeight());
        assertEquals("elapsed time", before[2], activity.findViewById(R.id.elapsed).getTop());
        assertNull("no box behind the skip label", activity.findViewById(R.id.seek_bubble).getBackground());
        for (int id : new int[]{R.id.control_rewind, R.id.control_forward})
            assertTrue("skip buttons stay hidden", activity.findViewById(id).getVisibility() != View.VISIBLE);
        List<String> clipped = new ArrayList<>();
        for (int id : WatchActivity.LIFTED) {
            View button = activity.findViewById(id);
            button.setScaleX(Tv.LIFT_ICON); button.setScaleY(Tv.LIFT_ICON);
            String problem = clipping(button);
            if (problem != null) clipped.add(name(button) + ": " + problem);
        }
        settle(100);
        snap("player-grown");
        assertTrue("clipped while grown:\n" + String.join("\n", clipped), clipped.isEmpty());
    }

    // The sweep.

    /** Focuses every focusable control on screen in turn; reports each grown view an ancestor clips. */
    private List<String> sweep(String screen) throws Exception {
        List<String> clipped = new ArrayList<>();
        ArrayList<View> controls = activity.getWindow().getDecorView().getFocusables(View.FOCUS_FORWARD);
        int grown = 0;
        for (View control : controls) {
            if (!control.isShown() || control.getWidth() == 0) continue;
            control.requestFocus(); settle(260);
            if (!control.isFocused()) continue;
            for (View v : grownIn(control)) {
                grown++;
                String problem = clipping(v);
                if (problem != null) clipped.add(screen + " " + name(control) + ": " + problem);
            }
        }
        System.out.println(screen + ": " + controls.size() + " focusable, " + grown + " grown views checked, " + clipped.size() + " clipped");
        snap(screen);
        return clipped;
    }
    private static List<View> grownIn(View root) {
        List<View> found = new ArrayList<>();
        if (root.getScaleX() > 1.001f || root.getScaleY() > 1.001f) found.add(root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) found.addAll(grownIn(((ViewGroup) root).getChildAt(i)));
        return found;
    }

    /**
     * Where {@code view}'s drawn (transformed) bounds escape a clip: a parent with clipChildren cuts each child at the
     * child's own bounds, clipToPadding at its padding, and clipToOutline at its outline (its bounds here).
     */
    private static String clipping(View view) {
        RectF drawn = drawn(view);
        for (View child = view; child.getParent() instanceof ViewGroup; child = (View) child.getParent()) {
            ViewGroup parent = (ViewGroup) child.getParent();
            RectF bounds = window(child);
            if (parent.getClipChildren() && !within(drawn, bounds)) return "cut at " + name(child) + " (" + name(parent) + " clips children) " + drawn + " outside " + bounds;
            if (child != view && child.getClipToOutline() && !within(drawn, bounds)) return "cut at outline of " + name(child);
            // ViewGroup clips to its padding only when it has some.
            boolean padded = child.getPaddingLeft() > 0 || child.getPaddingTop() > 0 || child.getPaddingRight() > 0 || child.getPaddingBottom() > 0;
            if (child != view && child instanceof ViewGroup && ((ViewGroup) child).getClipToPadding() && padded) {
                RectF inside = new RectF(bounds.left + child.getPaddingLeft(), bounds.top + child.getPaddingTop(),
                        bounds.right - child.getPaddingRight(), bounds.bottom - child.getPaddingBottom());
                if (!within(drawn, inside)) return "cut at padding of " + name(child);
            }
            if (parent.getId() == android.R.id.content) break;
        }
        return null;
    }
    private static boolean within(RectF inner, RectF outer) {
        return inner.left >= outer.left - 1 && inner.top >= outer.top - 1 && inner.right <= outer.right + 1 && inner.bottom <= outer.bottom + 1;
    }
    /** Bounds in the window, without the view's own transform. */
    private static RectF window(View v) {
        RectF r = new RectF(0, 0, v.getWidth(), v.getHeight());
        mapToWindow(v, r, false);
        return r;
    }
    /** Bounds in the window as drawn, with the view's own scale and its ancestors'. */
    private static RectF drawn(View v) {
        RectF r = new RectF(0, 0, v.getWidth(), v.getHeight());
        mapToWindow(v, r, true);
        return r;
    }
    private static void mapToWindow(View v, RectF r, boolean own) {
        for (View at = v; at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null) {
            if (at != v || own) { Matrix m = at.getMatrix(); if (!m.isIdentity()) m.mapRect(r); }
            View parent = at.getParent() instanceof View ? (View) at.getParent() : null;
            r.offset(at.getLeft() - (parent == null ? 0 : parent.getScrollX()), at.getTop() - (parent == null ? 0 : parent.getScrollY()));
        }
    }
    private static String name(View v) {
        String id = v.getId() > 0 ? v.getResources().getResourceEntryName(v.getId()) : null;
        Object tag = v.getTag();
        CharSequence label = v.getContentDescription();
        return v.getClass().getSimpleName() + (id != null ? "#" + id : "") + (tag instanceof String ? "[" + tag + "]" : "")
                + (label != null ? "'" + label + "'" : "");
    }

    // The browse activity.

    private MainActivity browse() throws Exception {
        MainActivity main = Robolectric.buildActivity(MainActivity.class).setup().get();
        activity = main;
        settle(2500);
        // The start screen covers the browse shell on an owner launch; these checks are about the shell beneath.
        ViewGroup root = main.findViewById(R.id.browse_root);
        for (int i = root.getChildCount() - 1; i >= 0; i--) if (root.getChildAt(i) instanceof tv.daniil.stream.ui.IntroView) root.removeViewAt(i);
        java.lang.reflect.Field intro = MainActivity.class.getDeclaredField("intro"); intro.setAccessible(true); intro.set(main, null);
        settle(300);
        return main;
    }

    /** Shows {@code page} in place of Home; Home's own late refresh (starter add-ons) may replace it, so it shows again. */
    private void show(MainActivity main, Page page) throws Exception {
        java.lang.reflect.Method show = MainActivity.class.getDeclaredMethod("show", Page.class, boolean.class);
        show.setAccessible(true);
        String marker = page.items.get(page.items.size() - 1).key;
        for (int attempt = 0; attempt < 20; attempt++) {
            main.runOnUiThread(() -> { try { show.invoke(main, page, false); } catch (Exception e) { throw new AssertionError(e); } });
            settle(1200);
            if (shown(main, marker)) { settle(1500); if (shown(main, marker)) return; }
        }
        throw new AssertionError("page never stayed");
    }
    private static boolean shown(MainActivity main, String key) {
        RecyclerView rows = main.findViewById(R.id.page_rows);
        PageAdapter adapter = (PageAdapter) rows.getAdapter();
        return adapter != null && adapter.position(key) >= 0;
    }
    private View find(String tag) { return activity.getWindow().getDecorView().findViewWithTag(tag); }
    private static View art(View tile) { return ((ViewGroup) tile).getChildAt(0); }

    private void snap(String name) throws Exception {
        View decor = activity.getWindow().getDecorView();
        Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        AtomicBoolean done = new AtomicBoolean();
        PixelCopy.request(activity.getWindow(), bitmap, result -> done.set(true), new Handler(Looper.getMainLooper()));
        for (int i = 0; i < 200 && !done.get(); i++) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));
        try (FileOutputStream stream = new FileOutputStream(new File(out, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
    }

    private static void settle(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25));
            Thread.sleep(25);
        }
    }
}
