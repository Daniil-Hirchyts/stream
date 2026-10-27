package tv.stream.app;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import tv.stream.app.ui.SubtitlePreviewView;

public final class SubtitlePagePreviewTest extends AppHarness {
    @Test public void choicesUseOnePagePreviewAndCancelRestoresItWhileSelectionCommits() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home")); tap("Settings", true); tap("Subtitles", false);
        waitUntil("subtitle settings", 5000, () -> visible("Subtitle color"));
        assertTrue("Effective default value is named", visible("Medium")); assertFalse(visible("Default"));
        assertTrue("Same terminology as the player", visible("Subtitle background")); assertTrue(visible("Dark box"));
        Bitmap before = drawPreview();
        try {
            tap("Subtitle color", true); waitUntil("colour choices", 5000, () -> visible("White")); SystemClock.sleep(350);
            Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(context.getFilesDir(), "validation-settings-single-preview.png"))) { screen.compress(Bitmap.CompressFormat.PNG, 100, out); } screen.recycle();
            instrumentation.runOnMainSync(() -> {
                View root = WindowInspector.getGlobalWindowViews().stream().<View>map(window -> window.findViewWithTag("popup-root")).filter(view -> view != null).findFirst().orElse(null);
                assertNotNull(root); assertEquals("No duplicate preview inside the popup", 0, previews(root));
            });
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); SystemClock.sleep(150);
            Bitmap preview = drawPreview(); assertFalse("Focus updates the existing page preview", before.sameAs(preview)); preview.recycle();
            assertEquals("Focus does not save", "", services.playerPreferences.getString("subtitle_color", ""));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            waitUntil("Settings window returns", 5000, () -> visible("Subtitle color")); SystemClock.sleep(250);
            Bitmap cancelled = drawPreview(); assertTrue("Back restores the saved look", before.sameAs(cancelled)); cancelled.recycle();
            tap("Subtitle color", false); tap("Yellow", true);
            waitUntil("colour saved", 5000, () -> services.playerPreferences.getString("subtitle_color", "").equals("yellow"));
            app.persistence.submit(() -> {}).get(); assertEquals("yellow", services.store.preference("subtitle_color", ""));
            waitUntil("Settings value updated", 5000, () -> visible("Subtitle color") && visible("Yellow"));
            Bitmap committed = drawPreview(); assertFalse(before.sameAs(committed)); committed.recycle();
        } finally { before.recycle(); }
    }
    private Bitmap drawPreview() {
        AtomicReference<Bitmap> image = new AtomicReference<>(); browse.onActivity(activity -> {
            View preview = activity.findViewById(R.id.page_rows).findViewWithTag("subtitle-preview"); assertNotNull(preview);
            Bitmap bitmap = Bitmap.createBitmap(preview.getWidth(), preview.getHeight(), Bitmap.Config.ARGB_8888); preview.draw(new Canvas(bitmap)); image.set(bitmap);
        }); return image.get();
    }
    private int previews(View view) { int count = view instanceof SubtitlePreviewView ? 1 : 0; if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += previews(((ViewGroup) view).getChildAt(i)); return count; }
}
