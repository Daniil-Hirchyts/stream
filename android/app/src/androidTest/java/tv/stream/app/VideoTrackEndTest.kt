package tv.stream.app

import org.junit.Assert.*
import android.net.Uri
import android.os.SystemClock
import androidx.media3.exoplayer.DecoderCounters
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import org.junit.Test
import org.junit.runner.RunWith
import tv.stream.playback.PlaybackRequest
import tv.stream.playback.PlaybackSession

/** Real track EOS must not look like a decoder stall while another track keeps the media clock alive. */
@RunWith(AndroidJUnit4::class)
class VideoTrackEndTest : AppHarness() {
    @Test @Throws(Exception::class) fun longerAudioFinishesWithPlatformAndSoftwareVideo() { checkTail("audio") }
    @Test @Throws(Exception::class) fun longerSubtitlesFinishAndSeekingBackRestoresVideo() { checkTail("subtitle") }

    @Throws(Exception::class)
    private fun checkTail(tail: String) {
        readyLibrary("http-mkv"); startAutomaticFixturePlayback()
        AppHarness.waitUntil("initial video", 30_000) { state() != null && state()!!.renderedFirstFrame }
        val activity = watch(); val session = AppFlowTest.session(activity)
        for (software in booleanArrayOf(false, true)) {
            instrumentation.runOnMainSync {
                session.load(PlaybackRequest.Builder(Uri.parse(origin + "/media/video-tail-" + tail + ".mkv"), "Track tail").build())
                if (software) {
                    try {
                        val decoders = PlaybackSession::class.java.getDeclaredField("decoders"); decoders.isAccessible = true
                        val value = decoders.get(session)
                        val manager = StreamDecoders::class.java.getDeclaredField("manager"); manager.isAccessible = true
                        (manager.get(value) as DecoderManager).selectVideoDecoder(DecoderMode.FFMPEG)
                    } catch (error: Exception) { throw AssertionError(error) }
                }
            }
            AppHarness.waitUntil("new video frames", 15_000) { state()!!.renderedFirstFrame && state()!!.playing && state()!!.positionMs > 500 }
            if (tail == "subtitle") {
                AppHarness.waitUntil("embedded subtitle track", 5000) { !state()!!.textTracks.isEmpty() }
                instrumentation.runOnMainSync { session.selectSubtitles(activity!!.playbackSnapshot()!!.textTracks[0]) }
                AppHarness.waitUntil("subtitle selected", 5000) { state()!!.subtitles != null }
            }
            assertTrue("The other track extends the media duration", state()!!.durationMs >= 17_000)
            AppHarness.waitUntil("tail survives the watchdog interval", 15_000) {
                assertHealthy("Finished video, software=$software")
                state()!!.positionMs >= 10_000
            }
            assertTrue("Longer track is still playing", state()!!.playing)
            val frames = frames(session)
            SystemClock.sleep(1200)
            assertEquals("Video has stopped producing frames", frames.toLong(), frames(session).toLong())
            instrumentation.runOnMainSync { session.seekTo(1000) }
            AppHarness.waitUntil("fresh frames after seeking out of the tail", 10_000) { frames(session) > frames && state()!!.positionMs < 6000 }
            AppHarness.waitUntil("normal completion after replaying video and its tail", 25_000) {
                assertHealthy("Completion after seeking, software=$software")
                state()!!.ended
            }
            assertEquals(0, activity!!.diagnosticSnapshot().getJSONArray("sourceFailures").length().toLong())
        }
    }

    private fun assertHealthy(stage: String) {
        val error: tv.stream.playback.PlaybackErrors.Description? = state()!!.error
        assertTrue(stage + (if (error == null) "" else ": " + error.code + " · " + error.message + " · " + error.detail), error == null)
    }

    private fun frames(session: PlaybackSession): Int {
        val count = java.util.concurrent.atomic.AtomicInteger()
        instrumentation.runOnMainSync {
            val counters: DecoderCounters? = session.player().videoDecoderCounters
            if (counters != null) { counters.ensureUpdated(); count.set(counters.renderedOutputBufferCount) }
        }
        return count.get()
    }
}
