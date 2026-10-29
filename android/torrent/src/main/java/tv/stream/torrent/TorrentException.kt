package tv.stream.torrent

import java.io.IOException

/** A streaming failure with a kind the app can act on; an IOException so Media3 reports it as a load error. */
class TorrentException(
    val kind: Int,
    message: String?,
) : IOException(message) { // Called from native code: (ILjava/lang/String;)V.
    companion object {
        // The same numbers as dtv::error_kind in native/src/engine.hpp.
        const val INVALID_HANDLE = 1
        const val CANCELLED = 2
        const val CLOSED = 3
        const val TIMEOUT = 4
        const val BAD_SOURCE = 5
        const val METADATA_FAILED = 6
        const val PIECE_TOO_LARGE = 7
        const val CACHE_TOO_SMALL = 8
        const val LOW_STORAGE = 9
        const val INVALID_FILE = 10
        const val TORRENT_FAILED = 11
        const val ENGINE_FAILED = 12
    }
}
