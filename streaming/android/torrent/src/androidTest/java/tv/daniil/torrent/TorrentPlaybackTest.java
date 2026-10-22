package tv.daniil.torrent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.view.Surface;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Media3 playing torrents through TorrentDataSource, decoding to a real surface, with a bounded cache. */
@RunWith(AndroidJUnit4.class)
@OptIn(markerClass = UnstableApi.class)
public final class TorrentPlaybackTest {
    private static final long CACHE = 16L << 20;
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Fixtures fixtures;
    private TorrentEngine engine;
    private ExoPlayer player;
    private Surface surface;
    private SurfaceTexture texture;
    private final AtomicBoolean rendered = new AtomicBoolean();
    private final AtomicReference<PlaybackException> error = new AtomicReference<>();

    @Before public void open() throws Exception {
        fixtures = Fixtures.load();
        Context context = instrumentation.getTargetContext();
        TorrentEngine.Config config = TorrentEngine.Config.localOnly(new File(context.getCacheDir(), "torrent").getPath());
        config.cacheBytes = CACHE;
        config.reserveBytes = 0;
        engine = TorrentEngine.create(config);
        texture = new SurfaceTexture(false);
        surface = new Surface(texture);
    }

    @After public void close() {
        instrumentation.runOnMainSync(() -> {
            if (player != null) player.release();
        });
        if (engine != null) engine.close();
        surface.release();
        texture.release();
    }

    /** Starts playing a file of a torrent from the host seeder. */
    private long play(Fixtures.Torrent t, Fixtures.File f) throws Exception {
        long torrent = engine.add(TorrentEngine.Source.metainfo(fixtures.metainfo(t)).withPeers(fixtures.seeder));
        List<TorrentFile> files = engine.awaitMetadata(torrent, 20_000);
        TorrentFile file = null;
        for (TorrentFile candidate : files) if (candidate.index == f.index) file = candidate;
        engine.selectFile(torrent, f.index);
        TorrentFile chosen = file;
        instrumentation.runOnMainSync(() -> {
            player = new ExoPlayer.Builder(instrumentation.getTargetContext())
                    .setLoadControl(new DefaultLoadControl.Builder().setTargetBufferBytes(24 << 20)
                            .setBufferDurationsMs(1000, 10_000, 1000, 1000).build())
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(new TorrentDataSource.Factory(engine)))
                    .build();
            player.setVideoSurface(surface);
            player.addListener(new Player.Listener() {
                @Override public void onRenderedFirstFrame() { rendered.set(true); }
                @Override public void onPlayerError(PlaybackException e) { error.set(e); }
            });
            player.setMediaItem(MediaItem.fromUri(TorrentDataSource.uri(torrent, chosen)));
            player.prepare();
            player.play();
        });
        return torrent;
    }

    private void waitFor(String what, long timeoutMs, BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        AtomicBoolean ok = new AtomicBoolean();
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync(() -> ok.set(condition.getAsBoolean()));
            if (ok.get()) return;
            if (error.get() != null) throw new AssertionError(what + ": " + error.get().getErrorCodeName(), error.get());
            SystemClock.sleep(100);
        }
        throw new AssertionError("Timed out: " + what);
    }

    private long position() {
        long[] p = new long[1];
        instrumentation.runOnMainSync(() -> p[0] = player.getCurrentPosition());
        return p[0];
    }

    private void seekAndPlay(long ms) {
        instrumentation.runOnMainSync(() -> player.seekTo(ms));
        waitFor("playing after a seek to " + ms, 60_000,
                () -> player.isPlaying() && player.getCurrentPosition() >= ms + 1500);
    }

    @Test public void filmPlaysBeforeItHasDownloadedAndRewindsIntoEvictedData() throws Exception {
        Fixtures.Torrent t = fixtures.torrent("film-hybrid");
        Fixtures.File f = t.files.get(0);
        long torrent = play(t, f);
        waitFor("first frame", 60_000, rendered::get);
        waitFor("advancing playback", 30_000, () -> player.getCurrentPosition() >= 2000);
        JSONObject early = engine.stats(torrent);
        assertTrue("playing before the whole file arrived: " + early, early.getLong("totalDownload") < f.size);

        long duration = 45_000;
        seekAndPlay(duration * 8 / 10);
        // Back to the start, long since evicted.
        seekAndPlay(1000);
        JSONObject cache = engine.stats(torrent).getJSONObject("cache");
        assertTrue("evicted: " + cache, cache.getLong("evicted") > 0);
        assertTrue("file within the bound: " + cache, cache.getLong("fileBytes") <= CACHE);
        assertTrue("storage within the bound: " + cache, cache.getLong("allocatedBytes") <= CACHE);
        assertTrue(position() >= 2500);
    }

    @Test public void mp4WithItsIndexAtTheEndPlays() throws Exception {
        Fixtures.Torrent t = fixtures.torrent("late-index");
        play(t, t.files.get(0));
        waitFor("first frame", 60_000, rendered::get);
        waitFor("advancing playback", 30_000, () -> player.getCurrentPosition() >= 2000);
    }

    @Test public void matroskaOffersItsAudioAndSubtitleTracks() throws Exception {
        Fixtures.Torrent t = fixtures.torrent("matroska");
        play(t, t.files.get(0));
        waitFor("first frame", 60_000, rendered::get);
        int[] counts = new int[2];
        instrumentation.runOnMainSync(() -> {
            for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
                if (g.getType() == C.TRACK_TYPE_AUDIO) counts[0]++;
                if (g.getType() == C.TRACK_TYPE_TEXT) counts[1]++;
            }
        });
        assertEquals(2, counts[0]);
        assertEquals(1, counts[1]);
        waitFor("advancing playback", 30_000, () -> player.getCurrentPosition() >= 2000);
    }

    @Test public void oneEpisodeOfAMultiFileTorrentPlays() throws Exception {
        Fixtures.Torrent t = fixtures.torrent("series");
        long torrent = play(t, t.file("S01E02.mkv"));
        waitFor("first frame", 60_000, rendered::get);
        waitFor("advancing playback", 30_000, () -> player.getCurrentPosition() >= 2000);
        // Only the chosen episode, give or take a shared piece at each edge, was downloaded.
        assertTrue(engine.stats(torrent).getLong("totalDownload") < t.file("S01E02.mkv").size + 2L * t.pieceLength);
    }
}
