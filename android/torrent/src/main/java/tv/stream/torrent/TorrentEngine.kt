package tv.stream.torrent

import android.os.SystemClock

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.IOException
import java.io.InterruptedIOException

/**
 * The native streaming engine (native/core, Rust): one BitTorrent session whose torrents download into a bounded cache, a read-ahead
 * window at a time. Torrents and readers are 64-bit handles that are never reused. Blocking calls wait in short
 * slices and give up when their thread is interrupted, as Media3 does to cancel a load. Thread-safe.
 */
class TorrentEngine private constructor(private val id: Long, private val cacheDirectory: String) : AutoCloseable {

    class Config(
        /** Private directory for payload cache files; any left from an earlier process are deleted. */
        @JvmField var cacheDirectory: String,
    ) {
        @JvmField var cacheBytes: Long = 256L shl 20
        /** Free storage that must remain beyond the cache. */
        @JvmField var reserveBytes: Long = 512L shl 20
        @JvmField var listenInterfaces: String = "0.0.0.0:0,[::]:0"
        @JvmField var dht: Boolean = true
        @JvmField var localDiscovery: Boolean = true
        /** UPnP IGD at [natpmpGateway] when PCP and NAT-PMP don't map: an explicit opt-in. */
        @JvmField var upnp: Boolean = false
        /** PCP, falling back to NAT-PMP, maps the listen port at [natpmpGateway] while the engine runs: an explicit opt-in. */
        @JvmField var natpmp: Boolean = false
        /** The default route's IPv4 gateway; a private or loopback address, optionally with a port. */
        @JvmField var natpmpGateway: String = ""
        @JvmField var peerExchange: Boolean = true
        @JvmField var multipleConnectionsPerIp: Boolean = false
        /** Internet peers only: no LAN discovery, port mapping, or non-public peer/tracker/DHT addresses. */
        @JvmField var publicNetworkOnly: Boolean = false
        /** PEM trust roots for HTTPS trackers and web seeds; see [TrustRoots]. */
        @JvmField var trustRootsFile: String = ""
        @JvmField var readAheadSeconds: Int = 45

        companion object {
            /** Only peers given explicitly, on loopback: no DHT, local discovery, port mapping or peer exchange. */
            @JvmStatic
            fun localOnly(cacheDirectory: String): Config {
                val c = Config(cacheDirectory)
                c.listenInterfaces = "127.0.0.1:0"
                c.peerExchange = false
                c.natpmp = c.peerExchange
                c.upnp = c.natpmp
                c.localDiscovery = c.upnp
                c.dht = c.localDiscovery
                c.multipleConnectionsPerIp = true
                return c
            }
        }
    }

