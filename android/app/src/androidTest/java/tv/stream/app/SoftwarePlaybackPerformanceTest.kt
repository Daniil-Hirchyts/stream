package tv.stream.app

import android.net.Uri
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.stream.playback.PlaybackRequest

/** Repeated local 1080p software playback, sampled over a real minute; run alone on the verified emulator. */
@RunWith(AndroidJUnit4::class)
class SoftwarePlaybackPerformanceTest : AppHarness() {
    @Test fun software1080pMemoryAndCpu() {
        playFilm()
        val session = AppFlowTest.session(watch())
        instrumentation.runOnMainSync {
            session.load(PlaybackRequest.Builder(Uri.parse("$origin/media/codecs/sdr-1080.mkv"), "software-profile").build())
            val decoders = field(session, "decoders")
            (field(decoders, "manager") as DecoderManager).selectVideoDecoder(DecoderMode.FFMPEG)
            session.player().repeatMode = Player.REPEAT_MODE_ONE
        }
        waitUntil("software 1080p playback", 30_000) {
            val s = state()
            s != null && s.error == null && s.playing && s.renderedFirstFrame &&
                s.videoFormat?.width == 1920 && s.videoFormat?.height == 1080 && s.positionMs >= 500 &&
                diagnostics().optString("videoDecoder").startsWith("ffmpeg")
        }
        val start = SystemClock.elapsedRealtime()
        val cpuStart = Process.getElapsedCpuTime()
        val samples = JSONArray()
        repeat(13) { sample ->
            val target = start + sample * 5_000L
            val remaining = target - SystemClock.elapsedRealtime()
            if (remaining > 0) SystemClock.sleep(remaining)
            val state = state()
            assertTrue("software playback stopped or failed at sample $sample", state != null && state.error == null && state.playWhenReady)
            val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
            val diagnostic = diagnostics()
            samples.put(JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - start)
                .put("processCpuMs", Process.getElapsedCpuTime() - cpuStart).put("pssKiB", memory.totalPss)
                .put("positionMs", state!!.positionMs).put("buffering", state.buffering)
                .put("droppedFrames", diagnostic.optInt("droppedFrames")))
        }
        val report = JSONObject().put("model", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS[0]).put("fixture", "generated 1080p H.264, 24 fps")
            .put("videoDecoder", diagnostics().optString("videoDecoder")).put("samples", samples)
        File(context.filesDir, "validation-software-performance.json").writeText(report.toString(2))
    }

    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        requireNotNull(it.get(owner))
    }
}
