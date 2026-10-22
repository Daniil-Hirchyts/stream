package tv.daniil.stream;

import static org.junit.Assume.assumeTrue;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import tv.daniil.stream.addon.BuiltinAddons;
import tv.daniil.stream.addon.Cancellation;
import tv.daniil.stream.addon.Title;

/**
 * Renders the real browse screens at 1920×1080 (TV, xhdpi) into PNGs for design review: real activity, layouts,
 * focus and artwork, with live Cinemeta and Internet Archive data. Not an assertion suite; run with
 * {@code ./gradlew :app:testDebugUnitTest --tests '*UiSnapshotTest' -PuiSnapshots=true}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class UiSnapshotTest {
    private File out;
    private MainActivity activity;

    @BeforeClass public static void enabled() { assumeTrue("UI snapshots run with -PuiSnapshots=true", Boolean.getBoolean("dtv.snapshots")); }
    @Before public void keystore() {
        FakeKeyStore.install();
        out = new File(System.getProperty("dtv.snapshotDir", "build/ui-snapshots"));
        out.mkdirs();
    }

    @Test public void browseScreens() throws Exception {
        TvApplication app = ApplicationProvider.getApplicationContext();
        TvApplication.Services services = app.services();
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        settle(3000);
        snap("00-home-empty"); reachable("00-home-empty");
        // The real first run: an empty Home leads to the store's starter page, whose Install adds them from live manifests.
        click("Get add-ons"); settle(2000);
        snap("00b-starters"); reachable("00b-starters");
        click("Install");
        waitFor(() -> services.store.addons().size() >= 3, 30_000);
        settle(2000);
        StringBuilder names = new StringBuilder();
        for (tv.daniil.stream.data.InstalledAddon a : services.store.addons()) names.append(a.manifest.name).append("; ");
        System.out.println("starter add-ons: " + names);

        Title film, series;
        try (Cancellation scope = services.addons.newRequest()) {
            String cinemeta = services.store.addons().get(0).id;
            film = services.addons.metadata(cinemeta, "movie", "tt0063350", scope).items.get(0);
            series = services.addons.metadata(cinemeta, "series", "tt0052520", scope).items.get(0);
        }
        services.store.progress(film, film.id, 41 * 60_000, 96 * 60_000, false);
        services.store.progress(series, series.episodes.get(3).id, 12 * 60_000, 25 * 60_000, false);
        services.store.favourite(film, true);

        activity.root("Home"); settle(9000);
        snap("01-home"); reachable("01-home");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_RIGHT); settle(2500);
        snap("02-home-focus-moved");
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN); settle(3000);
        snap("03-home-scrolled");
        for (int i = 0; i < 6; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        settle(3000);
        snap("03b-home-bottom");

        activity.openTitle(film, ""); settle(6000);
        snap("04-details-film"); reachable("04-details-film");
        for (int i = 0; i < 2; i++) key(KeyEvent.KEYCODE_DPAD_RIGHT);
        settle(1000);
        snap("04b-details-buttons");
        activity.openTitle(series, ""); settle(6000);
        snap("05-details-series"); reachable("05-details-series");
        for (int i = 0; i < 3; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        settle(3000);
        snap("05b-series-episodes");
        activity.sources(film, film.id); settle(3000);
        waitFor(() -> activity.getWindow().getDecorView().findFocus() != null, 40_000); settle(1000);
        snap("06-sources"); reachable("06-sources");

        activity.root("Library"); settle(4000);
        snap("07-library"); reachable("07-library");
        for (int i = 0; i < 3; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        settle(2000);
        snap("07b-library-scrolled");
        activity.root("Add-ons"); settle(9000);
        snap("08-store"); reachable("08-store");
        for (int i = 0; i < 8; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        settle(2000);
        snap("08b-store-scrolled");
        Route review = new Route("Add-on review"); review.value = BuiltinAddons.INTERNET_ARCHIVE;
        activity.push(review); settle(3000);
        snap("09-store-review"); reachable("09-store-review");
        activity.root("Settings"); settle(3000);
        snap("10-settings"); reachable("10-settings");
        click("Languages"); settle(800);
        snap("10a-settings-languages");
        click("Audio language"); settle(1500);
        snapDialog("10b-settings-choice");
        ShadowDialog.getLatestDialog().dismiss(); settle(500);
        activity.alert("Playback diagnostics", String.join(" ", java.util.Collections.nCopies(90, "A long message scrolls inside the alert.")));
        settle(1000);
        snapDialog("10c-long-alert");
        ShadowDialog.getLatestDialog().dismiss(); settle(500);
        // Left returns to the lit category; Down walks the categories and the pane follows.
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        for (int i = 0; i < 2; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        settle(1200);
        snap("10d-settings-playback"); reachable("10d-settings-playback");
        activity.search(); settle(2000);
        snap("11-search"); reachable("11-search");
        click("Show 123 keys"); settle(800);
        snap("11b-search-symbols");
        click("Show àéü keys"); settle(800);
        snap("11c-search-accents");
        // Typing re-renders the page; the keyboard stays on the page being typed from.
        click("Type é"); settle(1500);
        if (activity.getWindow().getDecorView().findViewWithTag("keyboard:é") == null) throw new AssertionError("Keyboard left the accents page");
        click("Clear search"); click("Show abc keys"); settle(800);
        activity.searchInput("night"); settle(7000);
        snap("12-search-results"); reachable("12-search-results");
        for (int i = 0; i < 7; i++) key(KeyEvent.KEYCODE_DPAD_RIGHT);
        for (int i = 0; i < 3; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        settle(1500);
        snap("12b-search-results-scrolled");
        activity.openImports(); settle(2500);
        snap("14-imports"); reachable("14-imports");
        controller.pause().stop().destroy();
        if (!unreachable.isEmpty()) throw new AssertionError("Controls the remote can't reach:\n" + String.join("\n", unreachable));
    }

    private final List<String> unreachable = new ArrayList<>();
    /**
     * Every control on screen must be reachable with the D-pad alone: a breadth-first walk of focus search in the four
     * directions from the current focus must arrive at each visible clickable view, and each one must be focusable.
     */
    private void reachable(String page) {
        View decor = activity.getWindow().getDecorView();
        View start = decor.findFocus();
        if (start == null) { unreachable.add(page + ": nothing has focus"); return; }
        java.util.Set<View> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        seen.add(start); queue.add(start);
        int[] directions = {View.FOCUS_LEFT, View.FOCUS_RIGHT, View.FOCUS_UP, View.FOCUS_DOWN};
        while (!queue.isEmpty() && seen.size() < 800) {
            View view = queue.poll();
            for (int direction : directions) { View next = view.focusSearch(direction); if (next != null && seen.add(next)) queue.add(next); }
        }
        int before = unreachable.size(), controls = check(page, decor, seen);
        System.out.println("remote " + page + ": " + controls + " controls, " + seen.size() + " reached, " + (unreachable.size() - before) + " problems");
    }
    private int check(String page, View view, java.util.Set<View> seen) {
        if (!view.isShown()) return 0;
        int count = 0;
        android.graphics.Rect visible = new android.graphics.Rect();
        // The Home spotlight is a touch shortcut to the card that already has focus; everything else must take focus.
        if (view.isClickable() && !"spotlight".equals(view.getTag()) && view.getGlobalVisibleRect(visible) && !visible.isEmpty()) {
            count++;
            String name = view.getTag() + " / " + view.getContentDescription();
            if (!view.isFocusable()) unreachable.add(page + ": touch only: " + name);
            else if (!seen.contains(view)) unreachable.add(page + ": unreachable: " + name);
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += check(page, group.getChildAt(i), seen);
        }
        return count;
    }

    /**
     * The player overlay (Stream's override of the shared player's layers) over a real film still: playing with the
     * controls up and Play/Pause focused, then the error card. Inflated directly with the same views WatchActivity uses.
     */
    @Test public void playerOverlay() throws Exception {
        android.app.Activity host = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        host.setTheme(R.style.TvTheme);
        android.widget.FrameLayout root = new android.widget.FrameLayout(host);
        root.setBackgroundColor(0xFF000000);
        android.widget.ImageView still = new android.widget.ImageView(host);
        still.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        try (tv.daniil.stream.addon.AddonClient client = new tv.daniil.stream.addon.AddonClient()) {
            byte[] image = client.fetch("https://images.metahub.space/background/medium/tt0063350/img", 8 << 20, new Cancellation(),
                    tv.daniil.stream.addon.AddonClient.deadline());
            still.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(image, 0, image.length));
        }
        root.addView(still, new android.widget.FrameLayout.LayoutParams(-1, -1));
        android.view.LayoutInflater.from(host).inflate(R.layout.playback_layers, root, true);
        host.setContentView(root);
        root.findViewById(R.id.controls).setVisibility(View.VISIBLE);
        ((android.widget.TextView) root.findViewById(R.id.controls_title)).setText("Night of the Living Dead");
        android.widget.TextView details = root.findViewById(R.id.controls_details);
        details.setText("English  ·  Subtitles: English"); details.setVisibility(View.VISIBLE);
        android.widget.ImageView centre = root.findViewById(R.id.center_icon);
        centre.setImageResource(tv.daniil.playback.R.drawable.ic_pause); centre.setVisibility(View.VISIBLE);
        android.widget.SeekBar progress = root.findViewById(R.id.progress);
        progress.setProgress(428); progress.setSecondaryProgress(520);
        ((android.widget.TextView) root.findViewById(R.id.elapsed)).setText("41:02");
        ((android.widget.TextView) root.findViewById(R.id.remaining)).setText("−55:21");
        android.widget.TextView play = root.findViewById(R.id.control_play);
        play.setText(tv.daniil.playback.R.string.control_pause);
        for (int id : new int[]{R.id.control_play, R.id.control_rewind, R.id.control_forward, R.id.control_options, R.id.control_stop}) {
            View control = root.findViewById(id); control.setFocusable(true); control.setClickable(true);
        }
        play.requestFocus();
        settle(1500);
        snap("15-player", host.getWindow());
        root.findViewById(R.id.controls).setVisibility(View.GONE);
        root.findViewById(R.id.error).setVisibility(View.VISIBLE);
        ((android.widget.TextView) root.findViewById(R.id.error_title)).setText("This source didn't open");
        ((android.widget.TextView) root.findViewById(R.id.error_message)).setText("The provider didn't answer in time. Try again, or choose another source.");
        root.findViewById(R.id.error_sources).setVisibility(View.VISIBLE);
        root.findViewById(R.id.error_retry).requestFocus();
        settle(800);
        snap("15b-player-error", host.getWindow());
        // The options menu, built with the same row layout the player's menu inflates; the first row has focus.
        root.findViewById(R.id.error).setVisibility(View.GONE);
        root.findViewById(R.id.controls).setVisibility(View.VISIBLE);
        android.view.LayoutInflater.from(host).inflate(tv.daniil.playback.R.layout.playback_menu, root, true);
        root.findViewById(tv.daniil.playback.R.id.menu).setVisibility(View.VISIBLE);
        ((android.widget.TextView) root.findViewById(tv.daniil.playback.R.id.menu_title)).setText(tv.daniil.playback.R.string.menu_options);
        android.view.ViewGroup rows = root.findViewById(tv.daniil.playback.R.id.menu_rows);
        String[][] items = {{"Audio", "English"}, {"Subtitles", "English"}, {"Quality", "Auto (1080p)"}, {"Speed", "Normal"}, {"Subtitle style", ""}};
        for (int i = 0; i < items.length; i++) {
            View row = android.view.LayoutInflater.from(host).inflate(tv.daniil.playback.R.layout.row_menu, rows, false);
            ((android.widget.TextView) row.findViewById(tv.daniil.playback.R.id.row_label)).setText(items[i][0]);
            ((android.widget.TextView) row.findViewById(tv.daniil.playback.R.id.row_value)).setText(items[i][1]);
            ((android.widget.ImageView) row.findViewById(tv.daniil.playback.R.id.row_icon)).setImageResource(tv.daniil.playback.R.drawable.ic_chevron_right);
            row.setSelected(i == 1);
            rows.addView(row);
        }
        settle(800);
        snap("15c-player-menu", host.getWindow());
    }

    /** Settles until {@code done} holds or {@code ms} pass. */
    private static void waitFor(java.util.function.BooleanSupplier done, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!done.getAsBoolean() && System.currentTimeMillis() < end) settle(100);
    }

    /** D-pad: arrows move focus the way the framework's focus search does; other keys go to the focused view. */
    private void key(int code) {
        View focused = activity.getWindow().getDecorView().findFocus();
        int direction = code == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT : code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT
                : code == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN : code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : 0;
        if (direction != 0 && focused != null) {
            View next = focused.focusSearch(direction);
            if (next != null) next.requestFocus(direction);
        } else {
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
    }

    /** Runs the main looper roughly in real time so background work, artwork and animations finish. */
    private static void settle(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25));
            Thread.sleep(25);
        }
    }

    private void click(String label) {
        ArrayList<View> found = new ArrayList<>();
        activity.getWindow().getDecorView().findViewsWithText(found, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION | View.FIND_VIEWS_WITH_TEXT);
        for (View view : found) for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v.isClickable()) { v.performClick(); return; }
        }
        throw new AssertionError("No control labelled " + label);
    }

    /**
     * Dialogs have their own window; this captures the alert surface without the dimmed page behind it. It draws in
     * software: Robolectric's hardware copy of a dialog window ignores a ScrollView's clip.
     */
    private void snapDialog(String name) throws Exception {
        View decor = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        decor.draw(new android.graphics.Canvas(bitmap));
        save(name, bitmap, decor);
    }
    private void snap(String name) throws Exception { snap(name, activity.getWindow()); }
    private void snap(String name, Window window) throws Exception {
        View decor = window.getDecorView();
        Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        AtomicBoolean done = new AtomicBoolean();
        PixelCopy.request(window, bitmap, result -> done.set(true), new Handler(Looper.getMainLooper()));
        for (int i = 0; i < 200 && !done.get(); i++) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));
        save(name, bitmap, decor);
    }
    private void save(String name, Bitmap bitmap, View decor) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(out, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        System.out.println("snapshot " + name + " " + decor.getWidth() + "x" + decor.getHeight());
    }
}
