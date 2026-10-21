package tv.daniil.torrent;

import java.io.IOException;

/** A streaming failure with a kind the app can act on; an IOException so Media3 reports it as a load error. */
public final class TorrentException extends IOException {
    // The same numbers as dtv::error_kind in streaming/native/src/engine.hpp.
    public static final int INVALID_HANDLE = 1, CANCELLED = 2, CLOSED = 3, TIMEOUT = 4, BAD_SOURCE = 5,
            METADATA_FAILED = 6, PIECE_TOO_LARGE = 7, CACHE_TOO_SMALL = 8, LOW_STORAGE = 9, INVALID_FILE = 10,
            TORRENT_FAILED = 11, ENGINE_FAILED = 12;

    public final int kind;

    // Called from native code.
    public TorrentException(int kind, String message) {
        super(message);
        this.kind = kind;
    }
}
