package tv.stream.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

import android.content.Context
import android.os.SystemClock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * The native engine on the emulator, through JNI (CheckJNI is on for this debuggable APK), against the host's real
 * BitTorrent seeder: metadata, file names, refusals, interruption, and every byte of a file after the bounded cache
 * has turned over several times.
 */
@RunWith(AndroidJUnit4::class)
class TorrentEngineTest {
    private lateinit var fixtures: Fixtures
    private var engine: TorrentEngine? = null

    @Before @Throws(Exception::class) fun open() {
        fixtures = Fixtures.load()
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = TorrentEngine.Config.localOnly(File(context.cacheDir, "torrent").path)
        config.cacheBytes = CACHE
        config.reserveBytes = 0
        engine = TorrentEngine.create(config)
    }

    @After fun close() {
        if (engine != null) engine!!.close()
    }

    @Throws(Exception::class)
    private fun add(t: Fixtures.Torrent): Long {
        return engine!!.add(TorrentEngine.Source.metainfo(fixtures.metainfo(t)).withPeers(fixtures.seeder))
    }

    @Test @Throws(Exception::class) fun metadataArrivesOverBitTorrentForEachHashVersion() {
        val v1 = fixtures.torrent("film-v1")
        val a = engine!!.add(TorrentEngine.Source.infoHash(v1.v1).withPeers(fixtures.seeder))
        assertFiles(v1, engine!!.awaitMetadata(a, 30_000))

        val v2 = fixtures.torrent("film-v2")
        val b = engine!!.add(TorrentEngine.Source.infoHash(v2.v2).withPeers(fixtures.seeder))
        assertFiles(v2, engine!!.awaitMetadata(b, 30_000))

        val hybrid = fixtures.torrent("series")
        val c = engine!!.add(TorrentEngine.Source.magnet("magnet:?xt=urn:btih:" + hybrid.v1 + "&x.pe=" + fixtures.seeder))
        assertFiles(hybrid, engine!!.awaitMetadata(c, 30_000))
    }

    @Test @Throws(Exception::class) fun metadataSnapshotRetainsMagnetBootstrapPeersAfterEngineReplacement() {
        val film = fixtures.torrent("film-v1")
        val magnet = TorrentEngine.Source.magnet("magnet:?xt=urn:btih:" + film.v1
                + "&x.pe=" + fixtures.seeder)
        val first = engine!!.add(magnet)
        assertFiles(film, engine!!.awaitMetadata(first, 30_000))
        val recovered = engine!!.snapshotSource(first, magnet)

        // A fresh session has neither a peer list nor payload from the original torrent.
        // DHT, trackers and discovery are disabled, so only the retained x.pe can supply data.
        engine!!.close()
        engine = null
        open()
        val replacement = engine!!.add(recovered)
        assertFiles(film, engine!!.awaitMetadata(replacement, 10_000))
        engine!!.selectFile(replacement, 0)
        val reader = engine!!.openReader(replacement, 0)
        try {
            assertTrue(engine!!.read(reader, 0, ByteArray(16384), 0, 16384) > 0)
        } finally {
            engine!!.closeReader(reader)
        }
    }

    @Test @Throws(Exception::class) fun fileNamesOutsideAsciiCrossTheNativeBoundary() {
        val t = fixtures.torrent("unicode")
        val files = engine!!.awaitMetadata(add(t), 10_000)
        assertEquals("Сериал — эпизод 🎬.mp4", files[0].name())
        assertFiles(t, files)
    }

    @Test @Throws(Exception::class) fun internetOnlyValidationRejectsLoopbackPeersThroughJni() {
        engine!!.close()
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = TorrentEngine.Config.localOnly(File(context.cacheDir, "torrent").path)
        config.cacheBytes = CACHE; config.reserveBytes = 0; config.publicNetworkOnly = true
        engine = TorrentEngine.create(config)
        val torrent = engine!!.add(TorrentEngine.Source.infoHash(fixtures.torrent("film-v1").v1).withPeers(fixtures.seeder))
        expect(TorrentException.TIMEOUT, Call { engine!!.awaitMetadata(torrent, 2000) })
        assertEquals(0, engine!!.stats(torrent).getInt("peers").toLong())
    }

    @Test @Throws(Exception::class) fun unusableTorrentsAndHandlesAreRefused() {
        expect(TorrentException.PIECE_TOO_LARGE, Call { add(fixtures.torrent("film-huge-pieces")) })
        expect(TorrentException.BAD_SOURCE, Call { engine!!.add(TorrentEngine.Source.metainfo(byteArrayOf('d'.code.toByte(), 'e'.code.toByte()))) })
        expect(TorrentException.BAD_SOURCE, Call { engine!!.add(TorrentEngine.Source.infoHash("not-a-hash")) })
        val t = add(fixtures.torrent("series"))
        engine!!.awaitMetadata(t, 10_000)
        // An explicit index must exist; it is never swapped for another file.
        expect(TorrentException.INVALID_FILE, Call { engine!!.selectFile(t, 99); null })
        engine!!.remove(t)
        expect(TorrentException.INVALID_HANDLE, Call { engine!!.stats(t) })
    }

