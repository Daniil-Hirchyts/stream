package tv.stream.app

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import tv.stream.app.addon.Cancellation

/** Bounded format discovery for signed/extensionless URLs; the first loader reuses the opened response. */
@OptIn(markerClass = [UnstableApi::class])
class HttpMediaResolver private constructor(@JvmField val uri: Uri, @JvmField val mime: String?, private val factory: DataSource.Factory, opened: DataSource?, private val prefix: ByteArray, private val length: Long) : AutoCloseable, DataSource.Factory {
    private val pending: AtomicReference<DataSource?> = AtomicReference(opened)
    companion object {
        @JvmStatic @Throws(IOException::class)
        fun resolve(address: String, headers: Map<String, String>, scope: Cancellation): HttpMediaResolver {
            val uri = Uri.parse(address)
            val path = if (uri.path == null) "" else uri.path!!.lowercase(java.util.Locale.ROOT)
            val properties: MutableMap<String, String> = java.util.HashMap(headers)
            var agent: String? = "Stream/0.1"
            for (key in java.util.ArrayList(properties.keys)) if (key.equals("User-Agent", ignoreCase = true)) agent = properties.remove(key)
            val factory = DefaultHttpDataSource.Factory().setUserAgent(agent)
                    .setDefaultRequestProperties(properties).setConnectTimeoutMs(15000).setReadTimeoutMs(30000)
            if (path.matches(Regex(".*\\.(mp4|m4v|mkv|webm|avi|ts|m2ts|mov|mpg|mpeg|m3u8|mpd)$")))
                return HttpMediaResolver(uri, if (path.endsWith(".m3u8")) MimeTypes.APPLICATION_M3U8 else if (path.endsWith(".mpd")) MimeTypes.APPLICATION_MPD else null,
                        factory, null, ByteArray(0), C.LENGTH_UNSET.toLong())
            val opened = HttpProbe(uri, scope)
            var success = false
            try {
                opened.connect(properties, agent)
                val length = opened.length()
                var mime: String? = null
                for (header in opened.getResponseHeaders().entries) if ("Content-Type".equals(header.key, ignoreCase = true)) {
                    for (value in header.value) {
                        val type = value.lowercase(java.util.Locale.ROOT)
                        if (type.contains("mpegurl")) mime = MimeTypes.APPLICATION_M3U8
                        else if (type.contains("dash+xml")) mime = MimeTypes.APPLICATION_MPD
                    }
                }
                val bytes = ByteArrayOutputStream()
                if (mime == null) {
                    val buffer = ByteArray(4096)
                    while (bytes.size() < 32768) {
                        val count = opened.sniff(buffer, Math.min(buffer.size, 32768 - bytes.size()))
                        if (count < 0) break
                        if (count == 0) continue
                        bytes.write(buffer, 0, count)
                        val text = String(bytes.toByteArray(), StandardCharsets.UTF_8).replace("﻿", "").trim { it <= ' ' }
                        if (text.startsWith("#EXTM3U")) { mime = MimeTypes.APPLICATION_M3U8; break }
                        if (text.matches(Regex("(?s)(?:<\\?xml.*?\\?>\\s*)?<MPD(?:\\s|>).*"))) { mime = MimeTypes.APPLICATION_MPD; break }
                        // Binary containers and ordinary bodies need no further probing.
                        if (!text.startsWith("<") && !text.startsWith("#") || bytes.size() >= 4096 && !text.startsWith("<?xml")) break
                    }
                }
                opened.finishProbe()
                val resolved = opened.getUri()
                val result = HttpMediaResolver(if (resolved == null) uri else resolved, mime, factory, opened, bytes.toByteArray(), length)
                success = true; return result
            } finally {
                if (!success) try { opened.close() } catch (ignored: IOException) { /* Preserve the probe failure/status. */ }
            }
        }
    }
    override fun createDataSource(): DataSource = Reused()
    override fun close() { val opened = pending.getAndSet(null); if (opened != null) try { opened.close() } catch (ignored: IOException) {} }
    private inner class Reused : DataSource {
        var source: DataSource? = null
        var offset = 0
        var buffered = ByteArray(0)
        val listeners = java.util.ArrayList<TransferListener>()
        override fun addTransferListener(listener: TransferListener) { if (!listeners.contains(listener)) listeners.add(listener); if (source != null) source!!.addTransferListener(listener) }
        @Throws(IOException::class)
        override fun open(spec: DataSpec): Long {
            offset = 0; buffered = ByteArray(0)
            source = if (spec.position == 0L && spec.length == C.LENGTH_UNSET.toLong() && spec.httpMethod == DataSpec.HTTP_METHOD_GET
                    && spec.httpBody == null && uri == spec.uri) pending.getAndSet(null) else null
            if (source != null) { buffered = prefix; for (listener in listeners) source!!.addTransferListener(listener); source!!.open(spec); return length }
            this@HttpMediaResolver.close(); source = factory.createDataSource()
            for (listener in listeners) source!!.addTransferListener(listener)
            return source!!.open(spec)
        }
        @Throws(IOException::class)
        override fun read(target: ByteArray, start: Int, count: Int): Int {
            if (count == 0) return 0
            if (offset < buffered.size) { val copied = Math.min(count, buffered.size - offset); System.arraycopy(buffered, offset, target, start, copied); offset += copied; return copied }
            return source!!.read(target, start, count)
        }
        override fun getUri(): Uri? = if (source == null) null else source!!.uri
        override fun getResponseHeaders(): Map<String, List<String>> = if (source == null) java.util.Collections.emptyMap() else source!!.responseHeaders
        @Throws(IOException::class)
        override fun close() { val previous = source; source = null; buffered = ByteArray(0); offset = 0; previous?.close() }
    }
}
