package tv.daniil.stream;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import tv.daniil.stream.data.TvStore;

/** Generated torrent playback, real timeline gestures and durable completion choices on an isolated store. */
@RunWith(AndroidJUnit4.class)
public final class WatchCompletionInteractionTest extends AppHarness {
    @Test public void timelineTapDragAndWatchedButtonPreserveTheViewersChoice() throws Exception {
        playFilm();
        control(tv.daniil.playback.R.id.player_view, false); control(R.id.progress, true);
        waitUntil("timeline mouse tap pauses", 5000, () -> !state().playWhenReady);
        long paused = state().positionMs;
        assertTrue("A tap toggles transport without jumping halfway through the episode", paused < 15_000);
        control(R.id.progress, false);
        waitUntil("timeline touch tap resumes", 5000, () -> state().playing);
        control(R.id.progress, true);
        waitUntil("pause again", 5000, () -> !state().playWhenReady);
        dragTo(0.5f);
        waitUntil("midpoint is ready", 5000, () -> !state().buffering && state().positionMs > state().durationMs * 0.45);
        screenshot("validation-player-timeline-middle.png");
        dragTo(0.94f);
        waitUntil("drag seeks without resuming", 5000, () -> !state().playWhenReady && state().positionMs > state().durationMs * 0.9);
        assertTrue("Seeking near the end alone is unwatched", services.store.history(false).stream().noneMatch(p -> p.watched));
        WatchActivity activity = watch();
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        dragTo(0.94f);
        SystemClock.sleep(100);
        instrumentation.runOnMainSync(() -> {
            View info = activity.findViewById(R.id.timeline_info), end = activity.findViewById(R.id.remaining);
            assertTrue("Time and feedback stay on screen", info.getX() >= 0);
            assertTrue("Time, pause and seek feedback clear the end label", info.getX() + info.getWidth() < end.getLeft());
            assertEquals("Pause and seek share one status slot",
                    !activity.findViewById(R.id.seek_bubble).isShown(), activity.findViewById(R.id.timeline_paused).isShown());
            assertEquals("Centre transport occupies no picture", 0, activity.findViewById(R.id.center_icon).getWidth());
        });
        screenshot("validation-player-timeline.png");
        control(R.id.control_watched, true);
        waitUntil("manual completion persisted", 5000, () -> services.store.history(false).stream().anyMatch(p -> p.watched));
        assertTrue("Watched episode leaves Continue Watching", services.store.history(true).isEmpty());
        control(R.id.control_stop, false);
        waitUntil("manual completion exits without a prompt", 5000, () -> watch() == null);
        assertFalse(visible("Finished watching?"));
    }

    @Test public void nearEndExitCanKeepThePlaceCancelOrMarkWatched() throws Exception {
        playFilm();
        control(tv.daniil.playback.R.id.player_view, false); control(R.id.progress, true);
        waitUntil("paused", 5000, () -> !state().playWhenReady);
        dragTo(0.92f);
        waitUntil("near end", 5000, () -> state().positionMs > state().durationMs * 0.9);
        control(R.id.control_stop, true);
        waitUntil("exit offers completion choice", 5000, () -> visible("Finished watching?"));
        screenshot("validation-player-exit.png");
        tap("Continue watching", false);
        waitUntil("cancel keeps the paused player", 5000, () -> watch() != null && !visible("Finished watching?"));
        assertFalse(state().playWhenReady);
        control(R.id.control_stop, true);
        tap("Keep for later", true);
        waitUntil("keep exits", 5000, () -> watch() == null);
        app.persistence.submit(() -> {}).get();
        TvStore.Progress kept = services.store.history(true).get(0);
        assertFalse(kept.watched); assertTrue(kept.positionMs > kept.durationMs * 0.9);
        // Reopen the real saved media, pausing as soon as it starts so the credits do not run out.
        startAutomaticFixturePlayback();
        waitUntil("resumed media starts", 30_000, () -> state() != null && state().started);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE);
        waitUntil("resumed media pauses", 5000, () -> !state().playWhenReady);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitUntil("system Back also offers completion", 5000, () -> visible("Finished watching?"));
        tap("Mark watched", false);
        waitUntil("mark exits", 5000, () -> watch() == null);
        app.persistence.submit(() -> {}).get();
        assertTrue(services.store.history(false).get(0).watched);
        assertTrue(services.store.history(true).isEmpty());
    }

    private void dragTo(float fraction) {
        WatchActivity activity = watch(); Rect bounds = new Rect();
        instrumentation.runOnMainSync(() -> assertTrue(activity.findViewById(R.id.progress).getGlobalVisibleRect(bounds)));
        long time = SystemClock.uptimeMillis();
        for (int i = 0; i <= 10; i++) {
            float x = bounds.left + bounds.width() * (0.1f + (fraction - 0.1f) * i / 10f);
            MotionEvent event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), i == 0 ? MotionEvent.ACTION_DOWN
                    : i == 10 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, x, bounds.centerY(), 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            assertTrue(instrumentation.getUiAutomation().injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(25);
        }
    }
    private void screenshot(String name) throws Exception {
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), name))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); }
        image.recycle();
    }
}
