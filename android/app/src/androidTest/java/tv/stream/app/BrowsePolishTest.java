package tv.stream.app;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** Real navigation and keyboard geometry on an isolated fixture-only installation. */
public final class BrowsePolishTest extends AppHarness {
    @Test public void keyboardRowsAlignAndEmptyScreensStayNavigable() throws Exception {
        launch(); waitUntil("empty Home", 10_000, () -> visible("Make yourself at home"));
        screenshot("validation-empty-home.png");
        tap("Library", true); waitUntil("empty Library", 5000, () -> visible("Your library is empty"));
        screenshot("validation-empty-library.png");
        tap("Search", true); waitUntil("keyboard", 5000, () -> visible("Type a"));
        alignedKeys("a"); screenshot("validation-search-keyboard.png");
        tap("Show 123 keys", false); alignedKeys("1");
        tap("Show àéü keys", true); alignedKeys("à");
        tap("Type à", false);
        waitUntil("accent key enters text", 5000, () -> visible("à"));
        tap("Clear search", true);
        waitUntil("clear keeps keyboard usable", 5000, () -> visible("Type à"));
    }
    @Test public void openingFilmFocusesPlayAndReturningRestoresTheChosenAction() throws Exception {
        readyLibrary(); launch(); tap("Generated Film", true);
        waitUntil("Play automatically focused", 10_000, () -> focused("play"));
        SystemClock.sleep(1200);
        assertTrue("Enrichment preserves Play focus", focused("play"));
        screenshot("validation-film-play-focus.png");
        tap("Sources", false);
        waitUntil("source chooser", 10_000, () -> !focused("play") && visible("Sources"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitUntil("return restores Sources instead of stealing focus", 10_000, () -> focused("sources"));
    }
    @Test public void everyKeyboardLanguageFillsItsLetterSymbolAndAccentRows() throws Exception {
        String[] codes = tv.stream.app.ui.KeyboardLanguages.CODES;
        services.store.setPreference("keyboardLanguages", String.join(",", codes));
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        tap("Search", true); waitUntil("keyboard", 5000, () -> visible("Type a"));
        for (int i = 0; i < codes.length; i++) {
            String code = codes[i], letters = tv.stream.app.ui.KeyboardLanguages.alphabet(code);
            waitUntil(code + " keyboard", 5000, () -> {
                AtomicBoolean current = new AtomicBoolean();
                browse.onActivity(activity -> {
                    android.widget.TextView globe = activity.findViewById(R.id.page_rows).findViewWithTag("keyboard:Language");
                    current.set(globe != null && globe.getText().toString().equals(tv.stream.app.ui.KeyboardLanguages.name(code)));
                }); return current.get();
            });
            alignedKeys(letters.substring(0, 1));
            tap("Show 123 keys", false); alignedKeys("1");
            tap("Show àéü keys", true); alignedKeys(tv.stream.app.ui.KeyboardLanguages.accents(code).substring(0, 1));
            tap("Show " + letters.substring(0, 3) + " keys", false);
            if (code.equals("ru")) screenshot("validation-keyboard-russian-filled.png");
            if (i < codes.length - 1) tap("Change keyboard language", true);
        }
        // Editing on the longer alphabet still uses the same query after several layout rebuilds.
        tap("Type ї", false); waitUntil("Ukrainian input", 5000, () -> searchText().equals("ї"));
        tap("Delete last character", true); waitUntil("deletion preserves keyboard", 5000, () -> visible("Type ї") && !searchText().equals("ї"));
    }
    private String searchText() {
        java.util.concurrent.atomic.AtomicReference<String> text = new java.util.concurrent.atomic.AtomicReference<>();
        browse.onActivity(activity -> text.set(((android.widget.TextView) activity.findViewById(R.id.search_query)).getText().toString()));
        return text.get();
    }
    private boolean focused(String tag) {
        AtomicBoolean result = new AtomicBoolean();
        browse.onActivity(activity -> { View focus = activity.getCurrentFocus(); result.set(focus != null && tag.equals(focus.getTag())); });
        return result.get();
    }
    private void alignedKeys(String first) {
        instrumentation.waitForIdleSync();
        browse.onActivity(activity -> {
            ViewGroup row = (ViewGroup) activity.findViewById(R.id.page_rows).findViewWithTag("keyboard:" + first).getParent();
            ViewGroup keys = (ViewGroup) row.getParent();
            int edge = row.getChildAt(row.getChildCount() - 1).getRight();
            for (int i = 0; i < keys.getChildCount(); i++) {
                ViewGroup other = (ViewGroup) keys.getChildAt(i);
                assertEquals("Each row begins in the same column", row.getLeft(), other.getLeft());
                assertEquals("Each row ends in the same column", edge, other.getChildAt(other.getChildCount() - 1).getRight());
                for (int j = 0; j < other.getChildCount(); j++) {
                    View key = other.getChildAt(j);
                    // Delete is an icon key; every other key carries its caption.
                    boolean icon = "Delete last character".contentEquals(key.getContentDescription());
                    assertTrue("Every cell is an actual key, with no blank spacers", (icon || key instanceof android.widget.TextView) && key.isFocusable());
                    assertEquals("Different label sizes must not offset key backgrounds", other.getChildAt(0).getTop(), key.getTop());
                    assertEquals("Keys share a height", row.getChildAt(0).getHeight(), key.getHeight());
                    assertEquals("Focus does not distort wide keys", 1f, key.getScaleX(), 0.001f);
                    if (!icon) assertEquals("Key captions fit", 0, ((android.widget.TextView) key).getLayout().getEllipsisCount(0));
                    if (j > 0) assertEquals("Only the designed gap separates keys", tv.stream.app.ui.Tv.dp(context, 4), key.getLeft() - other.getChildAt(j - 1).getRight());
                }
            }
        });
    }
    private void screenshot(String name) throws Exception {
        SystemClock.sleep(400); // Capture after the page entrance transition.
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        bitmap.recycle();
    }
}