    @Test @Throws(Exception::class) fun interruptionCancellationAndClosingWakeBlockedReads() {
        // No peers: the data never arrives, so every read blocks until something stops it.
        val t = engine!!.add(TorrentEngine.Source.metainfo(fixtures.metainfo(fixtures.torrent("late-index"))))
        engine!!.awaitMetadata(t, 10_000)
        engine!!.selectFile(t, 0)

        val error = AtomicReference<Throwable>()
        val r1 = engine!!.openReader(t, 0)
        var reader = Thread { blockedRead(r1, error) }
        reader.start()
        SystemClock.sleep(300)
        val start = SystemClock.elapsedRealtime()
        reader.interrupt()
        reader.join(5000)
        assertTrue(error.get() is InterruptedIOException)
        assertTrue(SystemClock.elapsedRealtime() - start < 2000)

        val r2 = engine!!.openReader(t, 0)
        error.set(null)
        reader = Thread { blockedRead(r2, error) }
        reader.start()
        SystemClock.sleep(300)
        engine!!.cancel(r2)
        reader.join(5000)
        assertEquals(TorrentException.CANCELLED.toLong(), (error.get() as TorrentException).kind.toLong())

        val r3 = engine!!.openReader(t, 0)
        error.set(null)
        reader = Thread { blockedRead(r3, error) }
        reader.start()
        SystemClock.sleep(300)
        engine!!.close()
        reader.join(5000)
        assertTrue(error.get() is TorrentException)
        engine = null
    }

    private fun blockedRead(reader: Long, error: AtomicReference<Throwable>) {
        try {
            engine!!.read(reader, 0, ByteArray(4096), 0, 4096)
            error.set(AssertionError("The read returned"))
        } catch (t: Throwable) {
            error.set(t)
        }
    }

    /** One hash version is enough here: eviction is version-blind, and metadata is checked for each version above. */
    @Test @Throws(Exception::class) fun everyByteArrivesAfterTheCacheTurnsOver() {
        readWholeFile(fixtures.torrent("film-v1"))
    }

    @Test @Throws(Exception::class) fun closingDuringTransferCanReopenRepeatedly() {
        for (cycle in 0 until 8) {
            val torrent = add(fixtures.torrent("film-hybrid"))
            engine!!.awaitMetadata(torrent, 10_000)
            engine!!.selectFile(torrent, 0)
            val reader = engine!!.openReader(torrent, 0)
            assertTrue(engine!!.read(reader, 0, ByteArray(16384), 0, 16384) > 0)
            engine!!.close()
            engine = null
            open()
        }
    }

    /** Reads the 68 MB film through a 16 MiB cache, hashing it, then rewinds into evicted pieces. */
    @Throws(Exception::class)
    private fun readWholeFile(t: Fixtures.Torrent) {
        val torrent = add(t)
        engine!!.awaitMetadata(torrent, 20_000)
        val file = t.files[0]
        engine!!.selectFile(torrent, file.index)
        val reader = engine!!.openReader(torrent, file.index)
        assertEquals(file.size, engine!!.fileSize(reader))

        val whole = MessageDigest.getInstance("SHA-256")
        val head = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var most: Long = 0
        var pos: Long = 0
        while (pos < file.size) {
            val n = engine!!.read(reader, pos, buf, 0, Math.min(buf.size.toLong(), file.size - pos).toInt())
            assertTrue(n > 0)
            whole.update(buf, 0, n)
            if (pos < (4 shl 20)) head.update(buf, 0, Math.min(n.toLong(), (4 shl 20) - pos).toInt())
            pos += n.toLong()
            most = Math.max(most, engine!!.stats(torrent).getJSONObject("cache").getLong("allocatedBytes"))
        }
        assertEquals(file.sha256, hex(whole.digest()))
        assertEquals(0, engine!!.read(reader, file.size, buf, 0, 1).toLong())

        val cache: JSONObject = engine!!.stats(torrent).getJSONObject("cache")
        val slots = cache.getInt("slots")
        assertTrue("turned over three times: $cache", cache.getLong("evicted") >= 3L * slots)
        assertTrue("file within the bound: $cache", cache.getLong("fileBytes") <= CACHE)
        assertTrue("storage within the bound: $most", most <= CACHE)

        // The start was evicted long ago; it comes back intact.
        val again = MessageDigest.getInstance("SHA-256")
        pos = 0
        while (pos < (4 shl 20)) {
            val n = engine!!.read(reader, pos, buf, 0, Math.min(buf.size.toLong(), (4 shl 20) - pos).toInt())
            again.update(buf, 0, n)
            pos += n.toLong()
        }
        assertEquals(hex(head.digest()), hex(again.digest()))
        engine!!.closeReader(reader)
        engine!!.remove(torrent)
    }

    private fun interface Call { @Throws(Exception::class) fun run(): Any? }

    companion object {
        private const val CACHE = 16L shl 20

        private fun assertFiles(expected: Fixtures.Torrent, files: List<TorrentFile>) {
            assertEquals(expected.files.size.toLong(), files.size.toLong())
            for (i in files.indices) {
                assertEquals(expected.files[i].index.toLong(), files[i].index.toLong())
                assertEquals(expected.files[i].path, files[i].path)
                assertEquals(expected.files[i].size, files[i].size)
            }
        }

        @Throws(Exception::class)
        private fun expect(kind: Int, call: Call) {
            try {
                call.run()
            } catch (e: TorrentException) {
                assertEquals(e.message, kind.toLong(), e.kind.toLong())
                return
            }
            fail("No error")
        }

        private fun hex(bytes: ByteArray): String {
            val s = StringBuilder()
            for (b in bytes) s.append(String.format(Locale.ROOT, "%02x", b))
            return s.toString()
        }
    }
}
