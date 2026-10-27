package tv.stream.app;

import android.view.KeyEvent;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class TransitionFocusTest extends AppHarness {
    /** Hold metadata work so the intermediate frame is observable without relying on network timing. */
    private CountDownLatch holdPageWork() throws Exception {
        CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) app.workers.submit(() -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue("Page workers held", entered.await(3, TimeUnit.SECONDS)); return release;
    }
    private String focusTag() {
        AtomicReference<String> tag = new AtomicReference<>("");
        browse.onActivity(activity -> { View focus = activity.getCurrentFocus(); if (focus != null) tag.set(String.valueOf(focus.getTag())); });
        return tag.get();
    }
    @Test public void openingFilmGoesDirectlyFromContentToPlayWithoutHighlightingNavigation() throws Exception {
        readyLibrary(); launch(); waitUntil("home", 10000, () -> visible("Generated Film"));
        List<String> focusEvents = new CopyOnWriteArrayList<>();
        browse.onActivity(activity -> activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((old, next) -> {
            if (next != null) focusEvents.add(String.valueOf(next.getTag()));
        }));
        CountDownLatch release = holdPageWork();
        try {
            tap("Generated Film", true);
            browse.onActivity(activity -> {
                assertFalse("No navigation fallback during metadata loading", activity.findViewById(R.id.top_bar).hasFocus());
                assertTrue("Content holds neutral focus", activity.findViewById(R.id.page_rows).isFocused());
            });
            assertFalse("No Home flash", focusEvents.contains("nav:Home"));
        } finally { release.countDown(); }
        waitUntil("Play receives focus", 10000, () -> "play".equals(focusTag()));
        assertFalse("No intermediate navigation highlight", focusEvents.stream().anyMatch(tag -> tag.startsWith("nav:")));
    }
    @Test public void deliberateNavigationDuringLoadingIsNotOverriddenWhenPlayArrives() throws Exception {
        readyLibrary(); launch(); waitUntil("home", 10000, () -> visible("Generated Film"));
        CountDownLatch release = holdPageWork();
        try {
            tap("Generated Film", false);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
            waitUntil("viewer selected the rail", 3000, () -> "nav:Home".equals(focusTag()));
        } finally { release.countDown(); }
        waitUntil("details finished", 10000, () -> visible("Play"));
        instrumentation.waitForIdleSync();
        assertEquals("Late content keeps the viewer's navigation choice", "nav:Home", focusTag());
    }
}
