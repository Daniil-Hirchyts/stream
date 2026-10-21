package tv.daniil.torrent;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The native streaming engine: one libtorrent session whose torrents download into a bounded cache, a read-ahead
 * window at a time. Torrents and readers are 64-bit handles that are never reused. Blocking calls wait in short
 * slices and give up when their thread is interrupted, as Media3 does to cancel a load. Thread-safe.
 */
public final class TorrentEngine implements AutoCloseable {
    static {
        System.loadLibrary("daniiltv");
    }

    /** How long one native wait lasts before the thread's interruption is checked again. */
    private static final long SLICE_MS = 250;

    public static final class Config {
        /** Private directory for payload cache files; any left from an earlier process are deleted. */
        public String cacheDirectory;
        public long cacheBytes = 256L << 20;
        /** Free storage that must remain beyond the cache. */
        public long reserveBytes = 512L << 20;
        public String listenInterfaces = "0.0.0.0:0,[::]:0";
        public boolean dht = true;
        public boolean localDiscovery = true;
        public boolean upnp;
        public boolean natpmp;
        public boolean peerExchange = true;
        public boolean multipleConnectionsPerIp;
        /** PEM trust roots for HTTPS trackers and web seeds; see {@link TrustRoots}. */
        public String trustRootsFile = "";
        public int readAheadSeconds = 45;

        public Config(String cacheDirectory) {
            this.cacheDirectory = cacheDirectory;
        }

        /** Only peers given explicitly, on loopback: no DHT, local discovery, port mapping or peer exchange. */
        public static Config localOnly(String cacheDirectory) {
            Config c = new Config(cacheDirectory);
            c.listenInterfaces = "127.0.0.1:0";
            c.dht = c.localDiscovery = c.upnp = c.natpmp = c.peerExchange = false;
            c.multipleConnectionsPerIp = true;
            return c;
        }
    }

    /** Exactly one of a .torrent file, a magnet link or an info hash, and optionally where to find peers. */
    public static final class Source {
        @Nullable final byte[] metainfo;
        @Nullable final String magnet;
        @Nullable final String infoHash;
        final String[] trackers;
        final String[] peers;

        private Source(@Nullable byte[] metainfo, @Nullable String magnet, @Nullable String infoHash,
                String[] trackers, String[] peers) {
            this.metainfo = metainfo;
            this.magnet = magnet;
            this.infoHash = infoHash;
            this.trackers = trackers;
            this.peers = peers;
        }

        public static Source metainfo(byte[] torrent) {
            return new Source(torrent, null, null, new String[0], new String[0]);
        }

        public static Source magnet(String uri) {
            return new Source(null, uri, null, new String[0], new String[0]);
        }

        /** 40 hexadecimal digits for a v1 info hash, 64 for v2. */
        public static Source infoHash(String hex) {
            return new Source(null, null, hex, new String[0], new String[0]);
        }

        public Source withTrackers(String... urls) {
            return new Source(metainfo, magnet, infoHash, urls.clone(), peers);
        }

        /** "address:port" of peers to contact directly. */
        public Source withPeers(String... addresses) {
            return new Source(metainfo, magnet, infoHash, trackers, addresses.clone());
        }
    }

    private final long id;
    private volatile boolean closed;

    private TorrentEngine(long id) {
        this.id = id;
    }

    public static TorrentEngine create(Config c) throws TorrentException {
        return new TorrentEngine(nativeCreate(c.cacheDirectory, c.cacheBytes, c.reserveBytes, c.listenInterfaces,
                c.dht, c.localDiscovery, c.upnp, c.natpmp, c.peerExchange, c.multipleConnectionsPerIp,
                c.trustRootsFile, c.readAheadSeconds));
    }

    /** The libtorrent version and the eviction patch it carries. */
    public static String version() {
        return nativeVersion();
    }

    public long add(Source s) throws TorrentException {
        return nativeAdd(id, s.metainfo, s.magnet, s.infoHash, s.trackers, s.peers);
    }

