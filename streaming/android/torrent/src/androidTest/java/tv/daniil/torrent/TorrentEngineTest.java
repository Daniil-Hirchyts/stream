package tv.daniil.torrent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The native engine on the emulator, through JNI (CheckJNI is on for this debuggable APK), against the host's real
 * BitTorrent seeder: metadata, file names, refusals, interruption, and every byte of a file after the bounded cache
 * has turned over several times.
 */
@RunWith(AndroidJUnit4.class)
public final class TorrentEngineTest {
    private static final long CACHE = 16L << 20;
    private Fixtures fixtures;
    private TorrentEngine engine;

    @Before public void open() throws Exception {
        fixtures = Fixtures.load();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        TorrentEngine.Config config = TorrentEngine.Config.localOnly(new File(context.getCacheDir(), "torrent").getPath());
        config.cacheBytes = CACHE;
        config.reserveBytes = 0;
        engine = TorrentEngine.create(config);
    }

    @After public void close() {
        if (engine != null) engine.close();
    }

    private long add(Fixtures.Torrent t) throws Exception {
        return engine.add(TorrentEngine.Source.metainfo(fixtures.metainfo(t)).withPeers(fixtures.seeder));
    }

    private static void assertFiles(Fixtures.Torrent expected, List<TorrentFile> files) {
        assertEquals(expected.files.size(), files.size());
        for (int i = 0; i < files.size(); i++) {
            assertEquals(expected.files.get(i).index, files.get(i).index);
            assertEquals(expected.files.get(i).path, files.get(i).path);
            assertEquals(expected.files.get(i).size, files.get(i).size);
        }
    }

    @Test public void metadataArrivesOverBitTorrentForEachHashVersion() throws Exception {
        Fixtures.Torrent v1 = fixtures.torrent("film-v1");
        long a = engine.add(TorrentEngine.Source.infoHash(v1.v1).withPeers(fixtures.seeder));
        assertFiles(v1, engine.awaitMetadata(a, 30_000));

        Fixtures.Torrent v2 = fixtures.torrent("film-v2");
        long b = engine.add(TorrentEngine.Source.infoHash(v2.v2).withPeers(fixtures.seeder));
        assertFiles(v2, engine.awaitMetadata(b, 30_000));

        Fixtures.Torrent hybrid = fixtures.torrent("series");
        long c = engine.add(TorrentEngine.Source.magnet("magnet:?xt=urn:btih:" + hybrid.v1 + "&x.pe=" + fixtures.seeder));
        assertFiles(hybrid, engine.awaitMetadata(c, 30_000));
    }

    @Test public void fileNamesOutsideAsciiCrossTheNativeBoundary() throws Exception {
        Fixtures.Torrent t = fixtures.torrent("unicode");
        List<TorrentFile> files = engine.awaitMetadata(add(t), 10_000);
        assertEquals("Сериал — эпизод 🎬.mp4", files.get(0).name());
        assertFiles(t, files);
    }

    @Test public void internetOnlyValidationRejectsLoopbackPeersThroughJni() throws Exception {
        engine.close();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        TorrentEngine.Config config = TorrentEngine.Config.localOnly(new File(context.getCacheDir(), "torrent").getPath());
        config.cacheBytes = CACHE; config.reserveBytes = 0; config.publicNetworkOnly = true;
        engine = TorrentEngine.create(config);
        long torrent = engine.add(TorrentEngine.Source.infoHash(fixtures.torrent("film-v1").v1).withPeers(fixtures.seeder));
        expect(TorrentException.TIMEOUT, () -> engine.awaitMetadata(torrent, 2000));
        assertEquals(0, engine.stats(torrent).getInt("peers"));
    }

    @Test public void unusableTorrentsAndHandlesAreRefused() throws Exception {
        expect(TorrentException.PIECE_TOO_LARGE, () -> add(fixtures.torrent("film-huge-pieces")));
        expect(TorrentException.BAD_SOURCE, () -> engine.add(TorrentEngine.Source.metainfo(new byte[] {'d', 'e'})));
        expect(TorrentException.BAD_SOURCE, () -> engine.add(TorrentEngine.Source.infoHash("not-a-hash")));
        long t = add(fixtures.torrent("series"));
        engine.awaitMetadata(t, 10_000);
        // An explicit index must exist; it is never swapped for another file.
        expect(TorrentException.INVALID_FILE, () -> { engine.selectFile(t, 99); return null; });
        engine.remove(t);
        expect(TorrentException.INVALID_HANDLE, () -> engine.stats(t));
    }

