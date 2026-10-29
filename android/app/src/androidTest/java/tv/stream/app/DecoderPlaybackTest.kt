package tv.stream.app

import org.junit.Assert.*
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import java.util.Collections
import org.junit.Test
import org.junit.runner.RunWith
import tv.stream.playback.PlaybackRequest
import tv.stream.playback.PlaybackSession
import tv.stream.playback.SubtitleDownloader

/** Focused decoder checks: synthetic fixtures, real audio output/video frames and the actual torrent path. */
@RunWith(AndroidJUnit4::class)
@OptIn(markerClass = [UnstableApi::class])
class DecoderPlaybackTest : AppHarness() {
    @Test fun bundledNativeDecodersArePresent() {
        for (mime in arrayOf("audio/eac3", "audio/eac3-joc", "audio/ac3", "audio/vnd.dts", "audio/vnd.dts.hd",
                "audio/true-hd", "audio/mp4a-latm", "audio/mpeg", "audio/flac", "audio/alac", "audio/opus", "audio/vorbis",
                "video/avc", "video/hevc", "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01", "video/mp4v-es", "video/mp42", "video/mp43", "video/wvc1", "video/mjpeg", "audio/mpeg-L1", "audio/mpeg-L2")) {
            assertTrue("Bundled decoder missing: $mime", StreamDecoders.supports(mime))
        }
        assertFalse(StreamDecoders.supports("video/unknown"))
    }

    @Test @Throws(Exception::class) fun eac3SurroundPlaysOverBitTorrentWithoutChangingSource() {
        open("codec-eac3")
        waitUntil("E-AC-3 decoded to audible PCM", 15_000) { audioRendered() }
        assertEquals("audio/eac3", state()!!.audioFormat!!.sampleMimeType)
        assertTrue("Software decoder selected automatically", diagnostics().optString("audioDecoder").startsWith("ffmpeg"))
        assertTrue("The real torrent transferred bytes", diagnostics().getLong("totalDownload") > 0)
        assertEquals(1, diagnostics().getInt("sourceAttempts").toLong())
        assertEquals(0, diagnostics().getJSONArray("sourceFailures").length().toLong())
        controlsAndSeek(false)
        evidence("eac3-torrent")
    }

    @Test @Throws(Exception::class) fun commonAudioCodecsProducePcmAndSurviveSeeking() {
        open("http-mkv")
        for (codec in arrayOf("eac3", "ac3", "dts", "truehd", "aac", "mp3", "flac", "alac", "opus", "vorbis")) {
            load(codec, true, false)
            waitUntil("$codec PCM output", 15_000) { audioRendered() }
            assertTrue("$codec uses FFmpeg", diagnostics().optString("audioDecoder").startsWith("ffmpeg"))
            controlsAndSeek(true)
            evidence(codec)
        }
    }

    @Test @Throws(Exception::class) fun softwareVideoDecodesHighBitDepthAndModernCodecs() {
        open("http-mkv")
        for (codec in arrayOf("h264-10", "hevc-10", "vp8", "vp9-10", "av1-10")) {
            // Hi10P must select software from actual format capabilities without forcing a decoder mode.
            load(codec, false, codec != "h264-10")
            assertTrue("$codec uses FFmpeg", diagnostics().optString("videoDecoder").startsWith("ffmpeg"))
            assertTrue("Software audio/video must not be rejected by the source policy", state()!!.error == null)
            controlsAndSeek(true)
            evidence(codec)
        }
    }

