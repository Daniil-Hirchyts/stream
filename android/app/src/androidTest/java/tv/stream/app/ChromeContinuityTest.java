package tv.stream.app;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextClock;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import tv.stream.app.addon.Title;
import tv.stream.app.ui.IntroView;
import tv.stream.app.ui.Item;
import tv.stream.app.ui.Page;

@RunWith(AndroidJUnit4.class)
public final class ChromeContinuityTest extends AppHarness {
    @Test public void browseClockLoadingAndCircularActions() throws Exception {
        readyLibrary(); launch(); waitUntil("home", 10000, () -> visible("Generated Film"));
        Rect clock = clockBounds(R.id.clock);
        browse.onActivity(activity -> {
            View tools = activity.findViewById(R.id.home_tools); Rect bounds = new Rect(); tools.getGlobalVisibleRect(bounds);
            assertTrue("Refresh sits left of the clock", bounds.right < clock.left);
            assertFalse("Default Home has no Back button", activity.findViewById(R.id.page_back).isShown());
        });
        tap("Refresh", true); SystemClock.sleep(100);
        browse.onActivity(activity -> {
            ViewGroup refresh = activity.findViewById(R.id.home_refresh);
            assertTrue("Refresh icon rotates on click", refresh.getChildAt(0).getRotation() > 0);
        });
        waitUntil("refreshed home", 10000, () -> visible("Generated Film"));
        tap("Generated Film", true); waitUntil("film", 10000, () -> visible("Play"));
        assertEquals(clock, clockBounds(R.id.clock));
        browse.onActivity(activity -> {
            for (String key : new String[]{"favourite", "watched"}) {
                View button = activity.findViewById(R.id.page_rows).findViewWithTag(key);
                assertNotNull(button);
                assertEquals("Icon buttons have a circular footprint", button.getHeight(), button.getWidth());
                assertEquals(dp(44), button.getWidth());
                assertFalse("Icon buttons keep accessible action labels", button.getContentDescription().toString().isEmpty());
            }
        });
        tap("Add favourite", true); waitUntil("favourite set", 5000, () -> visible("Remove favourite"));
        browse.onActivity(activity -> assertTrue(activity.findViewById(R.id.page_rows).findViewWithTag("favourite").isActivated()));
        tap("Remove favourite", false); waitUntil("favourite cleared", 5000, () -> visible("Add favourite"));
        tap("Mark watched", false);
        waitUntil("watched", 5000, () -> visible("Mark unwatched"));
        browse.onActivity(activity -> assertTrue(activity.findViewById(R.id.page_rows).findViewWithTag("watched").isActivated()));
        screenshot("chrome-circular-actions.png");
        tap("Back", true); waitUntil("home restored", 5000, () -> visible("Browse"));
        browse.onActivity(activity -> invoke(activity, "loading"));
        browse.onActivity(activity -> assertTrue("Navigation loading keeps the clock steady", activity.findViewById(R.id.clock).isShown()));
        browse.onActivity(MainActivity::refresh);
        waitUntil("browse clock returns", 10000, () -> clockShown(R.id.clock));
        // Preview the real intro view without enabling starter providers in the isolated harness.
        final IntroView[] intro = new IntroView[1];
        browse.onActivity(activity -> {
            intro[0] = new IntroView(activity);
            FrameLayout root = activity.findViewById(R.id.browse_root);
            root.addView(intro[0], new FrameLayout.LayoutParams(-1, -1));
            setIntro(activity, intro[0]);
            invoke(activity, "updateClock");
            assertFalse("Startup hides the clock", activity.findViewById(R.id.clock).isShown());
        });
        SystemClock.sleep(950);
        for (int i = 0; i < 5; i++) { screenshot("chrome-intro-" + i + ".png"); SystemClock.sleep(140); }
        browse.onActivity(activity -> {
            if (intro[0].getParent() instanceof ViewGroup) ((ViewGroup) intro[0].getParent()).removeView(intro[0]);
            setIntro(activity, null);
            invoke(activity, "updateClock");
            assertTrue("Browse clock returns after startup", activity.findViewById(R.id.clock).isShown());
        });
    }

