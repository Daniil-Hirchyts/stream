package tv.stream.torrent

import android.net.Uri

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec

import java.io.IOException

/**
 * Media3 reads a torrent's file through this: torrent://&lt;torrent&gt;/&lt;file index&gt;/&lt;name&gt;. Each open
 * maps the requested range onto a native reader; a read waits for verified pieces, which the engine downloads
 * first. Missing data is never replaced by zeros or an early end. Media3 seeks by closing and reopening.
 */
@OptIn(markerClass = [UnstableApi::class])
class TorrentDataSource(private val engine: TorrentEngine) : BaseDataSource(/* isNetwork= */ true) {

    class Factory(private val engine: TorrentEngine) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return TorrentDataSource(engine)
        }
    }

    private var uri: Uri? = null
    private var reader: Long = 0
    private var opened = false
    private var position: Long = 0
    private var remaining: Long = 0

    @Throws(IOException::class)
    override fun open(spec: DataSpec): Long {
        val uri = spec.uri
        this.uri = uri
        val segments = uri.pathSegments
        val torrent: Long
        val file: Int
        try {
            if (SCHEME != uri.scheme || uri.authority == null || segments.isEmpty()) {
                throw NumberFormatException()
            }
            torrent = java.lang.Long.parseLong(uri.authority!!)
            file = Integer.parseInt(segments[0])
        } catch (e: NumberFormatException) {
            throw TorrentException(TorrentException.INVALID_FILE, "Not a torrent file address: $uri")
        }
        transferInitializing(spec)
        reader = engine.openReader(torrent, file)
        val size = engine.fileSize(reader)
        if (spec.position > size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = spec.position
        remaining = if (spec.length == C.LENGTH_UNSET.toLong()) size - position else Math.min(spec.length, size - position)
        opened = true
        transferStarted(spec)
        return remaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = engine.read(reader, position, buffer, offset, Math.min(length.toLong(), remaining).toInt())
        // The engine reports the end only at the end of the file, which `remaining` already accounts for.
        if (n <= 0) throw TorrentException(TorrentException.ENGINE_FAILED, "The torrent's file ended early")
        position += n.toLong()
        remaining -= n.toLong()
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? {
        return uri
    }

    @Throws(IOException::class)
    override fun close() {
        uri = null
        val r = reader
        reader = 0
        try {
            if (r != 0L) engine.closeReader(r)
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        const val SCHEME = "torrent"

        /** The address of a torrent's file; the name lets Media3 recognise the container by its extension. */
        @JvmStatic
        fun uri(torrent: Long, file: TorrentFile): Uri {
            return Uri.Builder().scheme(SCHEME).authority(torrent.toString())
                    .appendPath(file.index.toString()).appendPath(file.name()).build()
        }
    }
}
