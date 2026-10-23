package tv.daniil.stream.ui;

import tv.daniil.stream.addon.AddonException;
import tv.daniil.torrent.TorrentException;
import static tv.daniil.torrent.TorrentException.*;

/** User-facing failures never include provider URL paths, credentials or native/private file paths. */
public final class Failures {
    private Failures() {}
    public static String message(Throwable error) {
        if (error instanceof AddonException) {
            switch (((AddonException) error).kind) {
                case CERTIFICATE: return "The provider's certificate can't be verified.";
                case TIMEOUT: return "The provider didn't respond in time.";
                case CANCELLED: return "Cancelled.";
                case INVALID_URL: return "Enter a valid add-on, torrent or magnet address.";
                case MALFORMED: return "The provider sent data this app can't read.";
                default: return "The provider can't be reached. Check your connection.";
            }
        }
        if (error instanceof TorrentException) {
            switch (((TorrentException) error).kind) {
                case LOW_STORAGE: return "Not enough free space for the playback cache.";
                case TIMEOUT: return "No peers sent the file list in time.";
                case INVALID_FILE: return "The listed file isn't in this torrent.";
                case PIECE_TOO_LARGE: case CACHE_TOO_SMALL: return "This torrent's pieces don't fit the playback cache.";
                case BAD_SOURCE: case METADATA_FAILED: return "The torrent's file list can't be read.";
                default: return "The torrent stopped.";
            }
        }
        return "Something went wrong. Check storage and your connection.";
    }
}
