package tv.stream.app;

import static org.junit.Assert.*;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import tv.stream.playback.PlaybackSession;
import tv.stream.playback.PlaybackSnapshot;
import tv.stream.torrent.TorrentEngine;

/** Actual system screensaver, sleep/wake and Home transitions with real generated torrent playback. */
public final class PlayerLifecycleTest extends AppHarness {
    @Test public void pausedFilmSurvivesScreensaverAndSleepWithTheSameDownloadedCache() throws Exception {
        playFilm(); WatchActivity activity = watch();
        instrumentation.runOnMainSync(() -> {
            PlaybackSession player = AppFlowTest.session(activity);
            player.pause(); player.setSpeed(1.5f); player.seekTo(6000);
        });
        waitUntil("paused film ready", 10000, () -> !state().playWhenReady && !state().buffering && state().positionMs >= 6000);
        WarmPlayer warm = new WarmPlayer(activity);
        try {
            // Android TV's exported sleep action starts its real configured screensaver without root.
            shell("am start -a com.google.android.pano.action.SLEEP");
            waitUntil("screensaver covers the player", 10000, () -> stopped(activity));
            warm.assertRetained();
            shell("input keyevent KEYCODE_WAKEUP");
            waitUntil("player returns from screensaver", 10000, () -> watch() == activity);
            warm.assertReturned();

            shell("cmd power sleep");
            PowerManager power = context.getSystemService(PowerManager.class);
            waitUntil("TV sleeps with the player stopped", 10000, () -> !power.isInteractive() && stopped(activity));
            SystemClock.sleep(1500);
            warm.assertRetained();
            shell("cmd power wakeup");
            waitUntil("player returns after wake", 10000, () -> power.isInteractive() && watch() == activity);
            warm.assertReturned();
            warm.resumeAndVerifyFrames();
            instrumentation.runOnMainSync(activity::finish);
            waitUntil("closing film releases downloaded cache", 10000, () -> !warm.payload.exists());
        } finally {
            shell("input keyevent KEYCODE_WAKEUP");
        }
    }

    @Test public void homePausesPlaybackAndRetainsItUntilTheStoppedPlayerIsClosed() throws Exception {
        playFilm(); WatchActivity activity = watch();
        pressHome();
        waitUntil("Home stops the player", 10000, () -> stopped(activity));
        WarmPlayer warm = new WarmPlayer(activity);
        warm.assertRetained();
        assertFalse("Home pauses rather than playing in the background", snapshot(activity).playWhenReady);
        shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n tv.stream.app/.MainActivity");
        waitUntil("launcher restores the same film", 10000, () -> watch() == activity);
        warm.assertReturned(); warm.resumeAndVerifyFrames();
        pressHome(); waitUntil("player stopped again", 10000, () -> stopped(activity));
        instrumentation.runOnMainSync(activity::finish);
        waitUntil("finishing while stopped releases the cache", 10000, () -> !warm.payload.exists());
    }

    private final class WarmPlayer {
        final WatchActivity activity;
        final PlaybackSession player;
        final PlaybackCoordinator coordinator;
        final TorrentEngine engine;
        final long torrent, position, downloaded, verified;
        final float speed;
        final int generation, attempts;
        final File payload;
        WarmPlayer(WatchActivity activity) throws Exception {
            this.activity = activity; player = AppFlowTest.session(activity);
            java.lang.reflect.Field field = WatchActivity.class.getDeclaredField("coordinator"); field.setAccessible(true);
            coordinator = (PlaybackCoordinator) field.get(activity); engine = coordinator.engine; torrent = coordinator.torrent;
            assertNotNull("A real torrent engine is active", engine); assertTrue(torrent != 0);
            JSONObject stats = stats(); downloaded = stats.getLong("totalDownload"); verified = stats.getLong("verifiedReadBytes");
            assertTrue("Actual torrent data was read", verified > 0); assertTrue(downloaded > 0);
            File[] files = new File(context.getCacheDir(), "payload").listFiles();
            assertNotNull(files); assertEquals("One playback payload", 1, files.length); payload = files[0];
            PlaybackSnapshot state = snapshot(activity); position = state.positionMs; speed = state.speed;
            AtomicReference<Integer> revision = new AtomicReference<>();
            AtomicReference<JSONObject> diagnostics = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> { revision.set(player.generation()); diagnostics.set(activity.diagnosticSnapshot()); });
            generation = revision.get(); attempts = diagnostics.get().getInt("sourceAttempts");
        }
        JSONObject stats() throws Exception { return app.playbackTelemetry.submit(() -> engine.stats(torrent)).get(); }
        void assertRetained() throws Exception {
            instrumentation.runOnMainSync(() -> {
                assertSame("Media3 was not recreated", player, AppFlowTest.session(activity));
                assertEquals("Media was not reloaded", generation, player.generation());
                assertSame("Native engine is retained", engine, coordinator.engine);
                assertEquals("Torrent handle is retained", torrent, coordinator.torrent);
                assertFalse(player.snapshot().playWhenReady);
                assertEquals("Paused position is retained", position, player.snapshot().positionMs, 250);
                assertEquals(speed, player.snapshot().speed, .001f);
                assertEquals("No new source resolution", attempts, activity.diagnosticSnapshot().optInt("sourceAttempts", -1));
            });
            assertTrue("Payload was not deleted", payload.isDirectory());
            JSONObject stats = stats();
            assertTrue("Download counters did not restart", stats.getLong("totalDownload") >= downloaded);
            assertTrue("Verified cache reads did not restart", stats.getLong("verifiedReadBytes") >= verified);
            assertTrue("Downloaded storage stays bounded", stats.getJSONObject("cache").getLong("allocatedBytes") <= (16L << 20));
        }
        void assertReturned() throws Exception {
            assertRetained();
            instrumentation.runOnMainSync(() -> {
                assertNull(player.snapshot().error);
                assertFalse("Returning does not show the loading page", activity.findViewById(R.id.loading).isShown());
            });
        }
        void resumeAndVerifyFrames() {
            AtomicReference<Integer> before = new AtomicReference<>(0);
            instrumentation.runOnMainSync(() -> before.set(player.player().getVideoDecoderCounters().renderedOutputBufferCount));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY);
            waitUntil("retained film actually renders new frames", 10000, () -> {
                AtomicReference<Boolean> advancing = new AtomicReference<>(false);
                instrumentation.runOnMainSync(() -> {
                    androidx.media3.exoplayer.DecoderCounters counters = player.player().getVideoDecoderCounters();
                    counters.ensureUpdated();
                    advancing.set(player.snapshot().playing && player.snapshot().positionMs > position + 500
                            && counters.renderedOutputBufferCount > before.get());
                });
                return advancing.get();
            });
        }
    }
    private PlaybackSnapshot snapshot(WatchActivity activity) {
        AtomicReference<PlaybackSnapshot> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(activity.playbackSnapshot())); return result.get();
    }
    private boolean stopped(WatchActivity activity) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> result.set(ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) == Stage.STOPPED));
        return result.get();
    }
    private void pressHome() {
        long now = SystemClock.uptimeMillis();
        assertTrue(instrumentation.getUiAutomation().injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0), true));
        assertTrue(instrumentation.getUiAutomation().injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0), true));
    }
    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor.AutoCloseInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand(command));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int size; while ((size = in.read(buffer)) != -1) out.write(buffer, 0, size);
            String output = new String(out.toByteArray(), StandardCharsets.UTF_8);
            assertFalse(output, output.contains("Error:") || output.contains("Exception"));
        }
    }
}