    /** Waits for the file list: at once for a .torrent file, from peers for a magnet link or info hash. */
    public List<TorrentFile> awaitMetadata(long torrent, long timeoutMs) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        for (;;) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Metadata wait interrupted");
            long left = deadline - SystemClock.elapsedRealtime();
            if (left <= 0) {
                throw new TorrentException(TorrentException.TIMEOUT, "No peer sent the torrent's metadata in time");
            }
            String json = nativeAwaitMetadata(id, torrent, Math.min(left, SLICE_MS));
            if (json != null) return parseFiles(json);
        }
    }

    /** Starts downloading the beginning of this file; nothing else of the torrent downloads. */
    public void selectFile(long torrent, int file) throws TorrentException {
        nativeSelectFile(id, torrent, file);
    }

    /** Sizes the read-ahead window once the media's bitrate is known. */
    public void setBitrate(long torrent, long bytesPerSecond) throws TorrentException {
        nativeSetBitrate(id, torrent, bytesPerSecond);
    }

    public void addPeers(long torrent, String... addresses) throws TorrentException {
        nativeAddPeers(id, torrent, addresses);
    }

    /** Peers, transfer rates, cache use and errors, for diagnostics. */
    public JSONObject stats(long torrent) throws TorrentException {
        try {
            return new JSONObject(nativeStats(id, torrent));
        } catch (JSONException e) {
            throw new TorrentException(TorrentException.ENGINE_FAILED, "Unreadable statistics");
        }
    }

    /** Recent peer disconnections and errors, for the Developer view. */
    public String[] diagnostics() throws TorrentException {
        String[] lines = nativeDiagnostics(id);
        return lines == null ? new String[0] : lines;
    }

    /** Stops the torrent and removes its cache; its readers fail with CLOSED. */
    public void remove(long torrent) throws TorrentException {
        nativeRemove(id, torrent);
    }

    public long openReader(long torrent, int file) throws TorrentException {
        return nativeOpenReader(id, torrent, file);
    }

    public long fileSize(long reader) throws TorrentException {
        return nativeFileSize(id, reader);
    }

    /**
     * Copies verified bytes of the reader's file at {@code position}, waiting for them to download. Returns the
     * number copied, at most to the end of a piece, and 0 only at the end of the file.
     */
    public int read(long reader, long position, byte[] buffer, int offset, int length) throws IOException {
        for (;;) {
            int n = nativeRead(id, reader, position, buffer, offset, length, SLICE_MS);
            if (n >= 0) return n;
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Torrent read interrupted");
        }
    }

    /** Wakes the reader's blocked read, which fails with CANCELLED, as do its later reads. */
    public void cancel(long reader) throws TorrentException {
        nativeCancel(id, reader);
    }

    public void closeReader(long reader) throws TorrentException {
        nativeCloseReader(id, reader);
    }

    /** Stops the session. Calls still running in other threads finish first, with errors. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        nativeDestroy(id);
    }

    private static List<TorrentFile> parseFiles(String json) throws TorrentException {
        try {
            JSONArray items = new JSONArray(json);
            List<TorrentFile> files = new ArrayList<>(items.length());
            for (int i = 0; i < items.length(); i++) {
                JSONObject f = items.getJSONObject(i);
                files.add(new TorrentFile(f.getInt("index"), f.getString("path"), f.getLong("size")));
            }
            return files;
        } catch (JSONException e) {
            throw new TorrentException(TorrentException.ENGINE_FAILED, "Unreadable file list");
        }
    }

    private static native long nativeCreate(String cacheDir, long cacheBytes, long reserveBytes, String listen,
            boolean dht, boolean lsd, boolean upnp, boolean natpmp, boolean pex, boolean multiplePerIp, String caFile,
            int readAheadSeconds) throws TorrentException;
    private static native void nativeDestroy(long id);
    private static native long nativeAdd(long id, @Nullable byte[] metainfo, @Nullable String magnet,
            @Nullable String infoHash, String[] trackers, String[] peers) throws TorrentException;
    @Nullable private static native String nativeAwaitMetadata(long id, long torrent, long waitMs) throws TorrentException;
    private static native void nativeSelectFile(long id, long torrent, int file) throws TorrentException;
    private static native void nativeSetBitrate(long id, long torrent, long bytesPerSecond) throws TorrentException;
    private static native void nativeAddPeers(long id, long torrent, String[] peers) throws TorrentException;
    private static native String nativeStats(long id, long torrent) throws TorrentException;
    private static native void nativeRemove(long id, long torrent) throws TorrentException;
    private static native long nativeOpenReader(long id, long torrent, int file) throws TorrentException;
    private static native long nativeFileSize(long id, long reader) throws TorrentException;
    private static native int nativeRead(long id, long reader, long position, byte[] buffer, int offset, int length,
            long waitMs) throws TorrentException;
    private static native void nativeCancel(long id, long reader) throws TorrentException;
    private static native void nativeCloseReader(long id, long reader) throws TorrentException;
    @Nullable private static native String[] nativeDiagnostics(long id) throws TorrentException;
    private static native String nativeVersion();
}
