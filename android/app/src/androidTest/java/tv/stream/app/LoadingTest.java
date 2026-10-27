package tv.stream.app;

import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import tv.stream.app.addon.Cancellation;
import tv.stream.app.data.InstalledAddon;
import tv.stream.app.ui.Item;
import tv.stream.app.ui.PageAdapter;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class LoadingTest extends AppHarness {
    private PageAdapter adapter(MainActivity activity) {
        try { java.lang.reflect.Field f = MainActivity.class.getDeclaredField("adapter"); f.setAccessible(true); return (PageAdapter) f.get(activity); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    @SuppressWarnings("unchecked") private List<Item> items(MainActivity activity) {
        try { java.lang.reflect.Field f = PageAdapter.class.getDeclaredField("items"); f.setAccessible(true); return (List<Item>) f.get(adapter(activity)); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    @Test public void slowSourcesKeepHeaderAndBackAndCancelWithoutReplacingDetails() throws Exception {
        readyLibrary(); launch(); tap("Generated Film", false); waitUntil("details", 5000, () -> visible("Play"));
        for (InstalledAddon a : services.store.addons()) services.addons.remove(a.id);
        try (Cancellation request = services.addons.newRequest()) {
            String url = origin + "/addon/slow/loading/manifest.json";
            services.addons.install(url, services.addons.preview(url, request));
        }
        tap("Sources", false);
        waitUntil("source header", 5000, () -> visible("Back") && visible("Generated Film"));
        waitUntil("slow source rows need placeholders", 5000, () -> {
            AtomicBoolean skeleton = new AtomicBoolean();
            browse.onActivity(activity -> skeleton.set(items(activity).stream().anyMatch(item -> item.kind == Item.Kind.SKELETON)));
            return skeleton.get();
        });
        browse.onActivity(activity -> {
            boolean partial = false;
            for (Item item : items(activity)) if (item.kind == Item.Kind.SKELETON) {
                assertEquals("Only source rows load", "Source results", item.value); partial = true;
            }
            assertTrue("Slow source request is still loading", partial);
        });
        tap("Back", true); waitUntil("back to media", 5000, () -> visible("Play") && visible("Sources"));
    }
    @Test public void pointerOverVisiblePaginationDoesNotShiftPage() throws Exception {
        readyLibrary(); launch(); tap("Browse catalogues", false); tap("Generated films", false);
        browse.onActivity(activity -> {
            RecyclerView list = activity.findViewById(R.id.page_rows);
            list.scrollToPosition(adapter(activity).getItemCount() - 1);
        });
        android.os.SystemClock.sleep(500); instrumentation.waitForIdleSync();
        android.graphics.Rect bounds = new android.graphics.Rect(); int[] before = new int[2];
        browse.onActivity(activity -> {
            RecyclerView list = activity.findViewById(R.id.page_rows);
            View next = list.findViewWithTag("next-page:bottom"); assertNotNull(next);
            assertTrue(next.getGlobalVisibleRect(bounds));
            before[0] = ((androidx.recyclerview.widget.LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition();
            before[1] = list.getChildAt(0).getTop();
        });
        long time = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent.PointerProperties p = new android.view.MotionEvent.PointerProperties();
        p.id = 0; p.toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE;
        android.view.MotionEvent.PointerCoords c = new android.view.MotionEvent.PointerCoords();
        c.x = bounds.centerX(); c.y = bounds.centerY(); c.pressure = 1; c.size = 1;
        for (int action : new int[]{android.view.MotionEvent.ACTION_HOVER_MOVE, android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_CANCEL}) {
            android.view.MotionEvent event = android.view.MotionEvent.obtain(time, android.os.SystemClock.uptimeMillis(), action, 1,
                    new android.view.MotionEvent.PointerProperties[]{p}, new android.view.MotionEvent.PointerCoords[]{c}, 0,
                    action == android.view.MotionEvent.ACTION_DOWN ? android.view.MotionEvent.BUTTON_PRIMARY : 0,
                    1, 1, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0);
            assertTrue("Inject mouse action " + action, instrumentation.getUiAutomation().injectInputEvent(event, true)); event.recycle();
        }
        android.os.SystemClock.sleep(400); instrumentation.waitForIdleSync();
        browse.onActivity(activity -> {
            RecyclerView list = activity.findViewById(R.id.page_rows);
            assertEquals("Pointer retains the visible row", before[0], ((androidx.recyclerview.widget.LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition());
            assertEquals("Pointer doesn't move the content", before[1], list.getChildAt(0).getTop());
        });
    }

}
