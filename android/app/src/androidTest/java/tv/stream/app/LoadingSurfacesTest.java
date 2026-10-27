package tv.stream.app;

import static org.junit.Assert.*;
import android.os.SystemClock;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import tv.stream.app.addon.Cancellation;
import tv.stream.app.ui.Item;

public final class LoadingSurfacesTest extends AppHarness {
    @Test public void onlyThePendingRemoteContentGetsSkeletonsAndLateResultsCannotReplaceLocalPages() throws Exception {
        try (BrowseRecoveryTest.CatalogServer server = new BrowseRecoveryTest.CatalogServer()) {
            server.homeGate = new CountDownLatch(1);
            try (Cancellation request = services.addons.newRequest()) {
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request));
            }
            launch(); waitUntil("remote Home is still loading", 5000, () -> skeleton("Home results"));
            assertFalse("Home does not pretend to be category blocks", skeleton("Catalogues"));
            tap("Settings", true); waitUntil("real Settings", 5000, () -> visible("Add-ons"));
            assertFalse("Local Settings has no ghost form", anySkeleton());
            tap("Library", false); waitUntil("real empty library", 5000, () -> visible("Your library is empty"));
            assertFalse("Empty Library is not represented by fake posters", anySkeleton());
            server.homeGate.countDown(); SystemClock.sleep(250);
            assertTrue("Late catalogue work keeps Library in place", visible("Your library is empty"));
            tap("Home", true); waitUntil("Home has actual posters", 10000, () -> visible("Film 00"));
            tap("Browse catalogues", true); waitUntil("local category index", 5000, () -> visible("Test films"));
            assertFalse("Category index reads the installed manifest, without a fake grid", anySkeleton());
        }
    }
    private boolean skeleton(String name) { return items().stream().anyMatch(item -> item.kind == Item.Kind.SKELETON && item.value.equals(name)); }
    private boolean anySkeleton() { return items().stream().anyMatch(item -> item.kind == Item.Kind.SKELETON); }
    @SuppressWarnings("unchecked") private List<Item> items() {
        AtomicReference<List<Item>> result = new AtomicReference<>(java.util.Collections.emptyList());
        browse.onActivity(activity -> {
            try {
                RecyclerView rows = activity.findViewById(R.id.page_rows);
                java.lang.reflect.Field data = rows.getAdapter().getClass().getDeclaredField("items"); data.setAccessible(true);
                result.set(new java.util.ArrayList<>((List<Item>) data.get(rows.getAdapter())));
            } catch (Exception e) { throw new AssertionError(e); }
        }); return result.get();
    }
}
