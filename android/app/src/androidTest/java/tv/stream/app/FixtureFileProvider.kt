package tv.stream.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Separate test-APK process supplies actual generated torrent bytes; no production dependency. */
class FixtureFileProvider : ContentProvider() {
    override fun onCreate(): Boolean { return true }
    override fun getType(uri: Uri): String? { return "application/x-bittorrent" }
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? {
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)); cursor.addRow(arrayOf<Any?>("Generated series.torrent", null)); return cursor
    }
    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if ("/series.torrent" != uri.path || "r" != mode) throw FileNotFoundException()
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            Thread({
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output: OutputStream ->
                        val connection = URL("http://127.0.0.1:58133/torrents/series.torrent").openConnection() as HttpURLConnection
                        connection.connectTimeout = 5000; connection.readTimeout = 5000
                        try { connection.inputStream.use { input: InputStream -> val buffer = ByteArray(8192); var n: Int; while (true) { n = input.read(buffer); if (n == -1) break; output.write(buffer, 0, n) } } }
                        finally { connection.disconnect() }
                    }
                } catch (ignored: Exception) { /* the real importer reports invalid/empty input */ }
            }, "fixture-file").start()
            return pipe[0]
        } catch (e: java.io.IOException) { throw FileNotFoundException("Fixture file unavailable") }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? { throw UnsupportedOperationException() }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int { throw UnsupportedOperationException() }
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int { throw UnsupportedOperationException() }
}
