package tv.daniil.stream;

import static org.junit.Assert.*;
import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import tv.daniil.playback.PlaybackRequest;
import tv.daniil.playback.PlaybackSession;
import tv.daniil.playback.SubtitleDownloader;

/** Focused decoder checks: synthetic fixtures, real audio output/video frames and the actual torrent path. */
@RunWith(AndroidJUnit4.class)
@OptIn(markerClass = UnstableApi.class)
public final class DecoderPlaybackTest extends AppHarness {
    @Test public void bundledNativeDecodersArePresent() {
        for (String mime : new String[]{"audio/eac3", "audio/eac3-joc", "audio/ac3", "audio/vnd.dts", "audio/vnd.dts.hd",
                "audio/true-hd", "audio/mp4a-latm", "audio/mpeg", "audio/flac", "audio/alac", "audio/opus", "audio/vorbis",
                "video/avc", "video/hevc", "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01"}) {
            assertTrue("Bundled decoder missing: " + mime, StreamDecoders.supports(mime));
        }
        assertFalse(StreamDecoders.supports("video/unknown"));
    }

    @Test public void eac3SurroundPlaysOverBitTorrentWithoutChangingSource() throws Exception {
        open("codec-eac3");
        waitUntil("E-AC-3 decoded to audible PCM", 15_000, this::audioRendered);
        assertEquals("audio/eac3", state().audioFormat.sampleMimeType);
        assertTrue("Software decoder selected automatically", diagnostics().optString("audioDecoder").startsWith("ffmpeg"));
        assertTrue("The real torrent transferred bytes", diagnostics().getLong("totalDownload") > 0);
        assertEquals(1, diagnostics().getInt("sourceAttempts"));
        assertEquals(0, diagnostics().getJSONArray("sourceFailures").length());
        controlsAndSeek(false);
        evidence("eac3-torrent");
    }

    @Test public void commonAudioCodecsProducePcmAndSurviveSeeking() throws Exception {
        open("http-mkv");
        for (String codec : new String[]{"eac3", "ac3", "dts", "truehd", "aac", "mp3", "flac", "alac", "opus", "vorbis"}) {
            load(codec, true, false);
            waitUntil(codec + " PCM output", 15_000, this::audioRendered);
            assertTrue(codec + " uses FFmpeg", diagnostics().optString("audioDecoder").startsWith("ffmpeg"));
            controlsAndSeek(true);
            evidence(codec);
        }
    }

    @Test public void softwareVideoDecodesHighBitDepthAndModernCodecs() throws Exception {
        open("http-mkv");
        for (String codec : new String[]{"h264-10", "hevc-10", "vp8", "vp9-10", "av1-10"}) {
            // Hi10P must select software from actual format capabilities without forcing a decoder mode.
            load(codec, false, !codec.equals("h264-10"));
            assertTrue(codec + " uses FFmpeg", diagnostics().optString("videoDecoder").startsWith("ffmpeg"));
            assertTrue("Software audio/video must not be rejected by the source policy", state().error == null);
            controlsAndSeek(true);
            evidence(codec);
        }
    }

    @Test public void runtimeDecoderFailureRetriesSameMediaAndPreservesViewerState() throws Exception {
        open("http-mkv");
        WatchActivity activity = watch(); PlaybackSession session = AppFlowTest.session(activity);
        instrumentation.runOnMainSync(() -> {
            session.selectAudio(stateOnMain(activity).audioTracks.get(1));
            session.selectSubtitles(stateOnMain(activity).textTracks.get(0));
        });
        waitUntil("chosen voice-over", 10_000, () -> state().audio != null && state().audio.language.startsWith("ru") && state().subtitles != null);
        String subtitles = state().subtitles.id;
        instrumentation.runOnMainSync(() -> { session.setSpeed(1.25f); session.pause(); session.seekTo(6000); });
        waitUntil("paused before failure", 10_000, () -> !state().playWhenReady && state().positionMs >= 6000 && !state().buffering);
        injectFailure(false);
        waitUntil("audio decoder fallback while paused", 15_000, () -> state().error == null && manager(activity).getAudioMode() == DecoderMode.FFMPEG
                && diagnostics().optString("audioDecoder").startsWith("ffmpeg") && !state().buffering);
        assertFalse(state().playWhenReady); assertTrue(state().positionMs >= 6000);
        assertEquals(1.25f, state().speed, 0.001f);
        assertTrue("Voice-over retained", state().audio.language.startsWith("ru"));
        assertEquals("Subtitle selection retained", subtitles, state().subtitles.id);
        instrumentation.runOnMainSync(session::play);
        waitUntil("fallback resumes with audio", 10_000, () -> state().playing && state().positionMs > 6500 && audioRendered());
        injectFailure(true);
        waitUntil("video decoder fallback on the same source", 15_000, () -> state().error == null
                && diagnostics().optString("videoDecoder").startsWith("ffmpeg") && state().playing && state().positionMs > 7000);
        assertEquals(1, diagnostics().getInt("sourceAttempts"));
        assertEquals(0, diagnostics().getJSONArray("sourceFailures").length());
        instrumentation.runOnMainSync(() -> {
            StreamDecoders decoders = decoders(activity);
            assertFalse("Software decoder failure must not loop", decoders.recover(failure(false, "FfmpegAudioRenderer", stateOnMain(activity).audioFormat)));
            assertFalse("An exhausted audio fallback must not loop", decoders.recover(failure(false, "MediaCodecAudioRenderer", stateOnMain(activity).audioFormat)));
            assertFalse("Network failure is not a decoder fallback", decoders.recover(new PlaybackException("network", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)));
            assertFalse("Encrypted content cannot fall back to FFmpeg", decoders.recover(failure(true, "MediaCodecVideoRenderer",
                    new Format.Builder().setSampleMimeType("video/avc").setCryptoType(C.CRYPTO_TYPE_FRAMEWORK).build())));
        });
        load("aac", false, false);
        assertEquals("A new load restores automatic selection", DecoderMode.AUTO, manager(activity).getVideoMode());
        assertEquals(DecoderMode.AUTO, manager(activity).getAudioMode());
        instrumentation.runOnMainSync(() -> {
            ExoPlayer player = session.player(); Format format = player.getAudioFormat();
            player.createMessage((type, payload) -> { throw ExoPlaybackException.createForRenderer(
                    new IllegalStateException("Injected audio output failure"), "MediaCodecAudioRenderer", 2,
                    format, C.FORMAT_HANDLED, null, false, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED); }).send();
        });
        waitUntil("audio output failure retries decoded PCM", 15_000, () -> state().error == null
                && manager(activity).getAudioMode() == DecoderMode.FFMPEG && state().playing && audioRendered());
    }

