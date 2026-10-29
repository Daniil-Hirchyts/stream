package tv.stream.torrent

import android.os.Bundle

import androidx.test.platform.app.InstrumentationRegistry

import org.json.JSONArray
import org.json.JSONObject

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.ArrayList

/**
 * The host's fixtures (simulator): torrents and their expected contents from the fixture service, and
 * the address of the real seeder. Both reach the emulator's loopback through adb reverse.
 */
class Fixtures private constructor(@JvmField val base: String, @JvmField val seeder: String, private val index: JSONArray) {
    class File @Throws(Exception::class) constructor(json: JSONObject) {
        @JvmField val index: Int
        @JvmField val path: String
        @JvmField val size: Long
        @JvmField val sha256: String

        init {
            index = json.getInt("index")
            path = json.getString("path")
            size = json.getLong("size")
            sha256 = json.getString("sha256")
        }
    }

    class Torrent @Throws(Exception::class) constructor(json: JSONObject) {
        @JvmField val name: String; @JvmField val url: String; @JvmField val v1: String; @JvmField val v2: String
        @JvmField val pieceLength: Int
        @JvmField val files: MutableList<File> = ArrayList()

        init {
            name = json.getString("name")
            url = json.getString("torrent")
            v1 = json.getString("v1")
            v2 = json.getString("v2")
            pieceLength = json.getInt("pieceLength")
            val items = json.getJSONArray("files")
            for (i in 0 until items.length()) files.add(File(items.getJSONObject(i)))
        }

        fun file(nameEnd: String): File {
            for (f in files) if (f.path.endsWith(nameEnd)) return f
            throw AssertionError("No file ending $nameEnd in $name")
        }
    }

    @Throws(Exception::class)
    fun torrent(name: String): Torrent {
        for (i in 0 until index.length()) {
            val t = index.getJSONObject(i)
            if (t.getString("name") == name) return Torrent(t)
        }
        throw AssertionError("No fixture $name; run ./simulator.sh services")
    }

    @Throws(IOException::class)
    fun metainfo(t: Torrent): ByteArray {
        return get(t.url)
    }

    companion object {
        @JvmStatic @Throws(Exception::class)
        fun load(): Fixtures {
            val args: Bundle = InstrumentationRegistry.getArguments()
            val base = args.getString("fixtures", "http://127.0.0.1:58133")
            val seeder = args.getString("seeder", "127.0.0.1:58134")
            val index = JSONArray(String(get("$base/index.json"), StandardCharsets.UTF_8))
            return Fixtures(base, seeder, index)
        }

        @JvmStatic @Throws(IOException::class)
        fun get(address: String): ByteArray {
            val c = URL(address).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 10000
            try {
                c.inputStream.use { `in`: InputStream ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16384)
                    var n: Int
                    while (`in`.read(buf).also { n = it } >= 0) out.write(buf, 0, n)
                    return out.toByteArray()
                }
            } finally {
                c.disconnect()
            }
        }
    }
}
