package tv.daniil.stream;

import static org.junit.Assert.*;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual pointer/remote interactions with Stream's player resource overrides, including its hidden transport row. */
@RunWith(AndroidJUnit4.class)
public final class PlayerInteractionTest extends AppHarness {
    @Test public void pointerMenusTracksStyleAndTimelineWorkInTheAppPlayer() throws Exception {
        readyLibrary("http-mkv"); launch(); tap("Generated Film", false); tap("Play", true);
        waitUntil("Matroska renders", 30_000, () -> state() != null && state().playing && state().renderedFirstFrame);
        control(tv.daniil.playback.R.id.player_view, false); control(tv.daniil.playback.R.id.center_icon, true);
        waitUntil("pointer pauses", 5000, () -> !state().playWhenReady);
        String audio = state().audioTracks.get(1).label, subtitle = state().textTracks.get(0).label;
        control(tv.daniil.playback.R.id.control_options, false);
        tap("Audio", true); tap(audio, false);
        waitUntil("audio selected through menu", 5000, () -> state().audio != null && state().audio.label.equals(audio));
        tap("Subtitles", false); tap(subtitle, true);
        waitUntil("subtitle selected through menu", 5000, () -> state().subtitles != null && state().subtitles.label.equals(subtitle));
        tap("Speed", true); tap("1.5×", false);
        waitUntil("speed selected through menu", 5000, () -> state().speed == 1.5f);
        tap("Subtitle style", false); tap("Size", true); tap("Large", false);
        waitUntil("subtitle style persisted", 5000, () -> services.playerPreferences.getString("subtitle_size", "").equals("large"));
        control(tv.daniil.playback.R.id.menu_back, true);
        WatchActivity menuActivity = watch();
        instrumentation.runOnMainSync(() -> assertFalse("Track changes must not expose controls beneath the menu",
                menuActivity.findViewById(tv.daniil.playback.R.id.controls).isShown()));
        // The centre of the video lies outside the right-hand menu; clicking it dismisses the sheet.
        control(tv.daniil.playback.R.id.player_view, false);
        waitUntil("menu closes by pointer", 5000, () -> !shown(tv.daniil.playback.R.id.menu));
        assertFalse("Dismissing the menu must leave playback paused", state().playWhenReady);
        control(tv.daniil.playback.R.id.player_view, true);
        WatchActivity activity = watch(); Rect timeline = new Rect();
        instrumentation.runOnMainSync(() -> {
            assertTrue(activity.findViewById(tv.daniil.playback.R.id.progress).getGlobalVisibleRect(timeline));
            View captions = activity.findViewById(tv.daniil.playback.R.id.subtitles);
            assertTrue("Captions clear the timeline touch target", captions.getHeight() - captions.getPaddingBottom() < timeline.top);
        });
        // The subtitle track extends beyond the generated video; keep the seek inside both A/V tracks.
        long target = state().durationMs * 4 / 10;
        assertTrue("Known duration for a real timeline drag", target > 0);
        long started = SystemClock.uptimeMillis();
        for (int i = 0; i <= 10; i++) {
            float x = timeline.left + timeline.width() * (0.2f + 0.2f * i / 10);
            MotionEvent event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), i == 0 ? MotionEvent.ACTION_DOWN
                    : i == 10 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, x, timeline.centerY(), 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            assertTrue(instrumentation.getUiAutomation().injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(30);
        }
        waitUntil("timeline drag seeks while paused", 10_000, () -> !state().playWhenReady && Math.abs(state().positionMs - target) < 2000);
        control(tv.daniil.playback.R.id.center_icon, true);
        waitUntil("pointer resumes after drag", 10_000, () -> state().playing);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU);
        waitUntil("remote opens menu after touch", 5000, () -> shown(tv.daniil.playback.R.id.menu));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU);
        waitUntil("remote closes menu", 5000, () -> !shown(tv.daniil.playback.R.id.menu));
        control(tv.daniil.playback.R.id.player_view, false); control(tv.daniil.playback.R.id.control_stop, true);
        waitUntil("pointer closes playback", 10_000, () -> watch() == null);
    }

    private boolean shown(int id) {
        java.util.concurrent.atomic.AtomicBoolean shown = new java.util.concurrent.atomic.AtomicBoolean();
        WatchActivity activity = watch();
        if (activity != null) instrumentation.runOnMainSync(() -> shown.set(activity.findViewById(id).getVisibility() == View.VISIBLE));
        return shown.get();
    }
}