    /** Exactly one of a .torrent file, a magnet link or an info hash, and optionally where to find peers. */
    class Source(
        @JvmField val metainfo: ByteArray?,
        @JvmField val magnet: String?,
        @JvmField val infoHash: String?,
        @JvmField val trackers: Array<String>,
        @JvmField val peers: Array<String>,
        @JvmField val fallbackTrackers: Array<String>,
    ) {
        fun withTrackers(vararg urls: String): Source {
            return Source(metainfo, magnet, infoHash, arrayOf(*urls), peers, fallbackTrackers)
        }

        /** These trackers, only if no others were given: for providers that name a torrent but no way to find its peers. */
        fun withFallbackTrackers(vararg urls: String): Source {
            return Source(metainfo, magnet, infoHash, trackers, peers, arrayOf(*urls))
        }

        /** "address:port" of peers to contact directly. */
        fun withPeers(vararg addresses: String): Source {
            return Source(metainfo, magnet, infoHash, trackers, arrayOf(*addresses), fallbackTrackers)
        }

        companion object {
            @JvmStatic
            fun metainfo(torrent: ByteArray): Source {
                return Source(torrent, null, null, arrayOf(), arrayOf(), arrayOf())
            }

            @JvmStatic
            fun magnet(uri: String): Source {
                return Source(null, uri, null, arrayOf(), magnetPeers(uri), arrayOf())
            }

            /** Preserve explicit bootstrap peers when a magnet becomes a cached metadata source. No DNS lookup. */
            private fun magnetPeers(magnet: String): Array<String> {
                val peers: MutableSet<String> = java.util.LinkedHashSet()
                try {
                    val query = java.net.URI.create(magnet).rawSchemeSpecificPart
                    if (!query.startsWith("?")) return arrayOf()
                    for (pair in query.substring(1).split("&")) {
                        val field = pair.split("=", limit = 2)
                        if (field.size != 2 || field[0] != "x.pe") continue
                        val peer = java.net.URLDecoder.decode(field[1], "UTF-8")
                        try {
                            val endpoint = java.net.URI.create("tcp://$peer")
                            val host: String? = endpoint.host; val port = endpoint.port
                            if (host == null || port < 1 || port > 65535 || endpoint.rawUserInfo != null
                                    || !endpoint.rawPath.isEmpty() || endpoint.rawQuery != null || endpoint.rawFragment != null) continue
                            var numeric = host.startsWith("[") && host.endsWith("]")
                            if (!numeric && host.matches(Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))) {
                                numeric = true
                                for (part in host.split(".")) if (Integer.parseInt(part) > 255) numeric = false
                            }
                            if (numeric) peers.add(peer)
                        } catch (ignored: IllegalArgumentException) { /* Optional malformed peer; the engine validates the magnet too. */ }
                    }
                } catch (ignored: IllegalArgumentException) { /* Invalid magnet is rejected when added. */
                } catch (ignored: java.io.UnsupportedEncodingException) { /* Invalid magnet is rejected when added. */ }
                return peers.toTypedArray()
            }

            /** 40 hexadecimal digits for a v1 info hash, 64 for v2. */
            @JvmStatic
            fun infoHash(hex: String): Source {
                return Source(null, null, hex, arrayOf(), arrayOf(), arrayOf())
            }
        }
    }

    @Volatile private var closed = false

    @Throws(TorrentException::class)
    fun add(s: Source): Long {
        return nativeAdd(id, s.metainfo, s.magnet, s.infoHash, s.trackers, s.peers, s.fallbackTrackers)
    }

    /** Waits for the file list: at once for a .torrent file, from peers for a magnet link or info hash. */
    @Throws(IOException::class)
    fun awaitMetadata(torrent: Long, timeoutMs: Long): List<TorrentFile> {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Metadata wait interrupted")
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) {
                throw TorrentException(TorrentException.TIMEOUT, "No peer sent the torrent's metadata in time")
            }
            val json = nativeAwaitMetadata(id, torrent, Math.min(left, SLICE_MS))
            if (json != null) return parseFiles(json)
        }
    }

    /** Starts downloading the beginning of this file; nothing else of the torrent downloads. */
    @Throws(TorrentException::class)
    fun selectFile(torrent: Long, file: Int) {
        nativeSelectFile(id, torrent, file)
    }

    /** Sizes the read-ahead window once the media's bitrate is known. */
    @Throws(TorrentException::class)
    fun setBitrate(torrent: Long, bytesPerSecond: Long) {
        nativeSetBitrate(id, torrent, bytesPerSecond)
    }

    @Throws(TorrentException::class)
    fun updateDemand(torrent: Long, bytesPerSecond: Long, aheadSeconds: Int) {
        nativeSetDemand(id, torrent, bytesPerSecond, aheadSeconds)
    }
    @Throws(TorrentException::class)
    fun snapshotSource(torrent: Long, original: Source): Source {
        return Source(nativeMetainfo(id, torrent), null, null, original.trackers, original.peers, arrayOf())
    }
    @Throws(TorrentException::class)
    fun addPeers(torrent: Long, vararg addresses: String) {
        nativeAddPeers(id, torrent, arrayOf(*addresses))
    }

    /** Peers, transfer rates, cache use and errors, for diagnostics. */
    @Throws(TorrentException::class)
    fun stats(torrent: Long): JSONObject {
        try {
            return JSONObject(nativeStats(id, torrent))
        } catch (e: JSONException) {
            throw TorrentException(TorrentException.ENGINE_FAILED, "Unreadable statistics")
        }
    }

    /** Recent peer disconnections and errors, for the Developer view. */
    @Throws(TorrentException::class)
    fun diagnostics(): Array<String> {
        val lines = nativeDiagnostics(id)
        return lines ?: arrayOf()
    }

    /** Stops the torrent and removes its cache; its readers fail with CLOSED. */
    @Throws(TorrentException::class)
    fun remove(torrent: Long) {
        nativeRemove(id, torrent)
    }

    @Throws(TorrentException::class)
    fun openReader(torrent: Long, file: Int): Long {
        return nativeOpenReader(id, torrent, file)
    }

    @Throws(TorrentException::class)
    fun fileSize(reader: Long): Long {
        return nativeFileSize(id, reader)
    }

    /**
     * Copies verified bytes of the reader's file at `position`, waiting for them to download. Returns the
     * number copied, at most to the end of a piece, and 0 only at the end of the file.
     */
    @Throws(IOException::class)
    fun read(reader: Long, position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val n = nativeRead(id, reader, position, buffer, offset, length, SLICE_MS)
            if (n >= 0) return n
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Torrent read interrupted")
        }
    }

    /** Wakes the reader's blocked read, which fails with CANCELLED, as do its later reads. */
    @Throws(TorrentException::class)
    fun cancel(reader: Long) {
        nativeCancel(id, reader)
    }

    @Throws(TorrentException::class)
    fun closeReader(reader: Long) {
        nativeCloseReader(id, reader)
    }

    /** Stops the session. Calls still running in other threads finish first, with errors. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        nativeDestroy(id)
        java.io.File(cacheDirectory).delete()
    }

    companion object {
        init {
            System.loadLibrary("stream")
        }

        /** How long one native wait lasts before the thread's interruption is checked again. */
        private const val SLICE_MS: Long = 250

        @JvmStatic
        @Throws(TorrentException::class)
        fun create(c: Config): TorrentEngine {
            return TorrentEngine(nativeCreate(c.cacheDirectory, c.cacheBytes, c.reserveBytes, c.listenInterfaces,
                    c.dht, c.localDiscovery, c.upnp, c.natpmp, c.natpmpGateway, c.peerExchange, c.multipleConnectionsPerIp, c.publicNetworkOnly,
                    c.trustRootsFile, c.readAheadSeconds), c.cacheDirectory)
        }

        /** The streaming engine's BitTorrent implementation and version. */
        @JvmStatic
        fun version(): String {
            return nativeVersion()
        }

        @Throws(TorrentException::class)
        private fun parseFiles(json: String): List<TorrentFile> {
            try {
                val items = JSONArray(json)
                val files: MutableList<TorrentFile> = ArrayList(items.length())
                for (i in 0 until items.length()) {
                    val f = items.getJSONObject(i)
                    files.add(TorrentFile(f.getInt("index"), f.getString("path"), f.getLong("size")))
                }
                return files
            } catch (e: JSONException) {
                throw TorrentException(TorrentException.ENGINE_FAILED, "Unreadable file list")
            }
        }

        @JvmStatic @Throws(TorrentException::class)
        private external fun nativeCreate(cacheDir: String, cacheBytes: Long, reserveBytes: Long, listen: String,
                dht: Boolean, lsd: Boolean, upnp: Boolean, natpmp: Boolean, natpmpGateway: String, pex: Boolean, multiplePerIp: Boolean, publicNetworkOnly: Boolean, caFile: String,
                readAheadSeconds: Int): Long
        @JvmStatic private external fun nativeDestroy(id: Long)
        @JvmStatic @Throws(TorrentException::class)
        private external fun nativeAdd(id: Long, metainfo: ByteArray?, magnet: String?,
                infoHash: String?, trackers: Array<String>, peers: Array<String>, fallbackTrackers: Array<String>): Long
        @JvmStatic @Throws(TorrentException::class)
        private external fun nativeAwaitMetadata(id: Long, torrent: Long, waitMs: Long): String?
        @JvmStatic @Throws(TorrentException::class)
        private external fun nativeSelectFile(id: Long, torrent: Long, file: Int)
        @JvmStatic @Throws(TorrentException::class)
        private external fun nativeSetBitrate(id: Long, torrent: Long, bytesPerSecond: Long)
        @JvmStatic @Throws(TorrentException::class)
        private external fun nativeSetDemand(id: Long, torrent: Long, bytesPerSecond: Long, seconds: Int)
        @JvmStatic @Throws(TorrentException::class)
        private external fun nativeMetainfo(id: Long, torrent: Long): ByteArray
        @JvmStatic @Throws(TorrentException::class)
        private external fun nativeAddPeers(id: Long, torrent: Long, peers: Array<String>)
        @JvmStatic @Throws(TorrentException::class)
        private external fun nativeStats(id: Long, torrent: Long): String
        @JvmStatic @Throws(TorrentException::class)
        private external fun nativeRemove(id: Long, torrent: Long)
        @JvmStatic @Throws(TorrentException::class)
        private external fun nativeOpenReader(id: Long, torrent: Long, file: Int): Long
        @JvmStatic @Throws(TorrentException::class)
        private external fun nativeFileSize(id: Long, reader: Long): Long
        @JvmStatic @Throws(TorrentException::class)
        private external fun nativeRead(id: Long, reader: Long, position: Long, buffer: ByteArray, offset: Int, length: Int,
                waitMs: Long): Int
        @JvmStatic @Throws(TorrentException::class)
        private external fun nativeCancel(id: Long, reader: Long)
        @JvmStatic @Throws(TorrentException::class)
        private external fun nativeCloseReader(id: Long, reader: Long)
        @JvmStatic @Throws(TorrentException::class)
        private external fun nativeDiagnostics(id: Long): Array<String>?
        @JvmStatic private external fun nativeVersion(): String
    }
}
