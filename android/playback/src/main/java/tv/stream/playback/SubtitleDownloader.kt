package tv.stream.playback

import android.net.Uri
import android.os.SystemClock

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Fetches external subtitle files before playback starts. Media3 stops the whole video when a side-loaded file
 * fails, so a missing, expired or oversized file is skipped here instead. Files saved in a legacy single-byte
 * encoding, common for Cyrillic SRT, are converted to UTF-8.
 */
object SubtitleDownloader {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val DEADLINE_MS: Long = 8000
    private val SRT: Pattern = Pattern.compile("^\\s*\\d+\\s*\\R\\s*\\d{1,2}:\\d{2}:\\d{2},\\d{1,3}\\s*-->")
    private val DOTTED_CUES: Pattern = Pattern.compile("\\d{2}:\\d{2}\\.\\d{3}\\s*-->")

    /** One subtitle file; empty strings mean unknown. The content decides the format when the type is wrong. */
    data class Source(val url: String, val mime: String, val language: String, val label: String) {
        override fun toString(): String = "Subtitle ($mime, $language)"

        constructor(json: JSONObject) : this(json.optString("url"), json.optString("mime"), json.optString("language"), json.optString("label"))
    }

    private data class Text(val mime: String, val body: String)

    /** Reads `[{"url", "mime", "language", "label"}…]`, the format the Chrome extension sends. */
    @Throws(JSONException::class)
    fun parse(json: String?): List<Source> {
        val sources: MutableList<Source> = ArrayList()
        if (json == null || json.isEmpty()) return sources
        val items = JSONArray(json)
        for (i in 0 until items.length()) sources.add(Source(items.getJSONObject(i)))
        return sources
    }

    /** Blocks for at most eight seconds and returns the files that arrived, in their original order. */
    fun download(directory: File, sources: List<Source>,
            headers: Map<String, String>): List<MediaItem.SubtitleConfiguration> {
        val old = directory.listFiles()
        if (old != null) for (file in old) file.delete()
        directory.mkdirs()
        val pool: ExecutorService = Executors.newFixedThreadPool(Math.min(4, sources.size))
        val accepting = java.util.concurrent.atomic.AtomicBoolean(true)
        val pending: MutableList<Future<MediaItem.SubtitleConfiguration?>> = ArrayList()
        for (i in sources.indices) {
            val index = i
            pending.add(pool.submit(Callable { fetch(directory, index, sources[index], headers, accepting) }))
        }
        pool.shutdown()
        val deadline = SystemClock.elapsedRealtime() + DEADLINE_MS
        val result: MutableList<MediaItem.SubtitleConfiguration> = ArrayList()
        for (future in pending) {
            try {
                val subtitle: MediaItem.SubtitleConfiguration? =
                        future.get(Math.max(0, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS)
                if (subtitle != null) result.add(subtitle)
            } catch (skipped: Exception) {
                future.cancel(true)
            }
        }
        pool.shutdownNow()
        // A timed-out HTTP worker can finish later. It must never recreate a retired session's directory.
        synchronized(directory) { accepting.set(false) }
        return result
    }

    @Throws(IOException::class)
    private fun fetch(directory: File, index: Int, source: Source,
            headers: Map<String, String>, accepting: java.util.concurrent.atomic.AtomicBoolean): MediaItem.SubtitleConfiguration? {
        val bytes = get(source.url, headers) ?: return null
        val text = identify(decode(bytes), source.mime) ?: return null
        val file = File(directory, "subtitle-" + index + extension(text.mime))
        synchronized(directory) {
            if (!accepting.get()) return null
            FileOutputStream(file).use { out ->
                out.write(text.body.toByteArray(StandardCharsets.UTF_8))
            }
        }
        return MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                .setId(SubtitleTracks.SIDELOADED + index)
                .setMimeType(text.mime)
                .setLanguage(if (source.language.isEmpty()) null else source.language)
                .setLabel(if (source.label.isEmpty()) null else source.label)
                .build()
    }

    @Throws(IOException::class)
    private fun get(address: String, headers: Map<String, String>): ByteArray? {
        var url = URL(address)
        // HttpURLConnection won't follow a redirect between http and https on its own.
        var redirects = 0
        while (redirects < 5) {
            if ("http" != url.protocol && "https" != url.protocol) return null
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5000
                connection.readTimeout = 6000
                connection.instanceFollowRedirects = false
                for ((key, value) in headers) {
                    connection.setRequestProperty(key, value)
                }
                val status = connection.responseCode
                val location: String? = connection.getHeaderField("Location")
                if (status >= 300 && status < 400 && location != null) {
                    url = URL(url, location)
                    redirects++
                    continue
                }
                if (status < 200 || status >= 300 || connection.contentLengthLong > MAX_BYTES) return null
                connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16384)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        if (out.size() > MAX_BYTES) return null
                    }
                    return out.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xFE) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xFF) == 0xFF) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (notUtf8: CharacterCodingException) {
            return String(bytes, Charset.forName(if (looksCyrillic(bytes)) "windows-1251" else "windows-1252"))
        }
    }

    /** Nearly every letter of windows-1251 Cyrillic is a high byte; Western text only uses them for accents. */
    private fun looksCyrillic(bytes: ByteArray): Boolean {
        var high = 0
        var letters = 0
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c >= 0xC0) high++
            if (c >= 0xC0 || (c >= 'A'.code && c <= 'Z'.code) || (c >= 'a'.code && c <= 'z'.code)) letters++
        }
        return letters > 0 && high * 3 > letters
    }

    /** Recognises the format from the content, since sites often serve subtitles as text/plain. */
    private fun identify(text: String, declared: String): Text? {
        val body = if (text.startsWith("﻿")) text.substring(1) else text
        val head = body.substring(0, Math.min(body.length, 4000)).trim { it <= ' ' }
        if (head.startsWith("WEBVTT")) return Text(MimeTypes.TEXT_VTT, body)
        if (head.startsWith("[Script Info]") || head.contains("[Events]")) return Text(MimeTypes.TEXT_SSA, body)
        if (head.startsWith("<") && head.contains("<tt")) return Text(MimeTypes.APPLICATION_TTML, body)
        if (SRT.matcher(head).find()) return Text(MimeTypes.APPLICATION_SUBRIP, body)
        // WebVTT cues without the WEBVTT header line, which Media3 requires.
        if (DOTTED_CUES.matcher(head).find()) return Text(MimeTypes.TEXT_VTT, "WEBVTT\n\n$body")
        if (head.contains("-->") && !declared.isEmpty() && !head.startsWith("<")) return Text(declared, body)
        return null
    }

    private fun extension(mime: String): String {
        return when (mime) {
            MimeTypes.APPLICATION_SUBRIP -> ".srt"
            MimeTypes.TEXT_SSA -> ".ass"
            MimeTypes.APPLICATION_TTML -> ".ttml"
            else -> ".vtt"
        }
    }
}