    @Test public void backRestoresTheExactShelfAndCardWithoutReloading() throws Exception {
        readyLibrary(); launch(); waitUntil("home", 10000, () -> visible("Generated Film"));
        String provider = services.store.addons().get(0).id;
        Title film = Title.parse(provider, new JSONObject().put("id", "fixture:film").put("type", "movie").put("name", "Generated Film"));
        browse.onActivity(activity -> {
            Page page = new Page();
            for (int row = 0; row < 2; row++) {
                List<Item.Tile> cards = new ArrayList<>();
                for (int i = 0; i < 18; i++) cards.add(new Item.Tile("row" + row + ":" + i, Item.Tile.Style.POSTER,
                        "Generated " + i, "2026", null, film, () -> activity.openTitle(film, "")));
                page.add(Item.shelf("saved-shelf:" + row, "Shelf " + row, "", Item.Tile.Style.POSTER, cards));
            }
            try { java.lang.reflect.Method show = MainActivity.class.getDeclaredMethod("show", Page.class); show.setAccessible(true); show.invoke(activity, page); }
            catch (Exception e) { throw new AssertionError(e); }
        });
        instrumentation.waitForIdleSync();
        browse.onActivity(activity -> {
            RecyclerView page = activity.findViewById(R.id.page_rows); page.scrollBy(0, 320);
            View card = page.findViewWithTag("row1:0"); assertNotNull(card);
            View parent = card;
            while (!(parent instanceof RecyclerView)) parent = (View) parent.getParent();
            RecyclerView shelf = (RecyclerView) parent;
            ((LinearLayoutManager) shelf.getLayoutManager()).scrollToPositionWithOffset(6, 80);
        });
        instrumentation.waitForIdleSync();
        browse.onActivity(activity -> activity.findViewById(R.id.page_rows).findViewWithTag("row1:7").requestFocus());
        SystemClock.sleep(400);
        Rect before = new Rect();
        browse.onActivity(activity -> assertTrue(activity.getCurrentFocus().getGlobalVisibleRect(before)));
        pointer(before.centerX(), before.centerY(), true);
        waitUntil("details", 10000, () -> visible("Play"));
        // A re-fetch would replace these injected shelves with fixture Home; Back must use the retained page.
        tap("Back", false);
        waitUntil("same card focus", 5000, () -> focused("row1:7"));
        SystemClock.sleep(1500);
        browse.onActivity(activity -> {
            View selected = activity.getCurrentFocus(); assertEquals("No background reload steals focus", "row1:7", selected.getTag());
            Rect after = new Rect(); assertTrue(selected.getGlobalVisibleRect(after));
            assertEquals("Same horizontal position", before.left, after.left, 2);
            assertEquals("Same vertical position", before.top, after.top, 2);
        });
        screenshot("chrome-restored-card.png");
    }

