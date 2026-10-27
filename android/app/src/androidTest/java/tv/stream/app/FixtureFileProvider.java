package tv.stream.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Separate test-APK process supplies actual generated torrent bytes; no production dependency. */
public final class FixtureFileProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/x-bittorrent"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}); cursor.addRow(new Object[]{"Generated series.torrent", null}); return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/series.torrent".equals(uri.getPath()) || !"r".equals(mode)) throw new FileNotFoundException();
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:58133/torrents/series.torrent").openConnection();
                    connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
                    try (InputStream input = connection.getInputStream()) { byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n); }
                    finally { connection.disconnect(); }
                } catch (Exception ignored) { /* the real importer reports invalid/empty input */ }
            }, "fixture-file").start();
            return pipe[0];
        } catch (java.io.IOException e) { throw new FileNotFoundException("Fixture file unavailable"); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
