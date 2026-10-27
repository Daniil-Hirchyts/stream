package tv.stream.app;

import static org.junit.Assert.*;
import android.net.Uri;
import android.os.SystemClock;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode;
import org.junit.Test;
import org.junit.runner.RunWith;
import tv.stream.playback.PlaybackRequest;
import tv.stream.playback.PlaybackSession;

/** Real track EOS must not look like a decoder stall while another track keeps the media clock alive. */
@RunWith(AndroidJUnit4.class)
public final class VideoTrackEndTest extends AppHarness {
    @Test public void longerAudioFinishesWithPlatformAndSoftwareVideo() throws Exception { checkTail("audio"); }
    @Test public void longerSubtitlesFinishAndSeekingBackRestoresVideo() throws Exception { checkTail("subtitle"); }

    private void checkTail(String tail) throws Exception {
        readyLibrary("http-mkv"); startAutomaticFixturePlayback();
        waitUntil("initial video", 30_000, () -> state() != null && state().renderedFirstFrame);
        WatchActivity activity = watch(); PlaybackSession session = AppFlowTest.session(activity);
        for (boolean software : new boolean[]{false, true}) {
            instrumentation.runOnMainSync(() -> {
                session.load(new PlaybackRequest.Builder(Uri.parse(origin + "/media/video-tail-" + tail + ".mkv"), "Track tail").build());
                if (software) {
                    try {
                        java.lang.reflect.Field decoders = PlaybackSession.class.getDeclaredField("decoders"); decoders.setAccessible(true);
                        Object value = decoders.get(session);
                        java.lang.reflect.Field manager = StreamDecoders.class.getDeclaredField("manager"); manager.setAccessible(true);
                        ((DecoderManager) manager.get(value)).selectVideoDecoder(DecoderMode.FFMPEG);
                    } catch (Exception error) { throw new AssertionError(error); }
                }
            });
            waitUntil("new video frames", 15_000, () -> state().renderedFirstFrame && state().playing && state().positionMs > 500);
            if (tail.equals("subtitle")) {
                waitUntil("embedded subtitle track", 5000, () -> !state().textTracks.isEmpty());
                instrumentation.runOnMainSync(() -> session.selectSubtitles(activity.playbackSnapshot().textTracks.get(0)));
                waitUntil("subtitle selected", 5000, () -> state().subtitles != null);
            }
            assertTrue("The other track extends the media duration", state().durationMs >= 17_000);
            waitUntil("tail survives the watchdog interval", 15_000, () -> {
                assertHealthy("Finished video, software=" + software);
                return state().positionMs >= 10_000;
            });
            assertTrue("Longer track is still playing", state().playing);
            int frames = frames(session);
            SystemClock.sleep(1200);
            assertEquals("Video has stopped producing frames", frames, frames(session));
            instrumentation.runOnMainSync(() -> session.seekTo(1000));
            waitUntil("fresh frames after seeking out of the tail", 10_000, () -> frames(session) > frames && state().positionMs < 6000);
            waitUntil("normal completion after replaying video and its tail", 25_000, () -> {
                assertHealthy("Completion after seeking, software=" + software);
                return state().ended;
            });
            assertEquals(0, activity.diagnosticSnapshot().getJSONArray("sourceFailures").length());
        }
    }

    private void assertHealthy(String stage) {
        tv.stream.playback.PlaybackErrors.Description error = state().error;
        assertTrue(stage + (error == null ? "" : ": " + error.code + " · " + error.message + " · " + error.detail), error == null);
    }

    private int frames(PlaybackSession session) {
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        instrumentation.runOnMainSync(() -> {
            DecoderCounters counters = session.player().getVideoDecoderCounters();
            if (counters != null) { counters.ensureUpdated(); count.set(counters.renderedOutputBufferCount); }
        });
        return count.get();
    }
}