    @Test public void playerClockOnlyShowsForPausedControlsAndMenus() throws Exception {
        playFilm();
        WatchActivity activity = watch();
        assertFalse("Active playback hides the clock", clockShown(R.id.player_clock));
        SystemClock.sleep(4500);
        instrumentation.runOnMainSync(() -> assertFalse("Playback controls have faded", activity.findViewById(R.id.controls).isShown()));
        assertFalse("Uninterrupted playback stays clear", clockShown(R.id.player_clock));
        control(tv.stream.playback.R.id.player_view, false); control(R.id.progress, true);
        waitUntil("paused", 5000, () -> !state().playWhenReady);
        waitUntil("paused clock", 5000, () -> clockShown(R.id.player_clock));
        Rect initial = clockBounds(R.id.player_clock);
        assertEquals("Fixed right inset", context.getResources().getDisplayMetrics().widthPixels - dp(48), initial.right);
        assertEquals("Fixed top inset", dp(22), initial.top);
        instrumentation.runOnMainSync(() -> {
            Rect button = new Rect(); activity.findViewById(R.id.control_settings).getGlobalVisibleRect(button);
            assertTrue("Player actions sit left of the clock", button.right < initial.left);
        });
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU);
        waitUntil("player menu", 5000, () -> visible("Settings"));
        assertEquals(initial, clockBounds(R.id.player_clock));
        SystemClock.sleep(400);
        screenshot("chrome-player-menu-clock.png");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY);
        waitUntil("resumed clock hidden", 5000, () -> state().playWhenReady && !clockShown(R.id.player_clock));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU);
        waitUntil("clock appears in menu during playback", 5000, () -> clockShown(R.id.player_clock));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU);
        waitUntil("closing menu clears active picture", 5000, () -> !clockShown(R.id.player_clock));
    }
    private void invoke(MainActivity activity, String name) {
        try { java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(activity); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private void setIntro(MainActivity activity, IntroView intro) {
        try { java.lang.reflect.Field field = MainActivity.class.getDeclaredField("intro"); field.setAccessible(true); field.set(activity, intro); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private boolean clockShown(int id) {
        AtomicBoolean result = new AtomicBoolean();
        WatchActivity playing = watch();
        if (playing != null) instrumentation.runOnMainSync(() -> result.set(playing.findViewById(id).isShown()));
        else browse.onActivity(activity -> result.set(activity.findViewById(id).isShown()));
        return result.get();
    }
    @Test public void introShineReachesTheWholeInteriorIncludingTheTip() throws Exception {
        IntroView intro = new IntroView(context);
        java.lang.reflect.Field started = IntroView.class.getDeclaredField("started"); started.setAccessible(true);
        long origin = started.getLong(intro);
        java.lang.reflect.Method draw = IntroView.class.getDeclaredMethod("drawMark", android.graphics.Canvas.class,
                long.class, float.class, float.class, float.class, float.class, float.class);
        draw.setAccessible(true);
        Bitmap bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        float[][] points = {{42.5f, 44}, {42.5f, 64}, {48, 41}, {48, 66}, {55, 54}, {68.5f, 54}};
        int[] baseline = new int[points.length], brightest = new int[points.length];
        try {
            bitmap.eraseColor(android.graphics.Color.BLACK);
            draw.invoke(intro, canvas, origin + 3000, 0f, 256f, 256f, 1080f, 1f);
            for (int i = 0; i < points.length; i++) baseline[i] = brightness(bitmap, points[i]);
            for (int age = 900; age <= 2020; age += 20) {
                bitmap.eraseColor(android.graphics.Color.BLACK);
                draw.invoke(intro, canvas, origin + age, 0f, 256f, 256f, 1080f, 1f);
                for (int i = 0; i < points.length; i++) brightest[i] = Math.max(brightest[i], brightness(bitmap, points[i]));
            }
            for (int i = 0; i < points.length; i++) assertTrue("Shine reaches interior sample " + i + " including the tip",
                    brightest[i] >= baseline[i] + 25);
        } finally { bitmap.recycle(); }
    }
    private int brightness(Bitmap bitmap, float[] point) {
        int color = bitmap.getPixel(Math.round(256 + (point[0] - 56.75f) * 10), Math.round(256 + (point[1] - 54) * 10));
        return android.graphics.Color.red(color);
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private boolean focused(String key) {
        AtomicBoolean found = new AtomicBoolean();
        browse.onActivity(activity -> found.set(activity.getCurrentFocus() != null && key.equals(activity.getCurrentFocus().getTag())));
        return found.get();
    }
    private Rect clockBounds(int id) {
        Rect result = new Rect();
        WatchActivity playing = watch();
        if (playing != null) instrumentation.runOnMainSync(() -> { TextClock clock = playing.findViewById(id); assertNotNull(clock); assertTrue(clock.getGlobalVisibleRect(result)); });
        else browse.onActivity(activity -> { TextClock clock = activity.findViewById(id); assertTrue(clock.getGlobalVisibleRect(result)); });
        return result;
    }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        bitmap.recycle();
    }
}
