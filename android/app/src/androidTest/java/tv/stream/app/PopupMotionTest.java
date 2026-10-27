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

/** Actual painted frames and rapid remote navigation, not just final animation properties. */
public final class PopupMotionTest extends AppHarness {
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
