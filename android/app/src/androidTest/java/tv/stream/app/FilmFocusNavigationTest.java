package tv.stream.app;

import static org.junit.Assert.*;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import tv.stream.app.addon.Cancellation;
import tv.stream.app.addon.Title;
import tv.stream.app.ui.PageAdapter;

/** Remote movement across recycled film rows and horizontally scrolling Home shelves. */
public final class FilmFocusNavigationTest extends AppHarness {
    @Test public void returningFromDetailsThenMovingUpRestoresHomeSpotlight() throws Exception {
        try (BrowseRecoveryTest.CatalogServer server = new BrowseRecoveryTest.CatalogServer()) {
            try (Cancellation scope = services.addons.newRequest()) {
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", scope));
            }
            services.store.favourite(Title.parse(services.store.addons().get(0).id, server.title(0)), true);
            launch(); waitUntil("Home favourites", 10000, () -> findTagPrefix("favourite:") != null);
            browse.onActivity(a -> findPrefix(a.findViewById(R.id.page_rows), "favourite:").requestFocus());
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            waitUntil("Catalogue shelf", 5000, () -> !focusTag().startsWith("favourite:"));
            key(KeyEvent.KEYCODE_DPAD_CENTER); waitUntil("Film details", 10000, () -> visible("Play"));
            key(KeyEvent.KEYCODE_BACK); waitUntil("Home restored", 10000, () -> !visible("Play") && !focusTag().startsWith("nav:"));
            key(KeyEvent.KEYCODE_DPAD_UP);
            waitUntil("First shelf focused", 5000, () -> focusTag().startsWith("favourite:"));
            waitUntil("Spotlight returns to the top", 5000, () -> {
                AtomicReference<Boolean> top = new AtomicReference<>(false);
                browse.onActivity(a -> top.set(!a.findViewById(R.id.page_rows).canScrollVertically(-1)));
                return top.get();
            });
        }
    }
    @Test public void libraryGridAndHomeShelvesKeepFocusInsideTheirRows() throws Exception {
        try (BrowseRecoveryTest.CatalogServer server = new BrowseRecoveryTest.CatalogServer()) {
            try (Cancellation scope = services.addons.newRequest()) {
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", scope));
            }
            for (int i = 0; i < 13; i++) services.store.favourite(Title.parse(services.store.addons().get(0).id, server.title(i)), true);
            launch(); waitUntil("Home favourites", 10000, () -> visible("Favourites"));
            waitUntil("Home films", 10000, () -> findTagPrefix("favourite:") != null);
            browse.onActivity(a -> findPrefix(a.findViewById(R.id.page_rows), "favourite:").requestFocus());
            key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_RIGHT);
            String favourite = focusTag(); assertTrue(favourite.startsWith("favourite:"));
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            waitUntil("Down reaches catalogue shelf", 5000, () -> !focusTag().equals(favourite) && !focusTag().startsWith("nav:"));
            assertFalse("Focus leaves the favourites shelf", focusTag().startsWith("favourite:"));
            key(KeyEvent.KEYCODE_DPAD_UP);
            waitUntil("Up returns to the same favourite", 5000, () -> focusTag().equals(favourite));
            java.util.List<String> visited = new java.util.ArrayList<>(); visited.add(focusTag());
            for (int i = 0; i < 7; i++) {
                String previous = focusTag(); key(KeyEvent.KEYCODE_DPAD_RIGHT);
                waitUntil("Right reaches the next film", 5000, () -> !focusTag().equals(previous));
                visited.add(focusTag());
            }
            for (int i = 6; i >= 0; i--) {
                key(KeyEvent.KEYCODE_DPAD_LEFT);
                assertEquals("A scrolled shelf returns through every film", visited.get(i), focusTag());
            }
            browse.onActivity(a -> a.root("Library"));
            waitUntil("Library grid", 10000, () -> firstGrid() != null);
            AtomicReference<View> first = new AtomicReference<>(), second = new AtomicReference<>(), third = new AtomicReference<>();
            browse.onActivity(a -> {
                PageAdapter.GridRow row = findGrid(a.findViewById(R.id.page_rows));
                first.set(focusable(row.getChildAt(0))); second.set(focusable(row.getChildAt(1))); third.set(focusable(row.getChildAt(2)));
                second.get().requestFocus();
            });
            key(KeyEvent.KEYCODE_DPAD_LEFT); waitUntil("Left reaches the first film", 5000, () -> focused(first.get()));
            key(KeyEvent.KEYCODE_DPAD_RIGHT); waitUntil("Right returns to the second film", 5000, () -> focused(second.get()));
            browse.onActivity(a -> third.get().requestFocus());
            key(KeyEvent.KEYCODE_DPAD_DOWN);
            waitUntil("Down reaches the next grid row", 5000, () -> gridSlot() == 2 && !focused(third.get()));
            key(KeyEvent.KEYCODE_DPAD_UP); waitUntil("Up returns to the same column", 5000, () -> focused(third.get()));
            browse.onActivity(a -> first.get().requestFocus());
            key(KeyEvent.KEYCODE_DPAD_LEFT); waitUntil("Left at the first film reaches navigation", 5000, () -> focusTag().equals("nav:Library"));
        }
    }
    private void key(int code) { instrumentation.sendKeyDownUpSync(code); instrumentation.waitForIdleSync(); }
    private String focusTag() { AtomicReference<String> result = new AtomicReference<>(""); browse.onActivity(a -> { View v = a.getCurrentFocus(); if (v != null) result.set(String.valueOf(v.getTag())); }); return result.get(); }
    private boolean focused(View view) { AtomicReference<Boolean> result = new AtomicReference<>(false); browse.onActivity(a -> result.set(a.getCurrentFocus() == view)); return result.get(); }
    private View findTagPrefix(String prefix) { AtomicReference<View> result = new AtomicReference<>(); browse.onActivity(a -> result.set(findPrefix(a.findViewById(R.id.page_rows), prefix))); return result.get(); }
    private static View findPrefix(View view, String prefix) {
        if (view.isFocusable() && String.valueOf(view.getTag()).startsWith(prefix)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View found = findPrefix(((ViewGroup) view).getChildAt(i), prefix); if (found != null) return found; }
        return null;
    }
    private PageAdapter.GridRow firstGrid() { AtomicReference<PageAdapter.GridRow> result = new AtomicReference<>(); browse.onActivity(a -> result.set(findGrid(a.findViewById(R.id.page_rows)))); return result.get(); }
    private static PageAdapter.GridRow findGrid(View view) {
        if (view instanceof PageAdapter.GridRow) return (PageAdapter.GridRow) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { PageAdapter.GridRow found = findGrid(((ViewGroup) view).getChildAt(i)); if (found != null) return found; }
        return null;
    }
    private static View focusable(View view) {
        if (view.isFocusable()) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View found = focusable(((ViewGroup) view).getChildAt(i)); if (found != null) return found; }
        return null;
    }
    private int gridSlot() { AtomicReference<Integer> result = new AtomicReference<>(-1); browse.onActivity(a -> {
        for (View v = a.getCurrentFocus(); v != null && v.getParent() instanceof View; v = (View) v.getParent())
            if (v.getParent() instanceof PageAdapter.GridRow) { result.set(((PageAdapter.GridRow) v.getParent()).indexOfChild(v)); break; }
    }); return result.get(); }
}
