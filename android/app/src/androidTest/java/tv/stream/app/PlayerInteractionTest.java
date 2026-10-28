package tv.stream.app;

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
        readyLibrary("http-mkv"); launch(); tap("Generated Film", false); tap("Play", true); chooseFirstSource();
        waitUntil("Matroska renders", 30_000, () -> state() != null && state().playing && state().renderedFirstFrame);
        control(tv.stream.playback.R.id.player_view, false); control(tv.stream.playback.R.id.progress, true);
        waitUntil("pointer pauses", 5000, () -> !state().playWhenReady);
        android.graphics.Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(context.getFilesDir(), "validation-player-paused.png"))) {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
        } finally { image.recycle(); }
        String audio = state().audioTracks.get(1).label, subtitle = state().textTracks.get(0).label;
        control(tv.stream.playback.R.id.control_options, false);
        tap("Audio", true); tap(audio, false);
        waitUntil("audio selected through menu", 5000, () -> state().audio != null && state().audio.label.equals(audio));
        tap(subtitle, true);
        waitUntil("subtitle selected through menu", 5000, () -> state().subtitles != null && state().subtitles.label.equals(subtitle));
        control(tv.stream.playback.R.id.player_view, false);
        control(R.id.control_settings, true);
        tap("Speed", true); tap("1.5×", false);
        waitUntil("speed selected through menu", 5000, () -> state().speed == 1.5f);
        tap("Subtitle style", false); tap("Size", true); tap("Large", false);
        waitUntil("subtitle style persisted", 5000, () -> services.playerPreferences.getString("subtitle_size", "").equals("large"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        WatchActivity menuActivity = watch();
        instrumentation.runOnMainSync(() -> assertTrue("The toolbar stays visible beneath the popup menu",
                menuActivity.findViewById(tv.stream.playback.R.id.controls).isShown()));
        // The centre of the video lies outside the right-hand menu; clicking it dismisses the sheet.
        control(tv.stream.playback.R.id.player_view, false);
        waitUntil("menu closes by pointer", 5000, () -> !popupOpen());
        assertFalse("Dismissing the menu must leave playback paused", state().playWhenReady);
        control(tv.stream.playback.R.id.player_view, true);
        WatchActivity activity = watch(); Rect timeline = new Rect();
        instrumentation.runOnMainSync(() -> {
            assertTrue(activity.findViewById(tv.stream.playback.R.id.progress).getGlobalVisibleRect(timeline));
            View captions = activity.findViewById(tv.stream.playback.R.id.subtitles);
            assertTrue("Captions clear the timeline touch target", captions.getHeight() - captions.getPaddingBottom() < timeline.top);
        });
        // Keep this interaction test near the start; video EOS and longer subtitle/audio tails have their own regression.
        long target = state().durationMs * 2 / 10;
        assertTrue("Known duration for a real timeline drag", target > 0);
        long started = SystemClock.uptimeMillis();
        for (int i = 0; i <= 10; i++) {
            float x = timeline.left + timeline.width() * (0.1f + 0.1f * i / 10);
            MotionEvent event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), i == 0 ? MotionEvent.ACTION_DOWN
                    : i == 10 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, x, timeline.centerY(), 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            assertTrue(instrumentation.getUiAutomation().injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(30);
        }
        waitUntil("timeline drag seeks while paused", 10_000, () -> !state().playWhenReady && Math.abs(state().positionMs - target) < 2000);
        control(tv.stream.playback.R.id.progress, true);
        waitUntil("pointer resumes after drag", 10_000, () -> state().playing);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU);
        waitUntil("remote opens menu after touch", 5000, () -> popupOpen());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU);
        waitUntil("remote closes menu", 5000, () -> !popupOpen());
        // Closing the menu brings the toolbar back; a tap on the video now would hide it again. The menu's window keeps
        // focus (and ignores Back) until its closing animation ends.
        waitUntil("toolbar after the menu", 5000, () -> shown(tv.stream.playback.R.id.controls) && watch().hasWindowFocus());
        WatchActivity playing = watch();
        instrumentation.runOnMainSync(() -> assertTrue("Back exits from the playback toolbar",
                playing.findViewById(tv.stream.playback.R.id.control_stop).isShown()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        if (watch() != null) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitUntil("Back closes playback", 10_000, () -> watch() == null);
    }

    @Test public void arrowsReachOptionsAndSelectSubtitlesWithoutAMenuKey() throws Exception {
        readyLibrary("http-mkv"); launch(); tap("Generated Film", false); tap("Play", true); chooseFirstSource();
        waitUntil("video renders", 30_000, () -> state() != null && state().playing && state().renderedFirstFrame);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        WatchActivity activity = watch();
        instrumentation.runOnMainSync(() -> assertTrue("Up focuses the first action, watched",
                activity.findViewById(R.id.control_watched).hasFocus()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        instrumentation.runOnMainSync(() -> assertTrue("Right reaches Audio & subtitles",
                activity.findViewById(tv.stream.playback.R.id.control_options).hasFocus()));
        waitUntil("Active controls include the clock", 2000, () -> shown(R.id.player_clock));
        SystemClock.sleep(2500);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        SystemClock.sleep(2000);
        assertTrue("Real remote activity restarts the timeout", shown(R.id.controls));
        java.util.concurrent.atomic.AtomicBoolean fadingTogether = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean fadeMismatch = new java.util.concurrent.atomic.AtomicBoolean();
        android.view.ViewTreeObserver.OnDrawListener observer = () -> {
            View controls = activity.findViewById(R.id.controls), clock = activity.findViewById(R.id.player_clock);
            if (controls.getAlpha() > .1f && controls.getAlpha() < .9f) {
                fadingTogether.set(true);
                if (!clock.isShown() || Math.abs(clock.getAlpha() - controls.getAlpha()) > .25f) fadeMismatch.set(true);
            }
        };
        instrumentation.runOnMainSync(() -> activity.findViewById(R.id.player_root).getViewTreeObserver().addOnDrawListener(observer));
        waitUntil("Focused controls hide after inactivity", 4000, () -> !shown(R.id.controls));
        waitUntil("Clock hides with active controls", 2000, () -> !shown(R.id.player_clock));
        instrumentation.runOnMainSync(() -> activity.findViewById(R.id.player_root).getViewTreeObserver().removeOnDrawListener(observer));
        assertTrue("Observed the controls fading", fadingTogether.get());
        assertFalse("Clock and controls fade together", fadeMismatch.get());
        instrumentation.runOnMainSync(() -> assertTrue("Focus returns to the video", activity.findViewById(R.id.player_root).hasFocus()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("OK opens options", 5000, () -> popupOpen());
        // Subtitle choices are directly on the Audio & subtitles page: Off, then the first track.
        waitUntil("subtitle choices", 5000, () -> visible("Off"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("remote subtitle selected", 5000, () -> state().subtitles != null);
        waitUntil("selection dismisses menu", 5000, () -> !popupOpen());
        waitUntil("closing morph returns the player window", 5000, () -> {
            java.util.concurrent.atomic.AtomicBoolean focus = new java.util.concurrent.atomic.AtomicBoolean();
            instrumentation.runOnMainSync(() -> focus.set(activity.hasWindowFocus())); return focus.get();
        });
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        instrumentation.runOnMainSync(() -> assertTrue("Down focuses the timeline",
                activity.findViewById(tv.stream.playback.R.id.progress).hasFocus()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("remote pauses", 5000, () -> !state().playWhenReady);
        SystemClock.sleep(4500);
        assertTrue("Paused controls stay visible", shown(R.id.controls));
        assertTrue("Paused clock stays visible", shown(R.id.player_clock));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        waitUntil("remote timeline seeks", 5000, () -> state().positionMs >= 9000);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        waitUntil("remote resumes", 5000, () -> state().playing);
        waitUntil("Focused timeline hides after resume", 6000, () -> !shown(R.id.controls));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        waitUntil("Remote reopens transport", 2000, () -> shown(R.id.controls));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitUntil("Back hides focused transport", 5000, () -> !shown(tv.stream.playback.R.id.controls));
        SystemClock.sleep(1200);
        assertFalse("The focus keeper must not reopen dismissed controls", shown(tv.stream.playback.R.id.controls));
    }

    @Test public void mouseMovementRestoresControlsAndIdlePointerLetsThemHide() throws Exception {
        readyLibrary("http-mkv"); launch(); tap("Generated Film", false); tap("Play", true); chooseFirstSource();
        waitUntil("video renders", 30_000, () -> state() != null && state().playing && state().renderedFirstFrame);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE);
        waitUntil("paused clock and controls", 5000, () -> shown(R.id.controls) && shown(R.id.player_clock));
        WatchActivity activity = watch(); Rect timeline = new Rect();
        instrumentation.runOnMainSync(() -> assertTrue(activity.findViewById(R.id.progress).getGlobalVisibleRect(timeline)));
        control(R.id.progress, true);
        waitUntil("timeline click resumes", 5000, () -> state().playing);
        waitUntil("Pointer transport hides after resume", 6000, () -> !shown(R.id.controls));
        assertFalse("Clock clears the picture too", shown(R.id.player_clock));
        // Use the timeline's last visible bounds to move the real pointer over it.
        hoverMouse(timeline.centerX() + 30, timeline.centerY());
        waitUntil("Mouse movement restores controls", 3000, () -> shown(R.id.controls));
        waitUntil("Mouse movement restores clock", 2000, () -> shown(R.id.player_clock));
        waitUntil("Stationary mouse does not keep the timeline open", 6000, () -> !shown(R.id.controls));
        assertTrue("Video keeps playing after the overlay hides", state().playing);
    }

    private void hoverMouse(float x, float y) {
        long time = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
        pointer.id = 0; pointer.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords(); coords.x = x; coords.y = y;
        MotionEvent event = MotionEvent.obtain(time, time, MotionEvent.ACTION_HOVER_MOVE, 1,
                new MotionEvent.PointerProperties[]{pointer}, new MotionEvent.PointerCoords[]{coords},
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        assertTrue(instrumentation.getUiAutomation().injectInputEvent(event, true)); event.recycle();
    }

    private boolean popupOpen() { return visible("Settings") || visible("Audio & subtitles") || visible("Subtitle style"); }

    private boolean shown(int id) {
        java.util.concurrent.atomic.AtomicBoolean shown = new java.util.concurrent.atomic.AtomicBoolean();
        WatchActivity activity = watch();
        if (activity != null) instrumentation.runOnMainSync(() -> shown.set(activity.findViewById(id).getVisibility() == View.VISIBLE));
        return shown.get();
    }
}