    private void open(String configuration) throws Exception {
        readyLibrary(configuration); launch(); tap("Generated Film", false); tap("Play", true); chooseFirstSource();
        waitUntil("initial frames: " + configuration, 45_000, () -> state() != null && state().error == null
                && state().renderedFirstFrame && state().playing && state().positionMs >= 500);
    }

    private void load(String codec, boolean audio, boolean video) {
        WatchActivity activity = watch();
        instrumentation.runOnMainSync(() -> {
            PlaybackSession session = AppFlowTest.session(activity);
            session.load(new PlaybackRequest.Builder(Uri.parse(origin + "/streaming/media/codecs/" + codec + ".mkv"), codec)
                    .subtitles(Collections.singletonList(new SubtitleDownloader.Source(origin + "/addon/subtitle.vtt", "text/vtt", "en", "Generated subtitle"))).build());
            DecoderManager manager = manager(activity);
            if (audio) manager.selectAudioDecoder(DecoderMode.FFMPEG);
            if (video) manager.selectVideoDecoder(DecoderMode.FFMPEG);
        });
        waitUntil(codec + " rendered frames", 20_000, () -> state().error == null && state().renderedFirstFrame && state().playing && state().positionMs >= 500);
    }

    private void controlsAndSeek(boolean externalSubtitles) {
        PlaybackSession session = AppFlowTest.session(watch());
        instrumentation.runOnMainSync(() -> { session.pause(); session.seekTo(6500); });
        waitUntil("paused seek", 10_000, () -> state().error == null && !state().playWhenReady && !state().buffering && state().positionMs >= 6500);
        instrumentation.runOnMainSync(() -> session.seekTo(1500));
        waitUntil("backward seek", 10_000, () -> state().error == null && !state().buffering && state().positionMs >= 1500 && state().positionMs < 4000);
        instrumentation.runOnMainSync(session::play);
        waitUntil("sound and picture after seek", 10_000, () -> state().error == null && state().playing && state().positionMs >= 2200 && audioRendered());
        if (externalSubtitles) assertFalse("External subtitle track retained", state().textTracks.isEmpty());
    }

    private boolean audioRendered() {
        java.util.concurrent.atomic.AtomicBoolean result = new java.util.concurrent.atomic.AtomicBoolean();
        WatchActivity activity = watch(); if (activity == null) return false;
        instrumentation.runOnMainSync(() -> {
            androidx.media3.exoplayer.DecoderCounters counters = AppFlowTest.session(activity).player().getAudioDecoderCounters();
            if (counters != null) { counters.ensureUpdated(); result.set(counters.renderedOutputBufferCount > 0); }
        });
        return result.get();
    }

    private void injectFailure(boolean video) {
        WatchActivity activity = watch();
        instrumentation.runOnMainSync(() -> {
            ExoPlayer player = AppFlowTest.session(activity).player();
            Format format = video ? player.getVideoFormat() : player.getAudioFormat();
            player.createMessage((type, payload) -> { throw failure(video, video ? "MediaCodecVideoRenderer" : "MediaCodecAudioRenderer", format); }).send();
        });
    }

    private static ExoPlaybackException failure(boolean video, String renderer, Format format) {
        return ExoPlaybackException.createForRenderer(new IllegalStateException("Injected decoder failure"), renderer,
                video ? 0 : 2, format, C.FORMAT_HANDLED, null, false, PlaybackException.ERROR_CODE_DECODING_FAILED);
    }
    private static tv.daniil.playback.PlaybackSnapshot stateOnMain(WatchActivity activity) { return activity.playbackSnapshot(); }
    private static StreamDecoders decoders(WatchActivity activity) { return (StreamDecoders) field(AppFlowTest.session(activity), "decoders"); }
    private static DecoderManager manager(WatchActivity activity) { return (DecoderManager) field(decoders(activity), "manager"); }
    private static Object field(Object object, String name) {
        try { java.lang.reflect.Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void evidence(String name) throws Exception {
        try (java.io.FileWriter out = new java.io.FileWriter(new java.io.File(context.getFilesDir(), "validation-decoder-" + name + ".json"))) {
            out.write(diagnostics().toString());
        }
    }
}
