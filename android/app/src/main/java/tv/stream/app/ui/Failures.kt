package tv.stream.app.ui

import tv.stream.app.addon.AddonException
import tv.stream.torrent.TorrentException

/** User-facing failures never include provider URL paths, credentials or native/private file paths. */
object Failures {
    @JvmStatic fun message(error: Throwable?): String {
        if (error is AddonException) {
            when (error.kind) {
                AddonException.Kind.CERTIFICATE -> return "The provider's certificate can't be verified."
                AddonException.Kind.TIMEOUT -> return "The provider didn't respond in time."
                AddonException.Kind.CANCELLED -> return "Cancelled."
                AddonException.Kind.INVALID_URL -> return "Enter a valid add-on, torrent or magnet address."
                AddonException.Kind.MALFORMED -> return "The provider sent data this app can't read."
                else -> return "The provider can't be reached. Check your connection."
            }
        }
        if (error is TorrentException) {
            when (error.kind) {
                TorrentException.LOW_STORAGE -> return "Not enough free space for the playback cache."
                TorrentException.TIMEOUT -> return "No peers sent the file list in time."
                TorrentException.INVALID_FILE -> return "The listed file isn't in this torrent."
                TorrentException.PIECE_TOO_LARGE, TorrentException.CACHE_TOO_SMALL -> return "This torrent's pieces don't fit the playback cache."
                TorrentException.BAD_SOURCE, TorrentException.METADATA_FAILED -> return "The torrent's file list can't be read."
                else -> return "The torrent stopped."
            }
        }
        return "Something went wrong. Check storage and your connection."
    }
}
