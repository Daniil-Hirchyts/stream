package tv.stream.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import tv.stream.torrent.*

/** Runs against the shrunk release APK. No provider configuration, application DB or public discovery is used. */
class ReleaseProbeInstrumentation : Instrumentation() {
    private var arguments: Bundle? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); this.arguments = arguments; start() }
    override fun onStart() {
        val result = Bundle()
        try { probe(); result.putString("stream", "\nOK (1 test)\n"); finish(Activity.RESULT_OK, result) }
        catch (failure: Throwable) {
            result.putString("stream", "\nFAILURES!!!\n" + Log.getStackTraceString(failure))
            result.putString("shortMsg", failure.javaClass.simpleName); finish(Activity.RESULT_CANCELED, result)
        }
    }
    @Throws(Exception::class)
    private fun probe() {
        val context: Context = targetContext
        for (mime in arrayOf("audio/eac3", "audio/true-hd", "video/mp4v-es", "video/mp43", "video/wvc1"))
            check(io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary.supportsFormat(mime))
        val origin = arguments!!.getString("fixtures", "http://127.0.0.1:58133")
        val peer = arguments!!.getString("seeder", "127.0.0.1:58134")
        val directory = File(context.cacheDir, "release-probe-" + UUID.randomUUID())
        try {
            val index = fetch("$origin/index.json")
            val fixtures = JSONArray(String(index, java.nio.charset.StandardCharsets.UTF_8)); var fixture: JSONObject? = null
            for (i in 0 until fixtures.length()) if (fixtures.getJSONObject(i).getString("name") == "unicode") fixture = fixtures.getJSONObject(i)
            check(fixture != null)
            val file = fixture!!.getJSONArray("files").getJSONObject(0)
            val expected = fetch(origin + "/media/unicode/" + Uri.encode(file.getString("path"), "/"))
            val metainfo = fetch(fixture.getString("torrent"))
            check((call("nativeVersion", arrayOf()) as String).contains("2.1.2"))
            val engine = call("nativeCreate", arrayOf(String::class.java, LONG, LONG, String::class.java,
                    BOOLEAN, BOOLEAN, BOOLEAN, BOOLEAN, BOOLEAN, BOOLEAN, BOOLEAN, String::class.java, INT),
                    directory.path, 16L shl 20, 0L, "127.0.0.1:0", false, false, false, false, false, true, false, "", 45) as Long
            try {
                val torrent = call("nativeAdd", arrayOf(LONG, ByteArray::class.java, String::class.java, String::class.java, Array<String>::class.java, Array<String>::class.java, Array<String>::class.java),
                        engine, metainfo, null, null, arrayOf<String>(), arrayOf(peer), arrayOf<String>()) as Long
                val metadata = call("nativeAwaitMetadata", arrayOf(LONG, LONG, LONG), engine, torrent, 10_000L) as String
                val selected = JSONArray(metadata).getJSONObject(0); val indexInTorrent = selected.getInt("index"); val size = selected.getLong("size")
                try { call("nativeSelectFile", arrayOf(LONG, LONG, INT), engine, torrent, 99); throw AssertionError("Invalid index accepted") }
                catch (e: TorrentException) { check(TorrentException.INVALID_FILE == e.kind) }
                call("nativeSelectFile", arrayOf(LONG, LONG, INT), engine, torrent, indexInTorrent)
                val reader = call("nativeOpenReader", arrayOf(LONG, LONG, INT), engine, torrent, indexInTorrent) as Long
                val actual = ByteArrayOutputStream(); val buffer = ByteArray(32768)
                try {
                    val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
                    var offset: Long = 0
                    while (offset < size) {
                        val n = call("nativeRead", arrayOf(LONG, LONG, LONG, ByteArray::class.java, INT, INT, LONG),
                                engine, reader, offset, buffer, 0, Math.min(buffer.size.toLong(), size - offset).toInt(), 250L) as Int
                        check(android.os.SystemClock.elapsedRealtime() < deadline); if (n < 0) continue
                        check(n > 0); actual.write(buffer, 0, n); offset += n.toLong()
                    }
                } finally { call("nativeCloseReader", arrayOf(LONG, LONG), engine, reader) }
                check(java.util.Arrays.equals(expected, actual.toByteArray()))
            } finally { call("nativeDestroy", arrayOf(LONG), engine) }
            val remaining = directory.listFiles(); check(remaining == null || remaining.size == 0)
        } finally { directory.delete() }
    }
    companion object {
        private val LONG: Class<*> = java.lang.Long.TYPE
        private val INT: Class<*> = java.lang.Integer.TYPE
        private val BOOLEAN: Class<*> = java.lang.Boolean.TYPE
        private fun check(value: Boolean) { if (!value) throw AssertionError("Release probe assertion failed") }
        @Throws(Exception::class)
        private fun fetch(address: String): ByteArray {
            val connection = java.net.URL(address).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            try {
                connection.inputStream.use { input -> ByteArrayOutputStream().use { bytes ->
                    val buffer = ByteArray(8192); var count: Int
                    while (input.read(buffer).also { count = it } != -1) { check(bytes.size() + count <= (2 shl 20)); bytes.write(buffer, 0, count) }
                    return bytes.toByteArray()
                } }
            } finally { connection.disconnect() }
        }
        /** Invoke the fixed JNI boundary in the real shrunk app; Java-only Config/Source wrappers may be merged by R8. */
        @Throws(Exception::class)
        private fun call(name: String, signature: Array<Class<*>>, vararg arguments: Any?): Any? {
            val method = TorrentEngine::class.java.getDeclaredMethod(name, *signature); method.isAccessible = true
            try { return method.invoke(null, *arguments) }
            catch (failure: java.lang.reflect.InvocationTargetException) {
                val cause = failure.cause; if (cause is Exception) throw cause; throw cause as Error
            }
        }
    }
}
