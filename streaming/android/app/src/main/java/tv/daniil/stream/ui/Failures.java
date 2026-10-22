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
                case CERTIFICATE: return "The provider's certificate could not be verified. Check its address or choose another provider.";
                case TIMEOUT: return "The provider did not respond in time. Try again or choose another provider.";
                case CANCELLED: return "Cancelled.";
                case INVALID_URL: return "Enter a valid HTTP(S) manifest or torrent address, or a valid magnet link.";
                case MALFORMED: return "The provider returned data this app could not read.";
                default: return "The provider could not be reached. Check your connection and try again.";
            }
        }
        if (error instanceof TorrentException) {
            switch (((TorrentException) error).kind) {
                case LOW_STORAGE: return "There is not enough free space for the playback cache and storage reserve. Clear this app's cache or choose a smaller cache in Settings.";
                case TIMEOUT: return "No peer supplied the torrent's metadata in time. Retry or choose another source.";
                case INVALID_FILE: return "The advertised file index does not exist. Choose another source.";
                case PIECE_TOO_LARGE: case CACHE_TOO_SMALL: return "This torrent's pieces do not fit the playback cache. Increase the cache or choose another source.";
                case BAD_SOURCE: case METADATA_FAILED: return "The torrent metadata could not be read. Check the import or choose another source.";
                default: return "The torrent could not continue. Retry or choose another source.";
            }
        }
        return "This action could not be completed. Check storage and your connection, then try again.";
    }
}
