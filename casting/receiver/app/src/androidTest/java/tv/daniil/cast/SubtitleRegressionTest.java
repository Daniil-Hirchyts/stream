package tv.daniil.cast;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;

import androidx.media3.common.Player;
import androidx.media3.common.text.Cue;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.SubtitleView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import tv.daniil.playback.PlaybackSession;
import tv.daniil.playback.R;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertTrue;

/** Runs real Media3 playback against an HTTP fixture served only inside the emulator. */
@RunWith(AndroidJUnit4.class)
public final class SubtitleRegressionTest {
    private ActivityScenario<PlayerActivity> scenario;
    private PlayerActivity screen;
    private SubtitleFixtureServer fixtures;
    private static final String[] FORMATS = {"vtt", "srt", "ass"};

    @Before public void open() throws Exception {
        fixtures = new SubtitleFixtureServer();
        scenario = ActivityScenario.launch(PlayerActivity.class);
        scenario.onActivity(activity -> screen = activity);
    }

    @After public void close() throws Exception {
        if (scenario != null) {
            send(new JSONObject().put("action", "stop"));
            scenario.close();
        }
        if (fixtures != null) fixtures.close();
    }

    @Test public void longCueSurvivesForwardAndBackwardSeekAndExpires() throws Exception {
        for (String format : FORMATS) {
            load(format, 0, "0");
            expectCue("Long " + format);
            send(new JSONObject().put("action", "pause"));
            seek(85_000);
            expectCue("Long " + format);
            screenshot(format + "-seek-85s");
            seek(5_000);
            expectCue("Long " + format);
            send(new JSONObject().put("action", "play"));
            seek(85_345);
            expectCue("Long " + format);
            screenshot(format + "-playing-seek");
            send(new JSONObject().put("action", "pause"));
            seek(131_000);
            expectCue("");
        }
    }

    @Test public void resumedLoadDisplaysTheAlreadyActiveCue() throws Exception {
        for (String format : FORMATS) {
            load(format, 85_000, "0");
            expectCue("Long " + format);
            screenshot(format + "-resumed-85s");
        }
    }

    @Test public void enablingAndSwitchingTracksMidCueDisplaysCurrentText() throws Exception {
        for (String format : FORMATS) {
            load(format, 85_000, "off");
            expectCue("");
            String first = track(0), alternate = track(1);
            send(new JSONObject().put("action", "subtitles").put("track", first));
            expectCue("Long " + format);
            send(new JSONObject().put("action", "subtitles").put("track", alternate));
            expectCue("Alternate long cue");
            screenshot(format + "-late-selection");
            send(new JSONObject().put("action", "subtitles").put("track", "off"));
            expectCue("");
            send(new JSONObject().put("action", "subtitles").put("track", first));
            expectCue("Long " + format);
        }
    }

    private void load(String format, long position, String subtitle) throws Exception {
        JSONArray tracks = new JSONArray()
                .put(new JSONObject().put("url", fixtures.url("long." + format))
                        .put("mime", format.equals("vtt") ? "text/vtt" : format.equals("srt")
                                ? "application/x-subrip" : "text/x-ssa")
                        .put("language", "en").put("label", "English"))
                .put(new JSONObject().put("url", fixtures.url("alternate.vtt"))
                        .put("mime", "text/vtt").put("language", "fr").put("label", "French"));
        send(new JSONObject().put("action", "load").put("url", fixtures.url("video.mp4"))
                .put("mime", "video/mp4").put("title", "Local subtitle regression · " + format)
                .put("position", position).put("subtitles", tracks).put("subtitle", subtitle));
        waitFor("rendered playback for " + format, player -> {
            try {
                return player.getPlaybackState() == Player.STATE_READY && player.getVideoFormat() != null
                        && player.getDuration() > 140_000 && CastHub.status().optBoolean("renderedVideo")
                        && screen.findViewById(R.id.loading).getVisibility() == View.GONE;
            } catch (Exception exception) { throw new AssertionError(exception); }
        });
    }

    private void seek(long position) throws Exception {
        send(new JSONObject().put("action", "seek").put("position", position));
        waitFor("seek to " + position, player -> player.getPlaybackState() == Player.STATE_READY
                && Math.abs(player.getCurrentPosition() - position) < 3_000);
    }

    private String track(int index) throws Exception {
        return CastHub.status().getJSONArray("textTracks").getJSONObject(index).getString("id");
    }

    private void send(JSONObject command) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { screen.command(command); }
            catch (Exception exception) { throw new AssertionError(exception); }
        });
    }

    private interface Check { boolean matches(ExoPlayer player); }

    private void waitFor(String description, Check check) throws Exception {
        Field field = PlayerActivity.class.getDeclaredField("session");
        field.setAccessible(true);
        long deadline = SystemClock.elapsedRealtime() + 12_000;
        AtomicBoolean matched = new AtomicBoolean();
        while (SystemClock.elapsedRealtime() < deadline) {
            // A preparing spinner may animate continuously. Read on the main thread
            // without ActivityScenario.onActivity's wait-for-idle gate.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try { matched.set(check.matches(((PlaybackSession) field.get(screen)).player())); }
                catch (Exception exception) { throw new AssertionError(exception); }
            });
            if (matched.get()) return;
            SystemClock.sleep(50);
        }
        screenshot("failure");
        assertTrue(description, matched.get());
    }

    private void expectCue(String expected) throws Exception {
        Field field = SubtitleView.class.getDeclaredField("cues");
        field.setAccessible(true);
        waitFor("visible cue: " + expected, player -> {
            StringBuilder text = new StringBuilder();
            try {
                @SuppressWarnings("unchecked") List<Cue> visible =
                        (List<Cue>) field.get(screen.findViewById(R.id.subtitles));
                for (Cue cue : visible) if (cue.text != null) text.append(cue.text);
            } catch (IllegalAccessException exception) { throw new AssertionError(exception); }
            return expected.isEmpty() ? text.length() == 0 : text.toString().contains(expected);
        });
    }

    private void screenshot(String name) throws Exception {
        try {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(250, 2000);
        } catch (java.util.concurrent.TimeoutException ignored) { /* A ticking time label need not block a capture. */ }
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getExternalFilesDir(null), "subtitle-regression");
        directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }
}
