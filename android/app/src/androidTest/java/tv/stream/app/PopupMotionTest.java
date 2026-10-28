package tv.stream.app;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import tv.stream.app.ui.Tv;
import tv.stream.app.ui.Item;

/** Actual painted frames and rapid remote navigation, not just final animation properties. */
public final class PopupMotionTest extends AppHarness {
    @Test public void focusedOpenerRemainsWhiteThroughoutOpeningAndClosing() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        AtomicReference<Tv.Alert> menu = new AtomicReference<>();
        AtomicReference<View> button = new AtomicReference<>();
        browse.onActivity(activity -> {
            Item.Action action = new Item.Action("test-popup", Item.Action.Style.ICON, "Settings", () -> {
                Tv.Alert alert = Tv.alert(activity, "Settings", (View) null); menu.set(alert);
                alert.button("speed", "Speed", () -> {}); alert.show();
            }).icon(R.drawable.ic_tv_settings);
            View control = Tv.button(activity, action, ignored -> {}); button.set(control);
            android.widget.FrameLayout.LayoutParams layout = new android.widget.FrameLayout.LayoutParams(Tv.dp(context, 44), Tv.dp(context, 44), android.view.Gravity.TOP | android.view.Gravity.RIGHT);
            layout.topMargin = Tv.dp(context, 24); layout.rightMargin = Tv.dp(context, 100);
            activity.addContentView(control, layout); control.requestFocus();
        });
        SystemClock.sleep(300);
        Rect source = new Rect();
        instrumentation.runOnMainSync(() -> assertTrue(button.get().getGlobalVisibleRect(source)));
        List<Bitmap> screenFrames = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Integer> screenColours = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Integer> screenWidths = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean recording = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread capture = new Thread(() -> {
            while (recording.get() && screenFrames.size() < 80) {
                Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
                if (screen == null) continue;
                int x = source.left + source.width() / 4, y = source.centerY();
                screenColours.add(screen.getPixel(x, y));
                int left = source.centerX(), right = left;
                while (left > source.left - 8 && android.graphics.Color.red(screen.getPixel(left - 1, source.top + source.height() / 3)) > 230) left--;
                while (right < source.right + 8 && android.graphics.Color.red(screen.getPixel(right + 1, source.top + source.height() / 3)) > 230) right++;
                screenWidths.add(right - left);
                int margin = Tv.dp(context, 12);
                int l = Math.max(0, source.left - margin), t = Math.max(0, source.top - margin);
                int r = Math.min(screen.getWidth(), source.right + margin), b = Math.min(screen.getHeight(), source.bottom + margin);
                screenFrames.add(Bitmap.createBitmap(screen, l, t, r - l, b - t)); screen.recycle();
            }
        }, "popup-screen-capture");
        capture.start();
        waitUntil("initial screen frame", 5000, () -> !screenFrames.isEmpty());
        List<Bitmap> frames = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Integer> colours = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<ViewTreeObserver.OnPreDrawListener> listener = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            button.get().performClick();
            View root = menu.get().dialog.getWindow().getDecorView().findViewWithTag("popup-root");
            int[] position = new int[2], window = new int[2]; button.get().getLocationOnScreen(position); root.getLocationOnScreen(window);
            int x = position[0] - window[0] + button.get().getWidth() / 4;
            int y = position[1] - window[1] + button.get().getHeight() / 2;
            listener.set(() -> {
                View origin = root.findViewWithTag("popup-origin");
                if (origin == null || !origin.isLaidOut()) return true;
                colours.add(pixel(root, x, y));
                if (frames.size() < 50) {
                    Bitmap bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap); canvas.scale(320f / root.getWidth(), 180f / root.getHeight());
                    root.draw(canvas); frames.add(bitmap);
                }
                return true;
            });
            root.getViewTreeObserver().addOnPreDrawListener(listener.get());
        });
        waitUntil("popup opened", 5000, () -> visible("Speed")); SystemClock.sleep(450);
        int openingFrames = colours.size();
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitUntil("popup closed", 5000, () -> !menu.get().dialog.isShowing());
        SystemClock.sleep(350); recording.set(false); capture.join(5000);
        try {
            int cell = 120, rows = (screenFrames.size() + 7) / 8;
            Bitmap strip = Bitmap.createBitmap(cell * 8, cell * rows, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(strip);
            for (int i = 0; i < screenFrames.size(); i++) canvas.drawBitmap(screenFrames.get(i), null,
                    new Rect(i % 8 * cell, i / 8 * cell, (i % 8 + 1) * cell, (i / 8 + 1) * cell), null);
            try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "validation-popup-screen-frames.png"))) { strip.compress(Bitmap.CompressFormat.PNG, 100, out); }
            strip.recycle();
        } finally { for (Bitmap frame : screenFrames) frame.recycle(); }
        try {
            Bitmap strip = Bitmap.createBitmap(320 * 4, 180 * ((frames.size() + 3) / 4), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(strip);
            for (int i = 0; i < frames.size(); i++) canvas.drawBitmap(frames.get(i), null,
                    new Rect(i % 4 * 320, i / 4 * 180, (i % 4 + 1) * 320, (i / 4 + 1) * 180), null);
            try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "validation-popup-opener-frames.png"))) { strip.compress(Bitmap.CompressFormat.PNG, 100, out); }
            strip.recycle();
        } finally { for (Bitmap frame : frames) frame.recycle(); }
        assertTrue("Opening produced painted frames", openingFrames >= 4);
        assertTrue("Closing produced painted frames", colours.size() - openingFrames >= 4);
        for (int colour : colours) assertTrue("The opener stays white in every popup frame: " + colours,
                android.graphics.Color.alpha(colour) >= 240 && android.graphics.Color.red(colour) >= 240);
        for (int colour : screenColours) assertTrue("The actual screen keeps the opener white: " + screenColours,
                android.graphics.Color.red(colour) >= 230 && android.graphics.Color.green(colour) >= 230 && android.graphics.Color.blue(colour) >= 230);
        int widest = java.util.Collections.max(screenWidths), narrowest = java.util.Collections.min(screenWidths);
        assertTrue("The cursor does not change size at window handoff: " + screenWidths, widest - narrowest <= Tv.dp(context, 3));
    }
    @Test public void refreshingUnchangedMenuNeverFlashesTheFocusedAction() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        AtomicReference<Tv.Alert> menu = new AtomicReference<>();
        browse.onActivity(activity -> {
            Tv.Alert alert = Tv.alert(activity, "Playback", (View) null); menu.set(alert);
            alert.button("speed", "Speed", () -> {}); alert.button("audio", "Audio", () -> {}); alert.show();
        });
        waitUntil("menu", 5000, () -> visible("Speed")); SystemClock.sleep(400);
        List<Integer> colours = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<ViewTreeObserver.OnPreDrawListener> listener = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            ViewGroup panel = panel(menu.get()); View selected = menu.get().dialog.getCurrentFocus(); assertNotNull(selected);
            Rect sample = new Rect(0, 0, selected.getWidth(), selected.getHeight());
            panel.offsetDescendantRectToMyCoords(selected, sample);
            int x = sample.left + Tv.dp(context, 4), y = sample.centerY();
            colours.add(pixel(panel, x, y));
            listener.set(() -> { colours.add(pixel(panel, x, y)); return true; });
            panel.getViewTreeObserver().addOnPreDrawListener(listener.get());
            long until = SystemClock.uptimeMillis() + 360;
            Runnable frames = new Runnable() {
                @Override public void run() { panel.invalidate(); if (SystemClock.uptimeMillis() < until) panel.postOnAnimation(this); }
            };
            menu.get().refresh(); panel.postOnAnimation(frames);
        });
        SystemClock.sleep(450);
        instrumentation.runOnMainSync(() -> panel(menu.get()).getViewTreeObserver().removeOnPreDrawListener(listener.get()));
        assertTrue("Several rendered frames observed", colours.size() >= 6);
        try (java.io.FileWriter writer = new java.io.FileWriter(new File(context.getFilesDir(), "validation-popup-refresh-frames.json"))) { writer.write(colours.toString()); }
        int baseline = android.graphics.Color.red(colours.get(0));
        int minimum = colours.stream().mapToInt(android.graphics.Color::red).min().getAsInt();
        assertTrue("Focused platter stays painted, baseline=" + baseline + ", minimum=" + minimum + ", frames=" + colours,
                baseline >= 240 && minimum >= baseline - 10);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitUntil("closed", 5000, () -> !menu.get().dialog.isShowing());
    }

    @Test public void rapidSubmenuBackAndReopenSettleOnTheRightPageWithoutGhostLayers() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        AtomicReference<Tv.Alert> menu = new AtomicReference<>(); List<Bitmap> frames = new ArrayList<>();
        browse.onActivity(activity -> {
            Tv.Alert alert = Tv.alert(activity, "Options", (View) null); menu.set(alert);
            alert.submenu("audio", "Audio", child -> {
                child.button("description", "English audio descriptions and accessibility support", () -> {});
                child.submenu("speed", "Speed", values -> { values.choice("normal", "1×", "", true, values::back); values.choice("faster", "1.5×", "", false, values::back); });
            }); alert.button("watched", "Mark watched", () -> {}); alert.show();
        });
        waitUntil("root", 5000, () -> visible("Audio")); SystemClock.sleep(350);
        AtomicReference<ViewTreeObserver.OnPreDrawListener> listener = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            ViewGroup panel = panel(menu.get()); View root = menu.get().dialog.getWindow().getDecorView().findViewWithTag("popup-root");
            long start = SystemClock.uptimeMillis();
            listener.set(() -> {
                if (frames.size() < 10 && SystemClock.uptimeMillis() - start >= frames.size() * 25L) {
                    Bitmap bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap); canvas.drawColor(0xff0a0a0d); canvas.scale(640f / root.getWidth(), 360f / root.getHeight()); root.draw(canvas); frames.add(bitmap);
                }
                return true;
            }); panel.getViewTreeObserver().addOnPreDrawListener(listener.get());
            menu.get().buttons.findViewWithTag("audio").performClick();
        });
        SystemClock.sleep(35); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        SystemClock.sleep(35);
        instrumentation.runOnMainSync(() -> menu.get().buttons.findViewWithTag("audio").performClick());
        SystemClock.sleep(35); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        SystemClock.sleep(350);
        instrumentation.runOnMainSync(() -> {
            ViewGroup panel = panel(menu.get()); panel.getViewTreeObserver().removeOnPreDrawListener(listener.get());
            assertEquals("No outgoing content or snapshot left behind", 1, panel.getChildCount());
            assertEquals("Focus returns to the submenu opener", "audio", menu.get().dialog.getCurrentFocus().getTag());
            assertEquals("Final page is opaque", 1f, panel.getChildAt(0).getAlpha(), .001f);
        });
        assertTrue(visible("Mark watched")); assertFalse(visible("Speed"));
        try {
            Bitmap strip = Bitmap.createBitmap(640 * 2, 360 * ((frames.size() + 1) / 2), Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(strip);
            for (int i = 0; i < frames.size(); i++) canvas.drawBitmap(frames.get(i), i % 2 * 640, i / 2 * 360, null);
            try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "validation-popup-motion-frames.png"))) { strip.compress(Bitmap.CompressFormat.PNG, 100, out); } strip.recycle();
        } finally { for (Bitmap frame : frames) frame.recycle(); }
        tap("Audio", true); waitUntil("child after interrupted transitions", 5000, () -> visible("Speed"));
        tap("Speed", false); waitUntil("value list", 5000, () -> visible("1×"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); waitUntil("wide parent", 5000, () -> visible("Speed"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitUntil("root restored", 5000, () -> visible("Mark watched"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitUntil("closed", 5000, () -> !menu.get().dialog.isShowing());
    }

    private ViewGroup panel(Tv.Alert alert) { return alert.dialog.getWindow().getDecorView().findViewWithTag("popup-panel"); }
    private int pixel(View view, int x, int y) {
        Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(bitmap); canvas.translate(-x, -y); view.draw(canvas);
        int colour = bitmap.getPixel(0, 0); bitmap.recycle(); return colour;
    }
}
