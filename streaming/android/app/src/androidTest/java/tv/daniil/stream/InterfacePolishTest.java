package tv.daniil.stream;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import tv.daniil.stream.addon.Title;
import tv.daniil.stream.ui.Item;
import tv.daniil.stream.ui.Page;
import tv.daniil.stream.ui.PageAdapter;

public final class InterfacePolishTest extends AppHarness {
    @Test public void keyboardLanguagesAndVoiceResultsKeepTheQueryEditable() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home"));
        tap("Settings", true); tap("Languages", false); tap("Keyboard languages", true);
        tap("Русский", true);
        waitUntil("language saved", 5000, () -> services.store.preference("keyboardLanguages", "").contains("ru"));
        tap("Back", false); tap("Search", true); waitUntil("English keys", 5000, () -> visible("Type a"));
        tap("Type a", false); tap("Type b", true);
        waitUntil("typed query", 5000, () -> query().equals("ab"));
        tap("Change keyboard language", true); waitUntil("Russian keys", 5000, () -> visible("Type я"));
        tap("Type я", false); waitUntil("language switch preserves query", 5000, () -> query().equals("abя"));
        AtomicReference<Intent> requested = new AtomicReference<>();
        AtomicInteger resultCode = new AtomicInteger(Activity.RESULT_OK);
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (!RecognizerIntent.ACTION_RECOGNIZE_SPEECH.equals(intent.getAction())) return null;
                requested.set(intent);
                return new Instrumentation.ActivityResult(resultCode.get(), new Intent().putStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS, new ArrayList<>(Arrays.asList("Voice phrase"))));
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            tap("Voice input", true);
            waitUntil("speech result becomes search query", 5000, () -> query().equals("Voice phrase"));
            assertEquals("ru", requested.get().getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
            assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, requested.get().getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL));
            tap("Type я", false); waitUntil("typing appends to voice result", 5000, () -> query().equals("Voice phraseя"));
            resultCode.set(Activity.RESULT_CANCELED); tap("Voice input", false);
            SystemClock.sleep(250); assertEquals("Cancelling voice preserves text", "Voice phraseя", query());
            tap("Change keyboard language", true); tap("Type z", false);
            waitUntil("Latin keyboard still edits the same query", 5000, () -> query().equals("Voice phraseяz"));
            screenshot("keyboard-languages-voice.png");
        } finally { instrumentation.removeMonitor(monitor); }
    }

    @Test public void clockStaysVisibleAndIconCaptionsCrossfade() throws Exception {
        readyLibrary(); launch(); waitUntil("Home clock", 10000, () -> visible("Generated Film") && clockShown());
        AtomicInteger hidden = new AtomicInteger();
        View[] frameClock = new View[1];
        AtomicReference<ViewGroup> captionCheck = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean outgoingFaded = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean incomingFaded = new java.util.concurrent.atomic.AtomicBoolean();
        ViewTreeObserver.OnPreDrawListener listener = () -> {
            if (frameClock[0].getVisibility() != View.VISIBLE) hidden.incrementAndGet();
            ViewGroup caption = captionCheck.get();
            if (caption != null) {
                TextView outgoing = (TextView) caption.getChildAt(0), incoming = (TextView) caption.getChildAt(1);
                if (outgoing.getText().toString().equals("Add favourite") && outgoing.getAlpha() > 0 && outgoing.getAlpha() < 1) outgoingFaded.set(true);
                if (incoming.getText().toString().equals("Mark watched") && incoming.getAlpha() > 0 && incoming.getAlpha() < 1) incomingFaded.set(true);
            }
            return true;
        };
        browse.onActivity(activity -> { frameClock[0] = activity.findViewById(R.id.clock);
            activity.findViewById(R.id.browse_root).getViewTreeObserver().addOnPreDrawListener(listener); });
        tap("Refresh", true); waitUntil("refreshed", 10000, () -> visible("Generated Film"));
        tap("Generated Film", false); waitUntil("details", 10000, () -> visible("Play"));
        SystemClock.sleep(350);
        browse.onActivity(activity -> activity.findViewById(R.id.page_rows).findViewWithTag("favourite").requestFocus());
        SystemClock.sleep(350);
        browse.onActivity(activity -> {
            captionCheck.set(activity.findViewById(R.id.page_rows).findViewWithTag("action-caption"));
            activity.findViewById(R.id.page_rows).findViewWithTag("watched").requestFocus();
        });
        waitUntil("old and new icon captions fade separately", 1500, () -> outgoingFaded.get() && incomingFaded.get());
        SystemClock.sleep(250); screenshot("icon-caption-transition.png");
        tap("Back", false); waitUntil("Home restored", 5000, () -> visible("Browse catalogues"));
        browse.onActivity(activity -> activity.findViewById(R.id.browse_root).getViewTreeObserver().removeOnPreDrawListener(listener));
        assertEquals("No hidden-clock frame during reload or navigation", 0, hidden.get());
    }

    @Test public void galleryCardsOmitYearsAndRedundantCollectionLabels() throws Exception {
        Title film = Title.parse("local", new JSONObject().put("id", "test:clean-gallery").put("type", "movie")
                .put("name", "A Quiet Film").put("releaseInfo", "1999"));
        services.store.favourite(film, true); services.store.watched(film, film.id, true);
        launch(); waitUntil("favourite on Home", 10000, () -> visible("A Quiet Film"));
        assertFalse("Home spotlight omits the year", visible("1999"));
        AtomicReference<MainActivity> owner = new AtomicReference<>(); browse.onActivity(owner::set);
        BrowseScreens screens = new BrowseScreens(owner.get(), services);
        Page library = screens.library(); int count = 0;
        for (Item item : library.items) for (Item.Tile tile : item.tiles) {
            count++; assertEquals("No year, Favourite or Watched caption", "", tile.subtitle);
            assertEquals("Collection context needs no duplicate badge", 0, tile.badge);
            assertTrue("Completed history needs no full progress bar", tile.progress < 0);
        }
        assertEquals(2, count);
        assertEquals("", screens.poster("browse", film).subtitle);
        assertTrue("Year stays available in title details", PageAdapter.metaLine(film).contains("1999"));
        tap("Library", true); waitUntil("Watch history", 5000, () -> visible("Watch history"));
        assertFalse(visible("1999")); assertFalse(visible("Watched")); assertFalse(visible("Favourite"));
        screenshot("library-clean-captions.png");
    }
    private String query() {
        AtomicReference<String> value = new AtomicReference<>();
        browse.onActivity(activity -> value.set(((TextView) activity.findViewById(R.id.search_query)).getText().toString())); return value.get();
    }
    private boolean clockShown() {
        AtomicInteger shown = new AtomicInteger(); browse.onActivity(activity -> shown.set(activity.findViewById(R.id.clock).isShown() ? 1 : 0)); return shown.get() == 1;
    }
    private void screenshot(String name) throws Exception {
        android.graphics.Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(context.getFilesDir(), name))) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
        bitmap.recycle();
    }
}
