package tv.stream.app;

import static org.junit.Assert.*;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.Test;
import tv.stream.app.addon.Cancellation;
import tv.stream.app.addon.Title;

/** Record every focus change, not just the final restored key after an in-place update. */
public final class InPlaceFocusTest extends AppHarness {
    @Test public void favouriteAndWatchedButtonsKeepTheSameFocusedViewForRemoteAndPointer() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        Title film = Title.parse("local", new JSONObject().put("id", "focus-film").put("type", "movie").put("name", "Focus film"));
        browse.onActivity(activity -> activity.openTitle(film, ""));
        waitUntil("film", 5000, () -> visible("Add favourite"));
        toggle("favourite", null, () -> visible("Remove favourite"));
        toggle("favourite", "Remove favourite", () -> visible("Add favourite"));
        assertTrue(services.store.library(true).isEmpty());
        toggle("watched", null, () -> visible("Mark unwatched"));
        toggle("watched", "Mark unwatched", () -> visible("Mark watched"));
        assertFalse(services.store.progress(film.key(), film.id).watched);
    }

    @Test public void settingsTogglesKeepTheirPaneAndFocusedRowAttached() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        tap("Settings", true); tap("Playback", true);
        toggle("interface-sounds", null, () -> services.store.preference("interfaceSounds", "true").equals("false"));
        toggle("interface-sounds", "Interface sounds", () -> services.store.preference("interfaceSounds", "false").equals("true"));
        toggle("autoplay", null, () -> services.store.preference("autoplay", "true").equals("false"));
        toggle("autoplay", "Play next episode", () -> services.store.preference("autoplay", "false").equals("true"));
        assertTrue("Still in Playback", visible("Playback cache"));
    }

    @Test public void addonEnableAndCatalogueVisibilityDoNotHandFocusToTheirFirstRow() throws Exception {
        try (BrowseRecoveryTest.CatalogServer server = new BrowseRecoveryTest.CatalogServer()) {
            try (Cancellation request = services.addons.newRequest()) {
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request));
            }
            String id = services.store.addons().get(0).id;
            launch(); waitUntil("Home", 10000, () -> visible("Film 00"));
            browse.onActivity(activity -> { Route route = new Route("Add-on"); route.value = id; activity.push(route); });
            waitUntil("add-on", 5000, () -> visible("Disable add-on"));
            toggle("enable", null, () -> !services.store.addons().get(0).enabled);
            toggle("enable", "Enable add-on", () -> services.store.addons().get(0).enabled);
            toggle("visibility:moviebrowse", null, () -> !services.store.catalogVisible(id, "movie", "browse"));
            toggle("visibility:moviebrowse", null, () -> services.store.catalogVisible(id, "movie", "browse"));
        }
    }

    private void toggle(String key, String pointerLabel, BooleanSupplier changed) {
        AtomicReference<View> original = new AtomicReference<>();
        AtomicReference<ViewTreeObserver.OnGlobalFocusChangeListener> observer = new AtomicReference<>();
        List<String> changes = new ArrayList<>();
        browse.onActivity(activity -> {
            View control = activity.findViewById(R.id.page_rows).findViewWithTag(key);
            assertNotNull("Control " + key, control); control.requestFocus(); original.set(control);
        });
        SystemClock.sleep(300);
        browse.onActivity(activity -> {
            observer.set((before, after) -> { if (after != original.get()) changes.add(after == null ? "none" : String.valueOf(after.getTag())); });
            activity.findViewById(R.id.page_rows).getViewTreeObserver().addOnGlobalFocusChangeListener(observer.get());
        });
        if (pointerLabel == null) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        else tap(pointerLabel, true);
        waitUntil("toggle saved: " + key, 5000, changed);
        SystemClock.sleep(400); // Include both progressive/final renders and delayed restoration callbacks.
        browse.onActivity(activity -> {
            View rows = activity.findViewById(R.id.page_rows);
            rows.getViewTreeObserver().removeOnGlobalFocusChangeListener(observer.get());
            assertSame("Focusable surface survives: " + key, original.get(), rows.findViewWithTag(key));
            assertSame("Focus stays on " + key, original.get(), activity.getCurrentFocus());
            assertTrue("No intermediate focus jumps: " + changes, changes.isEmpty());
        });
    }
}
