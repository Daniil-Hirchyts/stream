package tv.stream.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

import android.app.Instrumentation
import android.content.Context
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.Surface

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier

/** Media3 playing torrents through TorrentDataSource, decoding to a real surface, with a bounded cache. */
@RunWith(AndroidJUnit4::class)
@OptIn(markerClass = [UnstableApi::class])
class TorrentPlaybackTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var fixtures: Fixtures
    private var engine: TorrentEngine? = null
    private var player: ExoPlayer? = null
    private lateinit var surface: Surface
    private lateinit var texture: SurfaceTexture
    private val rendered = AtomicBoolean()
    private val error = AtomicReference<PlaybackException>()

    @Before @Throws(Exception::class) fun open() {
        fixtures = Fixtures.load()
        val context: Context = instrumentation.targetContext
        val config = TorrentEngine.Config.localOnly(File(context.cacheDir, "torrent").path)
        config.cacheBytes = CACHE
        config.reserveBytes = 0
        engine = TorrentEngine.create(config)
        texture = SurfaceTexture(false)
        surface = Surface(texture)
    }

    @After fun close() {
        instrumentation.runOnMainSync {
            if (player != null) player!!.release()
        }
        if (engine != null) engine!!.close()
        surface.release()
        texture.release()
    }

    /** Starts playing a file of a torrent from the host seeder. */
    @Throws(Exception::class)
    private fun play(t: Fixtures.Torrent, f: Fixtures.File): Long {
        val torrent = engine!!.add(TorrentEngine.Source.metainfo(fixtures.metainfo(t)).withPeers(fixtures.seeder))
        val files: List<TorrentFile> = engine!!.awaitMetadata(torrent, 20_000)
        var file: TorrentFile? = null
        for (candidate in files) if (candidate.index == f.index) file = candidate
        engine!!.selectFile(torrent, f.index)
        val chosen = file
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(instrumentation.targetContext)
                    .setLoadControl(DefaultLoadControl.Builder().setTargetBufferBytes(24 shl 20)
                            .setBufferDurationsMs(1000, 10_000, 1000, 1000).build())
                    .setMediaSourceFactory(DefaultMediaSourceFactory(TorrentDataSource.Factory(engine!!)))
                    .build()
            player!!.setVideoSurface(surface)
            player!!.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { rendered.set(true) }
                override fun onPlayerError(e: PlaybackException) { error.set(e) }
            })
            player!!.setMediaItem(MediaItem.fromUri(TorrentDataSource.uri(torrent, chosen!!)))
            player!!.prepare()
            player!!.play()
        }
        return torrent
    }

    private fun waitFor(what: String, timeoutMs: Long, condition: BooleanSupplier) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val ok = AtomicBoolean()
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync { ok.set(condition.asBoolean) }
            if (ok.get()) return
            if (error.get() != null) throw AssertionError(what + ": " + error.get().errorCodeName, error.get())
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out: $what")
    }

    @Test @Throws(Exception::class) fun mp4WithItsIndexAtTheEndPlays() {
        val t = fixtures.torrent("late-index")
        play(t, t.files[0])
        waitFor("first frame", 60_000) { rendered.get() }
        waitFor("advancing playback", 30_000) { player!!.currentPosition >= 2000 }
    }

    @Test @Throws(Exception::class) fun matroskaOffersItsAudioAndSubtitleTracks() {
        val t = fixtures.torrent("matroska")
        play(t, t.files[0])
        waitFor("first frame", 60_000) { rendered.get() }
        val counts = IntArray(2)
        instrumentation.runOnMainSync {
            for (g: Tracks.Group in player!!.currentTracks.groups) {
                if (g.type == C.TRACK_TYPE_AUDIO) counts[0]++
                if (g.type == C.TRACK_TYPE_TEXT) counts[1]++
            }
        }
        assertEquals(2, counts[0].toLong())
        assertEquals(1, counts[1].toLong())
        waitFor("advancing playback", 30_000) { player!!.currentPosition >= 2000 }
    }

    @Test @Throws(Exception::class) fun oneEpisodeOfAMultiFileTorrentPlays() {
        val t = fixtures.torrent("series")
        val torrent = play(t, t.file("S01E02.mkv"))
        waitFor("first frame", 60_000) { rendered.get() }
        waitFor("advancing playback", 30_000) { player!!.currentPosition >= 2000 }
        // Only the chosen episode, give or take a shared piece at each edge, was downloaded.
        assertTrue(engine!!.stats(torrent).getLong("totalDownload") < t.file("S01E02.mkv").size + 2L * t.pieceLength)
    }

    companion object {
        private const val CACHE = 16L shl 20
    }
}