    @Test @Throws(Exception::class) fun runtimeDecoderFailureRetriesSameMediaAndPreservesViewerState() {
        open("http-mkv")
        val activity = watch(); val session = AppFlowTest.session(activity)
        instrumentation.runOnMainSync {
            session.selectAudio(stateOnMain(activity)!!.audioTracks[1])
            session.selectSubtitles(stateOnMain(activity)!!.textTracks[0])
        }
        waitUntil("chosen voice-over", 10_000) { state()!!.audio != null && state()!!.audio!!.language!!.startsWith("ru") && state()!!.subtitles != null }
        val subtitles = state()!!.subtitles!!.id
        instrumentation.runOnMainSync { session.setSpeed(1.25f); session.pause(); session.seekTo(6000) }
        waitUntil("paused before failure", 10_000) { !state()!!.playWhenReady && state()!!.positionMs >= 6000 && !state()!!.buffering }
        injectFailure(false)
        waitUntil("audio decoder fallback while paused", 15_000) { state()!!.error == null && manager(activity).audioMode == DecoderMode.FFMPEG
                && diagnostics().optString("audioDecoder").startsWith("ffmpeg") && !state()!!.buffering }
        assertFalse(state()!!.playWhenReady); assertTrue(state()!!.positionMs >= 6000)
        assertEquals(1.25f, state()!!.speed, 0.001f)
        assertTrue("Voice-over retained", state()!!.audio!!.language!!.startsWith("ru"))
        assertEquals("Subtitle selection retained", subtitles, state()!!.subtitles!!.id)
        instrumentation.runOnMainSync { session.play() }
        waitUntil("fallback resumes with audio", 10_000) { state()!!.playing && state()!!.positionMs > 6500 && audioRendered() }
        injectFailure(true)
        waitUntil("video decoder fallback on the same source", 15_000) { state()!!.error == null
                && diagnostics().optString("videoDecoder").startsWith("ffmpeg") && state()!!.playing && state()!!.positionMs > 7000 }
        assertEquals(1, diagnostics().getInt("sourceAttempts").toLong())
        assertEquals(0, diagnostics().getJSONArray("sourceFailures").length().toLong())
        instrumentation.runOnMainSync {
            val decoders = decoders(activity)
            assertFalse("Software decoder failure must not loop", decoders.recover(failure(false, "FfmpegAudioRenderer", stateOnMain(activity)!!.audioFormat)))
            assertFalse("An exhausted audio fallback must not loop", decoders.recover(failure(false, "MediaCodecAudioRenderer", stateOnMain(activity)!!.audioFormat)))
            assertFalse("Network failure is not a decoder fallback", decoders.recover(PlaybackException("network", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
            assertFalse("Encrypted content cannot fall back to FFmpeg", decoders.recover(failure(true, "MediaCodecVideoRenderer",
                    Format.Builder().setSampleMimeType("video/avc").setCryptoType(C.CRYPTO_TYPE_FRAMEWORK).build())))
        }
        load("aac", false, false)
        assertEquals("A new load restores automatic selection", DecoderMode.AUTO, manager(activity).videoMode)
        assertEquals(DecoderMode.AUTO, manager(activity).audioMode)
        instrumentation.runOnMainSync {
            val player: ExoPlayer = session.player(); val format = player.audioFormat
            player.createMessage { type, payload -> throw ExoPlaybackException.createForRenderer(
                    IllegalStateException("Injected audio output failure"), "MediaCodecAudioRenderer", 2,
                    format, C.FORMAT_HANDLED, null, false, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) }.send()
        }
        waitUntil("audio output failure retries decoded PCM", 15_000) { state()!!.error == null
                && manager(activity).audioMode == DecoderMode.FFMPEG && state()!!.playing && audioRendered() }
        instrumentation.runOnMainSync {
            val player: ExoPlayer = session.player(); val format = player.audioFormat
            player.createMessage { type, payload -> throw ExoPlaybackException.createForRenderer(
                    IllegalStateException("Injected multichannel PCM output failure"), "FfmpegAudioRenderer", 3,
                    format, C.FORMAT_HANDLED, null, false, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) }.send()
        }
        waitUntil("PCM output retries stereo once", 15000) { java.lang.Boolean.TRUE == field(decoders(activity), "retriedStereo")
                && state()!!.error == null && state()!!.playing && audioRendered() }
    }

    @Test @Throws(Exception::class) fun surroundAudioCanBeDownmixedToRealStereoPcm() {
        io.github.anilbeesetti.nextlib.media3ext.ffdecoder.StereoDecoderProbe.check("$origin/media/codecs/eac3.ec3")
    }

    @Test @Throws(Exception::class) fun legacyAviCodecsAnd1080pVideoRenderFreshFramesAfterSeeking() {
        open("http-mkv")
        for (codec in arrayOf("xvid", "mp42", "mp43", "mjpeg", "sdr-1080")) {
            load(codec, false, true); controlsAndSeek(true); evidence(codec)
        }
    }
    @Test @Throws(Exception::class) fun lateSubtitlesKeepTheMediaAndSuccessfulDecoderFallback() {
        open("http-mkv")
        val activity = watch(); val session = AppFlowTest.session(activity)
        injectFailure(false)
        waitUntil("FFmpeg audio fallback", 15000) { manager(activity).audioMode == DecoderMode.FFMPEG && state()!!.playing }
        val generation = java.util.concurrent.atomic.AtomicInteger()
        instrumentation.runOnMainSync {
            generation.set(session.generation())
            session.attachExternalSubtitles(Collections.singletonList(SubtitleDownloader.Source("$origin/addon/subtitle.vtt", "text/vtt", "en", "Late subtitle")), Collections.emptyMap(), true)
        }
        waitUntil("external subtitle attached", 10000) { state()!!.subtitles != null && state()!!.subtitles!!.external }
        instrumentation.runOnMainSync {
            assertEquals("Media was not replaced", generation.get().toLong(), session.generation().toLong())
            assertEquals("Working fallback retained", DecoderMode.FFMPEG, manager(activity).audioMode)
            session.pause(); session.seekTo(1500)
        }
        waitUntil("paused late cue", 10000) { !state()!!.buffering }
        instrumentation.runOnMainSync {
            try {
                val cues = PlaybackSession::class.java.getDeclaredMethod("cues"); cues.isAccessible = true
                assertFalse("Cue already active at seek target", (cues.invoke(session) as List<*>).isEmpty())
            } catch (error: ReflectiveOperationException) { throw AssertionError(error) }
        }
        val another = "$origin/addon/subtitle.vtt?late=second"
        instrumentation.runOnMainSync {
            session.attachExternalSubtitles(Collections.singletonList(SubtitleDownloader.Source(another, "text/vtt", "en", "Second late subtitle")), Collections.emptyMap(), true)
            session.selectSubtitles(null)
        }
        waitUntil("second caption download", 10000) {
            val present = java.util.concurrent.atomic.AtomicBoolean()
            instrumentation.runOnMainSync { present.set(session.hasExternalSubtitle(another)) }; present.get()
        }
        assertNull("Late completion respects explicit Off", state()!!.subtitles)

    }

    @Test @Throws(Exception::class) fun lateSubtitleBatchesKeepSelectionAndTheLatestDownloadIntent() {
        open("http-mkv"); val session = AppFlowTest.session(watch())
        val initial = "$origin/addon/subtitle.vtt?batch=initial"
        instrumentation.runOnMainSync { session.attachExternalSubtitles(Collections.singletonList(SubtitleDownloader.Source(initial, "text/vtt", "en", "Initial")), Collections.emptyMap(), true) }
        waitUntil("initial late track", 10000) { state()!!.subtitles != null && state()!!.subtitles!!.external }
        val batch: MutableList<SubtitleDownloader.Source> = java.util.ArrayList()
        for (i in 0 until 8) batch.add(SubtitleDownloader.Source("$origin/addon/subtitle.vtt?batch=$i", "text/vtt", "en", "Batch $i"))
        val completed = java.util.concurrent.atomic.AtomicBoolean()
        instrumentation.runOnMainSync { session.attachExternalSubtitles(batch, Collections.emptyMap(), true) { value -> completed.set(value) } }
        waitUntil("batch complete", 10000) { completed.get() }
        assertNotNull("Selected timeline survives eviction", state()!!.subtitles); assertEquals("Batch 0", state()!!.subtitles!!.label)
        assertEquals(8, state()!!.textTracks.stream().filter { track -> track.external }.count())
        val olderSelected = java.util.concurrent.atomic.AtomicBoolean(); val latest = java.util.concurrent.atomic.AtomicBoolean()
        instrumentation.runOnMainSync {
            session.attachExternalSubtitles(Collections.singletonList(SubtitleDownloader.Source("$origin/addon/subtitle.vtt?choice=older", "text/vtt", "en", "Older")), Collections.emptyMap(), true
            ) { loaded -> olderSelected.set(session.snapshot().subtitles != null && session.snapshot().subtitles!!.label == "Older") }
            session.attachExternalSubtitles(Collections.singletonList(SubtitleDownloader.Source("$origin/addon/subtitle.vtt?choice=latest", "text/vtt", "en", "Latest")), Collections.emptyMap(), true) { value -> latest.set(value) }
        }
        waitUntil("latest download selected", 10000) { latest.get() }
        assertFalse("An earlier completion must not change the newer selection intent", olderSelected.get())
        assertEquals("Latest", state()!!.subtitles!!.label)
    }

    @Throws(Exception::class)
    private fun open(configuration: String) {
        readyLibrary(configuration); launch(); tap("Generated Film", false); tap("Play", true); chooseFirstSource()
        waitUntil("initial frames: $configuration", 45_000) { state() != null && state()!!.error == null
                && state()!!.renderedFirstFrame && state()!!.playing && state()!!.positionMs >= 500 }
    }

    private fun load(codec: String, audio: Boolean, video: Boolean) {
        val activity = watch()
        instrumentation.runOnMainSync {
            val session = AppFlowTest.session(activity)
            session.load(PlaybackRequest.Builder(Uri.parse(origin + "/media/codecs/" + codec + (if (java.util.Arrays.asList("xvid", "mp42", "mp43", "mjpeg").contains(codec)) ".avi" else ".mkv")), codec)
                    .subtitles(Collections.singletonList(SubtitleDownloader.Source("$origin/addon/subtitle.vtt", "text/vtt", "en", "Generated subtitle"))).build())
            val manager = manager(activity)
            if (audio) manager.selectAudioDecoder(DecoderMode.FFMPEG)
            if (video) manager.selectVideoDecoder(DecoderMode.FFMPEG)
        }
        waitUntil("$codec rendered frames", 20_000) { state()!!.error == null && state()!!.renderedFirstFrame && state()!!.playing && state()!!.positionMs >= 500 }
    }

    private fun controlsAndSeek(externalSubtitles: Boolean) {
        val session = AppFlowTest.session(watch())
        instrumentation.runOnMainSync { session.pause(); session.seekTo(6500) }
        waitUntil("paused seek", 10_000) { state()!!.error == null && !state()!!.playWhenReady && !state()!!.buffering && state()!!.positionMs >= 6500 }
        instrumentation.runOnMainSync { session.seekTo(1500) }
        waitUntil("backward seek", 10_000) { state()!!.error == null && !state()!!.buffering && state()!!.positionMs >= 1500 && state()!!.positionMs < 4000 }
        val rendered = java.util.concurrent.atomic.AtomicInteger()
        instrumentation.runOnMainSync { val counter: androidx.media3.exoplayer.DecoderCounters? = session.player().videoDecoderCounters
            rendered.set(if (counter == null) 0 else counter.renderedOutputBufferCount); session.play() }
        waitUntil("fresh video frames after seek", 10000) {
            val fresh = java.util.concurrent.atomic.AtomicBoolean()
            instrumentation.runOnMainSync { val counter: androidx.media3.exoplayer.DecoderCounters? = session.player().videoDecoderCounters
                fresh.set(counter != null && counter.renderedOutputBufferCount > rendered.get()) }
            fresh.get()
        }
        waitUntil("sound and picture after seek", 10_000) { state()!!.error == null && state()!!.playing && state()!!.positionMs >= 2200 && audioRendered() }
        if (externalSubtitles) assertFalse("External subtitle track retained", state()!!.textTracks.isEmpty())
    }

    private fun audioRendered(): Boolean {
        val result = java.util.concurrent.atomic.AtomicBoolean()
        val activity = watch(); if (activity == null) return false
        instrumentation.runOnMainSync {
            val counters: androidx.media3.exoplayer.DecoderCounters? = AppFlowTest.session(activity).player().audioDecoderCounters
            if (counters != null) { counters.ensureUpdated(); result.set(counters.renderedOutputBufferCount > 0) }
        }
        return result.get()
    }

    private fun injectFailure(video: Boolean) {
        val activity = watch()
        instrumentation.runOnMainSync {
            val player: ExoPlayer = AppFlowTest.session(activity).player()
            val format = if (video) player.videoFormat else player.audioFormat
            player.createMessage { type, payload -> throw failure(video, if (video) "MediaCodecVideoRenderer" else "MediaCodecAudioRenderer", format) }.send()
        }
    }

    @Throws(Exception::class)
    private fun evidence(name: String) {
        java.io.FileWriter(java.io.File(context.filesDir, "validation-decoder-$name.json")).use { out ->
            out.write(diagnostics().toString())
        }
    }

    companion object {
        @JvmStatic private fun failure(video: Boolean, renderer: String, format: Format?): ExoPlaybackException {
            return ExoPlaybackException.createForRenderer(IllegalStateException("Injected decoder failure"), renderer,
                    if (video) 0 else 2, format, C.FORMAT_HANDLED, null, false, PlaybackException.ERROR_CODE_DECODING_FAILED)
        }
        @JvmStatic private fun stateOnMain(activity: WatchActivity?): tv.stream.playback.PlaybackSnapshot? { return activity!!.playbackSnapshot() }
        @JvmStatic private fun decoders(activity: WatchActivity?): StreamDecoders { return field(AppFlowTest.session(activity), "decoders") as StreamDecoders }
        @JvmStatic private fun manager(activity: WatchActivity?): DecoderManager { return field(decoders(activity), "manager") as DecoderManager }
        @JvmStatic private fun field(`object`: Any?, name: String): Any? {
            try { val field = `object`!!.javaClass.getDeclaredField(name); field.isAccessible = true; return field.get(`object`) }
            catch (error: Exception) { throw AssertionError(error) }
        }
    }
}