    @Test public void interruptionCancellationAndClosingWakeBlockedReads() throws Exception {
        // No peers: the data never arrives, so every read blocks until something stops it.
        long t = engine.add(TorrentEngine.Source.metainfo(fixtures.metainfo(fixtures.torrent("late-index"))));
        engine.awaitMetadata(t, 10_000);
        engine.selectFile(t, 0);

        AtomicReference<Throwable> error = new AtomicReference<>();
        long r1 = engine.openReader(t, 0);
        Thread reader = new Thread(() -> blockedRead(r1, error));
        reader.start();
        SystemClock.sleep(300);
        long start = SystemClock.elapsedRealtime();
        reader.interrupt();
        reader.join(5000);
        assertTrue(error.get() instanceof InterruptedIOException);
        assertTrue(SystemClock.elapsedRealtime() - start < 2000);

        long r2 = engine.openReader(t, 0);
        error.set(null);
        reader = new Thread(() -> blockedRead(r2, error));
        reader.start();
        SystemClock.sleep(300);
        engine.cancel(r2);
        reader.join(5000);
        assertEquals(TorrentException.CANCELLED, ((TorrentException) error.get()).kind);

        long r3 = engine.openReader(t, 0);
        error.set(null);
        reader = new Thread(() -> blockedRead(r3, error));
        reader.start();
        SystemClock.sleep(300);
        engine.close();
        reader.join(5000);
        assertTrue(error.get() instanceof TorrentException);
        engine = null;
    }

    private void blockedRead(long reader, AtomicReference<Throwable> error) {
        try {
            engine.read(reader, 0, new byte[4096], 0, 4096);
            error.set(new AssertionError("The read returned"));
        } catch (Throwable t) {
            error.set(t);
        }
    }

    @Test public void everyByteArrivesAfterTheCacheTurnsOverV1() throws Exception {
        readWholeFile(fixtures.torrent("film-v1"));
    }

    @Test public void closingDuringTransferCanReopenRepeatedly() throws Exception {
        for (int cycle = 0; cycle < 8; cycle++) {
            long torrent = add(fixtures.torrent("film-hybrid"));
            engine.awaitMetadata(torrent, 10_000);
            engine.selectFile(torrent, 0);
            long reader = engine.openReader(torrent, 0);
            assertTrue(engine.read(reader, 0, new byte[16384], 0, 16384) > 0);
            engine.close();
            engine = null;
            open();
        }
    }

    @Test public void everyByteArrivesAfterTheCacheTurnsOverV2() throws Exception {
        readWholeFile(fixtures.torrent("film-v2"));
    }

    @Test public void everyByteArrivesAfterTheCacheTurnsOverHybrid() throws Exception {
        readWholeFile(fixtures.torrent("film-hybrid"));
    }

    /** Reads the 68 MB film through a 16 MiB cache, hashing it, then rewinds into evicted pieces. */
    private void readWholeFile(Fixtures.Torrent t) throws Exception {
        long torrent = add(t);
        engine.awaitMetadata(torrent, 20_000);
        Fixtures.File file = t.files.get(0);
        engine.selectFile(torrent, file.index);
        long reader = engine.openReader(torrent, file.index);
        assertEquals(file.size, engine.fileSize(reader));

        MessageDigest whole = MessageDigest.getInstance("SHA-256");
        MessageDigest head = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[1 << 20];
        long most = 0;
        for (long pos = 0; pos < file.size;) {
            int n = engine.read(reader, pos, buf, 0, (int) Math.min(buf.length, file.size - pos));
            assertTrue(n > 0);
            whole.update(buf, 0, n);
            if (pos < (4 << 20)) head.update(buf, 0, (int) Math.min(n, (4 << 20) - pos));
            pos += n;
            most = Math.max(most, engine.stats(torrent).getJSONObject("cache").getLong("allocatedBytes"));
        }
        assertEquals(file.sha256, hex(whole.digest()));
        assertEquals(0, engine.read(reader, file.size, buf, 0, 1));

        JSONObject cache = engine.stats(torrent).getJSONObject("cache");
        int slots = cache.getInt("slots");
        assertTrue("turned over three times: " + cache, cache.getLong("evicted") >= 3L * slots);
        assertTrue("file within the bound: " + cache, cache.getLong("fileBytes") <= CACHE);
        assertTrue("storage within the bound: " + most, most <= CACHE);

        // The start was evicted long ago; it comes back intact.
        MessageDigest again = MessageDigest.getInstance("SHA-256");
        for (long pos = 0; pos < (4 << 20);) {
            int n = engine.read(reader, pos, buf, 0, (int) Math.min(buf.length, (4 << 20) - pos));
            again.update(buf, 0, n);
            pos += n;
        }
        assertEquals(hex(head.digest()), hex(again.digest()));
        engine.closeReader(reader);
        engine.remove(torrent);
    }

    private interface Call { Object run() throws Exception; }

    private static void expect(int kind, Call call) throws Exception {
        try {
            call.run();
        } catch (TorrentException e) {
            assertEquals(e.getMessage(), kind, e.kind);
            return;
        }
        fail("No error");
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b));
        return s.toString();
    }
}
