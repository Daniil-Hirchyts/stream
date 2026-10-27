package tv.stream.torrent;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;
import java.util.List;

/**
 * Media3 reads a torrent's file through this: torrent://&lt;torrent&gt;/&lt;file index&gt;/&lt;name&gt;. Each open
 * maps the requested range onto a native reader; a read waits for verified pieces, which the engine downloads
 * first. Missing data is never replaced by zeros or an early end. Media3 seeks by closing and reopening.
 */
@OptIn(markerClass = UnstableApi.class)
public final class TorrentDataSource extends BaseDataSource {
    public static final String SCHEME = "torrent";

    /** The address of a torrent's file; the name lets Media3 recognise the container by its extension. */
    public static Uri uri(long torrent, TorrentFile file) {
        return new Uri.Builder().scheme(SCHEME).authority(String.valueOf(torrent))
                .appendPath(String.valueOf(file.index)).appendPath(file.name()).build();
    }

    public static final class Factory implements DataSource.Factory {
        private final TorrentEngine engine;

        public Factory(TorrentEngine engine) {
            this.engine = engine;
        }

        @Override public DataSource createDataSource() {
            return new TorrentDataSource(engine);
        }
    }

    private final TorrentEngine engine;
    @Nullable private Uri uri;
    private long reader;
    private boolean opened;
    private long position;
    private long remaining;

    public TorrentDataSource(TorrentEngine engine) {
        super(/* isNetwork= */ true);
        this.engine = engine;
    }

    @Override public long open(DataSpec spec) throws IOException {
        uri = spec.uri;
        List<String> segments = uri.getPathSegments();
        long torrent;
        int file;
        try {
            if (!SCHEME.equals(uri.getScheme()) || uri.getAuthority() == null || segments.isEmpty()) {
                throw new NumberFormatException();
            }
            torrent = Long.parseLong(uri.getAuthority());
            file = Integer.parseInt(segments.get(0));
        } catch (NumberFormatException e) {
            throw new TorrentException(TorrentException.INVALID_FILE, "Not a torrent file address: " + uri);
        }
        transferInitializing(spec);
        reader = engine.openReader(torrent, file);
        long size = engine.fileSize(reader);
        if (spec.position > size) {
            throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
        }
        position = spec.position;
        remaining = spec.length == C.LENGTH_UNSET ? size - position : Math.min(spec.length, size - position);
        opened = true;
        transferStarted(spec);
        return remaining;
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        int n = engine.read(reader, position, buffer, offset, (int) Math.min(length, remaining));
        // The engine reports the end only at the end of the file, which `remaining` already accounts for.
        if (n <= 0) throw new TorrentException(TorrentException.ENGINE_FAILED, "The torrent's file ended early");
        position += n;
        remaining -= n;
        bytesTransferred(n);
        return n;
    }

    @Nullable @Override public Uri getUri() {
        return uri;
    }

    @Override public void close() throws IOException {
        uri = null;
        long r = reader;
        reader = 0;
        try {
            if (r != 0) engine.closeReader(r);
        } finally {
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }
}
