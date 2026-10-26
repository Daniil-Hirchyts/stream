package tv.daniil.stream;

import static org.junit.Assert.*;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import tv.daniil.stream.ui.Tv;

/** Actual remote/pointer inputs and rendered geometry; no public providers or owner data. */
public final class PopupInteractionTest extends AppHarness {
    @Test public void nestedLevelsMorphWithoutABackdropAndRestoreParentFocus() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        AtomicReference<Tv.Alert> popup = new AtomicReference<>(); AtomicBoolean accepted = new AtomicBoolean();
        browse.onActivity(activity -> {
            Tv.Alert menu = Tv.alert(activity, "Episode options", (View) null); popup.set(menu);
            menu.submenu("playback", "Playback", child -> {
                child.submenu("quality", "Quality", grandchild -> {
                    grandchild.button("balanced", "Balanced HD", () -> { accepted.set(true); grandchild.dialog.dismiss(); });
                });
                child.button("source", "Choose source", () -> {});
            });
            menu.button("watched", "Mark watched", () -> {}); menu.show();
        });
        waitUntil("root options", 5000, () -> visible("Mark watched") && visible("Playback"));
        SystemClock.sleep(350); assertNoExitRows();
        Dialog dialog = popup.get().dialog;
        instrumentation.runOnMainSync(() -> {
            assertEquals(0, dialog.getWindow().getAttributes().flags & (WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_BLUR_BEHIND));
            ViewGroup root = dialog.getWindow().getDecorView().findViewWithTag("popup-root");
            assertEquals("No page screenshot or tint layer", 1, root.getChildCount());
            ViewGroup rows = popup.get().buttons;
            assertEquals("Only the two actions", 2, rows.getChildCount());
            assertTrue("Action rows include meaningful icons", ((ViewGroup) rows.getChildAt(1)).getChildAt(0) instanceof ImageView);
        });
        Rect original = bounds(dialog);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        waitUntil("nested level", 5000, () -> visible("Quality") && visible("Choose source"));
        SystemClock.sleep(300); assertNoExitRows(); assertFalse(visible("Mark watched"));
        tap("Quality", true); waitUntil("third level", 5000, () -> visible("Balanced HD"));
        SystemClock.sleep(300); assertNoExitRows(); screenshot("validation-popup-nested.png");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        waitUntil("Left returns one level", 5000, () -> visible("Choose source") && !visible("Balanced HD"));
        SystemClock.sleep(300);
        instrumentation.runOnMainSync(() -> assertEquals("quality", dialog.getCurrentFocus().getTag()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitUntil("Back returns to root", 5000, () -> visible("Mark watched") && !visible("Choose source"));
        SystemClock.sleep(300); assertEquals("Root geometry restored", original, bounds(dialog));
        instrumentation.runOnMainSync(() -> assertEquals("playback", dialog.getCurrentFocus().getTag()));
        tap("Playback", false); tap("Quality", false); tap("Balanced HD", true);
        waitUntil("selection closes the whole window", 5000, () -> accepted.get() && !dialog.isShowing());
        windowReturned();
    }

    @Test public void menusGrowFromTheirOpenerAndStayInsideEveryScreenEdge() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        AtomicReference<Tv.Alert> popup = new AtomicReference<>(); AtomicReference<View> opener = new AtomicReference<>();
        AtomicReference<Rect> start = new AtomicReference<>(); List<Rect> frames = new ArrayList<>();
        browse.onActivity(activity -> {
            ImageView button = new ImageView(activity); button.setImageResource(R.drawable.ic_tv_more);
            button.setPadding(dp(11), dp(11), dp(11), dp(11)); button.setBackgroundResource(R.drawable.tv_button_round);
            button.setContentDescription("Open menu");
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.LEFT);
            params.leftMargin = dp(40); params.topMargin = dp(30);
            ((FrameLayout) activity.findViewById(android.R.id.content)).addView(button, params); opener.set(button);
            Tv.control(button, "popup-opener", () -> {
                Rect actual = new Rect(); button.getGlobalVisibleRect(actual); start.set(actual); frames.clear();
                Tv.Alert menu = Tv.choices(activity, "Episode options", new String[]{"Play", "Choose source", "Mark watched"}, i -> {}, null);
                popup.set(menu); menu.show();
                View panel = menu.dialog.getWindow().getDecorView().findViewWithTag("popup-panel");
                panel.getViewTreeObserver().addOnPreDrawListener(() -> {
                    Rect rect = new Rect(); if (panel.isShown() && panel.getGlobalVisibleRect(rect)) frames.add(rect); return true;
                });
            }, key -> {}, 1f, false);
        });
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        for (int position = 0; position < 4; position++) {
            final int corner = position;
            browse.onActivity(activity -> {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) opener.get().getLayoutParams();
                params.leftMargin = corner % 2 == 0 ? dp(40) : screenWidth - dp(84);
                params.topMargin = corner < 2 ? dp(30) : screenHeight - dp(74);
                opener.get().setLayoutParams(params);
            });
            tap("Open menu", position % 2 == 0); waitUntil("menu", 5000, () -> visible("Choose source")); SystemClock.sleep(350);
            assertNoExitRows();
            instrumentation.runOnMainSync(() -> {
                assertTrue("Morph opener=" + start.get() + ", first=" + (frames.isEmpty() ? "none" : frames.get(0)), !frames.isEmpty() && Rect.intersects(start.get(), frames.get(0)));
                Rect end = boundsNow(popup.get().dialog);
                assertTrue(end.left >= dp(23)); assertTrue(end.right <= screenWidth - dp(23));
                assertTrue(end.top >= dp(23)); assertTrue(end.bottom <= screenHeight - dp(23));
                assertTrue("The menu expands beyond its control", frames.stream().anyMatch(rect -> rect.width() < end.width() * .8f));
                assertTrue("Header controls open below; bottom controls open above", corner < 2 ? end.top >= start.get().bottom : end.bottom <= start.get().top);
                ViewGroup rows = popup.get().buttons;
                for (int i = 0; i < rows.getChildCount(); i++) {
                    View row = rows.getChildAt(i); Rect visible = new Rect(); assertTrue(row.getGlobalVisibleRect(visible));
                    assertEquals("The whole action is inside the final panel", row.getHeight(), visible.height());
                    assertTrue(((ViewGroup) row).getChildAt(0) instanceof ImageView);
                }
            });
            if (position == 1) screenshot("validation-popup-anchored.png");
            instrumentation.sendKeyDownUpSync(position % 2 == 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_BACK);
            waitUntil("remote dismisses root menu", 5000, () -> !popup.get().dialog.isShowing()); windowReturned();
            browse.onActivity(activity -> assertTrue("Focus returns to the opener", opener.get().hasFocus()));
        }
    }

    @Test public void longChoicesKeepTheSelectionAndBothRemoteExitKeysCancel() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        AtomicReference<Tv.Alert> popup = new AtomicReference<>(); AtomicInteger cancelled = new AtomicInteger();
        String[] choices = new String[24]; for (int i = 0; i < choices.length; i++) choices[i] = "Source " + (i + 1);
        for (int key : new int[]{KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK}) {
            browse.onActivity(activity -> {
                Tv.Alert menu = Tv.choices(activity, "Choose source", null, choices, 17, i -> {}, null, cancelled::incrementAndGet);
                popup.set(menu); menu.show();
            });
            waitUntil("initial selection scrolls into view", 5000, () -> visible("Source 18")); SystemClock.sleep(350); assertNoExitRows();
            instrumentation.runOnMainSync(() -> {
                View selected = popup.get().dialog.getCurrentFocus(); assertEquals("choice:17", selected.getTag());
                Rect visible = new Rect(); assertTrue(selected.getGlobalVisibleRect(visible));
                assertEquals("Whole focus row is visible", selected.getHeight(), visible.height());
            });
            int before = cancelled.get(); instrumentation.sendKeyDownUpSync(key);
            waitUntil("cancellation callback", 5000, () -> cancelled.get() == before + 1 && !popup.get().dialog.isShowing()); windowReturned();
        }
    }

    @Test public void textEntryValidatesCommitsAndCanBeLeftWithoutSaving() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        AtomicReference<String> accepted = new AtomicReference<>();
        browse.onActivity(activity -> activity.edit("Rename title", "Title", "", accepted::set));
        waitUntil("entry form", 5000, () -> visible("Continue")); assertNoExitRows();
        tap("Continue", true); waitUntil("empty value stays in the form", 5000, () -> visible("Enter a value"));
        assertNull(accepted.get()); enter("A new title"); tap("Continue", false);
        waitUntil("accepted value", 5000, () -> "A new title".equals(accepted.get()) && !visible("Continue")); windowReturned();
        browse.onActivity(activity -> activity.edit("Rename title", "Title", "Unchanged", accepted::set));
        waitUntil("second form", 5000, () -> visible("Continue")); enter("Do not save");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        waitUntil("Left dismisses even from a focused input", 5000, () -> !visible("Continue"));
        assertEquals("A new title", accepted.get());
    }

    @Test public void messageOnlyPopupsNeedNoDismissButton() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        AtomicInteger cancelled = new AtomicInteger(); AtomicReference<Tv.Alert> popup = new AtomicReference<>();
        browse.onActivity(activity -> {
            Tv.Alert message = Tv.alert(activity, "A quiet message", "Details are readable without a redundant confirmation action.");
            popup.set(message); message.dialog.setOnCancelListener(d -> cancelled.incrementAndGet()); message.show();
        });
        waitUntil("message", 5000, () -> visible("A quiet message")); assertNoExitRows();
        waitUntil("message window accepts remote input", 5000, () -> {
            AtomicBoolean ready = new AtomicBoolean();
            instrumentation.runOnMainSync(() -> ready.set(popup.get().dialog.getWindow().getDecorView().hasWindowFocus() && popup.get().dialog.getCurrentFocus() != null));
            return ready.get();
        });
        SystemClock.sleep(300);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        waitUntil("Left closes message", 5000, () -> cancelled.get() == 1 && !popup.get().dialog.isShowing());
    }

    @Test public void playbackFailureKeepsItsActionsButLeavesWithLeft() throws Exception {
        readyLibrary("failover"); launch(); tap("Generated Film", false); tap("Sources", true); tap("Unavailable source", false);
        waitUntil("manual source failure", 20000, () -> state() != null && state().error != null);
        assertFalse("No Close row on playback failures", visible("Close"));
        waitUntil("failure actions are readable", 5000, () -> visible("Try again") && visible("Choose source"));
        SystemClock.sleep(300);
        screenshot("validation-player-error-popup.png");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        waitUntil("Left exits the player from its error popup", 5000, () -> watch() == null);
    }

    @Test public void nestedPagesMeasureTheirContentAndMorphNarrowWideNarrow() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        AtomicReference<Tv.Alert> popup = new AtomicReference<>(); List<Integer> widths = new java.util.concurrent.CopyOnWriteArrayList<>();
        browse.onActivity(activity -> {
            Tv.Alert root = Tv.alert(activity, "Options", (View) null); popup.set(root);
            root.submenu("audio", "Audio", child -> {
                child.button("description", "English subtitles with audio descriptions and accessibility support", () -> {});
                child.submenu("speed", "Speed", compact -> {
                    compact.choice("normal", "1×", "", true, compact::back);
                    compact.choice("faster", "1.5×", "", false, compact::back);
                });
            }); root.show();
            View panel = root.dialog.getWindow().getDecorView().findViewWithTag("popup-panel");
            panel.getViewTreeObserver().addOnPreDrawListener(() -> { if (panel.isShown()) widths.add(panel.getWidth()); return true; });
        });
        waitUntil("compact root", 5000, () -> visible("Audio")); SystemClock.sleep(350); Rect narrow = bounds(popup.get().dialog); screenshot("validation-popup-content-root.png");
        widths.clear(); tap("Audio", false); waitUntil("wide page", 5000, () -> visible("Speed")); SystemClock.sleep(300);
        Rect wide = bounds(popup.get().dialog); screenshot("validation-popup-content-wide.png");
        assertTrue("Content, not the parent width, determines this level", wide.width() > narrow.width() + dp(120));
        assertTrue("Width changes through intermediate frames", widths.stream().anyMatch(w -> w > narrow.width() + dp(10) && w < wide.width() - dp(10)));
        tap("Speed", true); waitUntil("compact value list", 5000, () -> visible("1×")); SystemClock.sleep(300);
        Rect compact = bounds(popup.get().dialog); assertTrue(compact.width() < wide.width() - dp(120));
        assertTrue(compact.top >= dp(71)); assertTrue(compact.bottom <= context.getResources().getDisplayMetrics().heightPixels - dp(23));
        screenshot("validation-popup-content-width.png");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); waitUntil("wide parent", 5000, () -> visible("Speed")); SystemClock.sleep(300);
        assertEquals(wide, bounds(popup.get().dialog));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitUntil("narrow parent", 5000, () -> visible("Audio") && !visible("Speed")); SystemClock.sleep(300);
        assertEquals(narrow, bounds(popup.get().dialog));
        instrumentation.runOnMainSync(() -> assertEquals("audio", popup.get().dialog.getCurrentFocus().getTag()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); windowReturned();
    }

    private void assertNoExitRows() { assertFalse(visible("Cancel")); assertFalse(visible("Close")); assertFalse(visible("Back")); }
    private Rect bounds(Dialog dialog) { AtomicReference<Rect> value = new AtomicReference<>(); instrumentation.runOnMainSync(() -> value.set(boundsNow(dialog))); return value.get(); }
    private Rect boundsNow(Dialog dialog) { Rect rect = new Rect(); assertTrue(dialog.getWindow().getDecorView().findViewWithTag("popup-panel").getGlobalVisibleRect(rect)); return rect; }
    private int dp(float value) { return Tv.dp(context, value); }
    private void windowReturned() {
        waitUntil("Activity regains window focus", 5000, () -> { AtomicBoolean focused = new AtomicBoolean(); browse.onActivity(activity -> focused.set(activity.hasWindowFocus())); return focused.get(); });
    }
    private void screenshot(String name) throws Exception {
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), name))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); } image.recycle();
    }
}
