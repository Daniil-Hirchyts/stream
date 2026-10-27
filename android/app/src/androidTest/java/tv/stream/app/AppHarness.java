package tv.stream.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import tv.stream.app.addon.*;
import tv.stream.playback.PlaybackSnapshot;
import static org.junit.Assert.*;

/** Real activities, real MotionEvents/keys and generated loopback media; an isolated database preserves owner data. */
public abstract class AppHarness {
    final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    final Context context = instrumentation.getTargetContext();
    TvApplication app;
    TvApplication.Services services;
    String database, origin;
    Map<String, ?> oldConfiguration;
    ActivityScenario<MainActivity> browse;
    @Before public void configure() throws Exception {
        android.accessibilityservice.AccessibilityServiceInfo info = instrumentation.getUiAutomation().getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        instrumentation.getUiAutomation().setServiceInfo(info);
        app = (TvApplication) context.getApplicationContext();
        SharedPreferences options = context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE);
        // An interrupted run leaves its overrides behind; never keep those as the configuration to restore.
        oldConfiguration = new java.util.HashMap<>(options.getAll());
        for (String key : new String[]{"database", "localOnly", "cacheBytes", "peer", "addonDirectory"}) oldConfiguration.remove(key);
        database = InstrumentationRegistry.getArguments().getString("database", "test-" + UUID.randomUUID() + ".db");
        assertTrue("Isolated test database", database.matches("test-[a-z0-9-]+\\.db"));
        origin = InstrumentationRegistry.getArguments().getString("fixtures", "http://127.0.0.1:58133");
        // The store's directory is the fixture service's, never Stremio's public one.
        options.edit().putString("database", database).putBoolean("localOnly", true).putLong("cacheBytes", 16L << 20)
                .putString("peer", InstrumentationRegistry.getArguments().getString("seeder", "127.0.0.1:58134"))
                .putString("addonDirectory", origin + "/addon/good/directory/manifest.json").commit();
        app.resetServices(); services = app.services();
    }
    @After public void cleanup() throws Exception {
        if (browse != null) browse.close();
        instrumentation.runOnMainSync(() -> {
            for (Stage stage : new Stage[]{Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED})
                for (Activity activity : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)))
                    if (activity instanceof MainActivity || activity instanceof WatchActivity) activity.finish();
        });
        instrumentation.waitForIdleSync();
        waitUntil("native payload cleanup", 10_000, () -> { java.io.File[] files = new java.io.File(context.getCacheDir(), "payload").listFiles(); return files == null || files.length == 0; });
        app.persistence.submit(() -> {}).get();
        for (String id : services.store.importIds()) services.imports.remove(id);
        app.resetServices(); context.deleteDatabase(database); context.deleteSharedPreferences(RuntimeOptions.playerPreferences(database));
        SharedPreferences.Editor editor = context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE).edit().clear();
        for (Map.Entry<String, ?> e : oldConfiguration.entrySet()) {
            Object v = e.getValue(); if (v instanceof String) editor.putString(e.getKey(), (String) v); else if (v instanceof Boolean) editor.putBoolean(e.getKey(), (Boolean) v); else if (v instanceof Long) editor.putLong(e.getKey(), (Long) v); else if (v instanceof Integer) editor.putInt(e.getKey(), (Integer) v);
        }
        editor.commit();
    }
    void launch() { browse = ActivityScenario.launch(MainActivity.class); }
    void readyLibrary() throws Exception {
        readyLibrary("ui");
    }
    void readyLibrary(String configuration) throws Exception {
        try (Cancellation scope = services.addons.newRequest()) {
            String url = origin + "/addon/good/" + configuration + "/manifest.json"; services.addons.install(url, services.addons.preview(url, scope));
        }
    }
    WatchActivity watch() {
        AtomicReference<WatchActivity> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) if (a instanceof WatchActivity) result.set((WatchActivity) a); });
        return result.get();
    }
    PlaybackSnapshot state() {
        AtomicReference<PlaybackSnapshot> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) if (a instanceof WatchActivity) result.set(((WatchActivity) a).playbackSnapshot()); });
        return result.get();
    }
    JSONObject diagnostics() {
        AtomicReference<JSONObject> result = new AtomicReference<>(new JSONObject());
        instrumentation.runOnMainSync(() -> { for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) if (a instanceof WatchActivity) result.set(((WatchActivity) a).diagnosticSnapshot()); }); return result.get();
    }
    static void waitUntil(String message, long timeout, BooleanSupplier condition) {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end) { if (condition.getAsBoolean()) return; SystemClock.sleep(100); }
        try {
            android.graphics.Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if (image != null) { try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(), "validation-failure.png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } image.recycle(); }
        } catch (Exception ignored) { /* keep the original assertion */ }
        fail("Timed out: " + message);
    }
    boolean visible(String label) { return findWindow(label) != null; }
    private AccessibilityNodeInfo findWindow(String label) {
        for (android.view.accessibility.AccessibilityWindowInfo window : instrumentation.getUiAutomation().getWindows()) {
            if (!window.isFocused()) continue;
            AccessibilityNodeInfo result = find(window.getRoot(), label); if (result != null) return result;
        }
        return null;
    }
    AccessibilityNodeInfo find(AccessibilityNodeInfo root, String label) {
        if (root == null) return null;
        if (root.isVisibleToUser() && (label.equalsIgnoreCase(root.getText() == null ? "" : root.getText().toString()) || label.equalsIgnoreCase(root.getContentDescription() == null ? "" : root.getContentDescription().toString()))) return root;
        for (int i = 0; i < root.getChildCount(); i++) { AccessibilityNodeInfo result = find(root.getChild(i), label); if (result != null) return result; }
        return null;
    }
    void enter(String text) {
        android.os.Bundle args = new android.os.Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        AccessibilityNodeInfo field = editable(instrumentation.getUiAutomation().getRootInActiveWindow());
        if (field == null) for (android.view.accessibility.AccessibilityWindowInfo window : instrumentation.getUiAutomation().getWindows()) { field = editable(window.getRoot()); if (field != null) break; }
        assertNotNull("Editable dialog", field); assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
        instrumentation.waitForIdleSync();
    }
    private AccessibilityNodeInfo editable(AccessibilityNodeInfo root) {
        if (root == null) return null; if (root.isEditable()) return root;
        for (int i = 0; i < root.getChildCount(); i++) { AccessibilityNodeInfo result = editable(root.getChild(i)); if (result != null) return result; }
        return null;
    }
    void row(String key, boolean mouse) {
        Rect bounds = new Rect(); browse.onActivity(activity -> { android.view.View v = activity.findViewById(R.id.page_rows).findViewWithTag(key); assertNotNull("Row " + key, v); assertTrue(v.getGlobalVisibleRect(bounds)); });
        pointer(bounds.centerX(), bounds.centerY(), mouse);
    }
    void tap(String label, boolean mouse) {
        long end = SystemClock.elapsedRealtime() + 30_000;
        while (!visible(label) && SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(500);
            if (!visible(label)) {
                boolean moved = false;
                for (android.view.accessibility.AccessibilityWindowInfo window : instrumentation.getUiAutomation().getWindows()) if (window.isFocused()) moved |= scroll(window.getRoot());
                if (!moved || !visible(label)) swipeBrowse();
            }
        }
        if (!visible(label)) waitUntil("visible " + label, 100, () -> visible(label));
        waitUntil("target " + label, 5000, () -> targetNode(label) != null);
        AccessibilityNodeInfo node = targetNode(label);
        if (node != null && node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId())) {
            SystemClock.sleep(150); instrumentation.waitForIdleSync();
            waitUntil("settled " + label, 5000, () -> targetNode(label) != null); node = targetNode(label);
        }
        assertNotNull("Clickable " + label, node); Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        // IME/dialog transitions move buttons after accessibility first exposes them. Wait for stable geometry.
        long stable = SystemClock.elapsedRealtime(), endGeometry = stable + 3000;
        while (SystemClock.elapsedRealtime() < endGeometry && SystemClock.elapsedRealtime() - stable < 400) {
            SystemClock.sleep(75); AccessibilityNodeInfo next = targetNode(label);
            if (next == null) { stable = SystemClock.elapsedRealtime(); continue; } Rect rectangle = new Rect(); next.getBoundsInScreen(rectangle);
            if (!bounds.equals(rectangle)) { bounds.set(rectangle); stable = SystemClock.elapsedRealtime(); }
        }
        pointer(bounds.centerX(), bounds.centerY(), mouse);
    }
    private AccessibilityNodeInfo targetNode(String label) {
        // A shelf heading can have the same text as its card. Only match labels inside an actual click target,
        // carrying the ancestor from this snapshot instead of following potentially recycled parent pointers.
        for (android.view.accessibility.AccessibilityWindowInfo window : instrumentation.getUiAutomation().getWindows()) {
            if (!window.isFocused()) continue;
            AccessibilityNodeInfo target = clickableLabel(window.getRoot(), label, null);
            if (target != null) return target;
        }
        return null;
    }
    private AccessibilityNodeInfo clickableLabel(AccessibilityNodeInfo node, String label, AccessibilityNodeInfo ancestor) {
        if (node == null || !node.isVisibleToUser()) return null;
        AccessibilityNodeInfo target = node.isClickable() ? node : ancestor;
        boolean matches = label.equalsIgnoreCase(node.getText() == null ? "" : node.getText().toString())
                || label.equalsIgnoreCase(node.getContentDescription() == null ? "" : node.getContentDescription().toString());
        if (matches && target != null && target.isEnabled()) return target;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = clickableLabel(node.getChild(i), label, target);
            if (found != null) return found;
        }
        return null;
    }
    private void swipeBrowse() {
        if (browse == null) return; Rect bounds = new Rect();
        browse.onActivity(activity -> { if (activity.hasWindowFocus()) activity.findViewById(R.id.page_rows).getGlobalVisibleRect(bounds); });
        if (bounds.isEmpty()) return;
        float x = bounds.right - 30, from = bounds.bottom - 60, to = bounds.top + 60;
        long time = SystemClock.uptimeMillis();
        for (int i = 0; i <= 10; i++) {
            MotionEvent event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), i == 0 ? MotionEvent.ACTION_DOWN : i == 10 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, x, from + (to - from) * i / 10, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN); assertTrue(instrumentation.getUiAutomation().injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(25);
        }
    }
    private boolean scroll(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (scroll(node.getChild(i))) return true;
        return false;
    }
    void pointer(float x, float y, boolean mouse) {
        long time = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties(); p.id = 0; p.toolType = mouse ? MotionEvent.TOOL_TYPE_MOUSE : MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords(); c.x = x; c.y = y; c.pressure = 1; c.size = 1;
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent e = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, 1, new MotionEvent.PointerProperties[]{p}, new MotionEvent.PointerCoords[]{c}, 0, mouse && action == MotionEvent.ACTION_DOWN ? MotionEvent.BUTTON_PRIMARY : 0, 1, 1, 0, 0, mouse ? InputDevice.SOURCE_MOUSE : InputDevice.SOURCE_TOUCHSCREEN, 0);
            assertTrue(instrumentation.getUiAutomation().injectInputEvent(e, true)); e.recycle();
        }
        instrumentation.waitForIdleSync();
        try { instrumentation.getUiAutomation().waitForIdle(100, 2000); } catch (java.util.concurrent.TimeoutException ignored) { /* state assertions still decide success */ }
    }
    void control(int id, boolean mouse) {
        WatchActivity activity = watch(); assertNotNull(activity); Rect bounds = new Rect();
        instrumentation.runOnMainSync(() -> { assertTrue(activity.findViewById(id).getGlobalVisibleRect(bounds)); }); pointer(bounds.centerX(), bounds.centerY(), mouse);
    }
    /** Play is automatic; Sources still exercises explicit pointer selection when that page is open. */
    void chooseFirstSource() {
        Rect bounds = new Rect();
        waitUntil("playable source row", 30000, () -> {
            if (watch() != null) return true;
            bounds.setEmpty(); browse.onActivity(activity -> sourceBounds(activity.findViewById(R.id.page_rows), bounds));
            return !bounds.isEmpty();
        });
        if (!bounds.isEmpty()) pointer(bounds.centerX(), bounds.centerY(), true);
    }
    private boolean sourceBounds(android.view.View view, Rect bounds) {
        if (view.isClickable() && String.valueOf(view.getTag()).startsWith("source:") && view.getGlobalVisibleRect(bounds)) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (sourceBounds(group.getChildAt(i), bounds)) return true;
        }
        return false;
    }
    /** Exercise the player's internal automatic-recovery mode directly; normal Play uses this same automatic mode. */
    void startAutomaticFixturePlayback() throws Exception {
        Title title = Title.parse(services.store.addons().get(0).id, new JSONObject()
                .put("id", "fixture:film").put("type", "movie").put("name", "Generated Film"));
        instrumentation.runOnMainSync(() -> context.startActivity(WatchActivity.intent(context, title, title.id, "").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }
    void playFilm() throws Exception {
        readyLibrary(); launch(); tap("Generated Film", false); tap("Play", true); chooseFirstSource();
        waitUntil("first frame and advancing playback", 60_000, () -> state() != null && state().renderedFirstFrame && state().positionMs >= 2000);
    }
}
