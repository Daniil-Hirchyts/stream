package tv.daniil.stream;

import static org.junit.Assert.*;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import androidx.recyclerview.widget.RecyclerView;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Test;

/** Observe rendered frames: restoring a cached page must still animate the visible content. */
public final class PageNavigationTest extends AppHarness {
    @Test public void tabsPushBackSearchAndCachedHomeAnimateAfterTheirContentIsLaidOut() throws Exception {
        launch(); waitUntil("Home", 10000, () -> visible("Make yourself at home")); SystemClock.sleep(350);
        motion(activity -> activity.root("Settings"), 0);
        motion(activity -> activity.push(new Route("Keyboard languages")), 1);
        motion(MainActivity::back, -1);
        motion(activity -> activity.root("Home"), 0);
        motion(MainActivity::search, 0);
        motion(MainActivity::back, 0);
    }
    private void motion(Consumer<MainActivity> navigate, int direction) {
        AtomicBoolean movingContent = new AtomicBoolean();
        AtomicReference<ViewTreeObserver.OnDrawListener> observer = new AtomicReference<>();
        browse.onActivity(activity -> {
            RecyclerView rows = activity.findViewById(R.id.page_rows);
            observer.set(() -> {
                float distance = direction == 0 ? rows.getTranslationY() : direction * rows.getTranslationX();
                if (rows.getChildCount() > 0 && rows.getAlpha() > 0.01f && rows.getAlpha() < .99f && distance > 1f) movingContent.set(true);
            });
            rows.getViewTreeObserver().addOnDrawListener(observer.get()); navigate.accept(activity);
        });
        waitUntil("visible page slides and fades in direction " + direction, 5000, movingContent::get);
        waitUntil("page transition settles", 5000, () -> {
            AtomicBoolean settled = new AtomicBoolean();
            browse.onActivity(activity -> {
                View rows = activity.findViewById(R.id.page_rows);
                settled.set(rows.getAlpha() == 1f && rows.getTranslationX() == 0f && rows.getTranslationY() == 0f);
            });
            return settled.get();
        });
        browse.onActivity(activity -> activity.findViewById(R.id.page_rows).getViewTreeObserver().removeOnDrawListener(observer.get()));
    }
}
