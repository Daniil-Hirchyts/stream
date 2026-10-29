package tv.stream.app

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import tv.stream.app.addon.Cancellation

/** The probe owns its connection until handoff; Media3 owns reads/close afterward. TLS uses platform defaults. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class HttpProbe(uri: Uri, scope: Cancellation) : BaseDataSource(true) {
    companion object {
        private val TIMER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            val thread = Thread(r, "media-probe-timeout"); thread.isDaemon = true; thread
        }
    }
    @Volatile private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var uri: Uri
    private var headers: Map<String, List<String>> = Collections.emptyMap()
    private var remaining: Long = C.LENGTH_UNSET.toLong()
    private var transferred = false
    private var prefetched = 0
    @JvmField val until: Long = SystemClock.elapsedRealtime() + 2000
    private val probing = AtomicBoolean(true)
    private val abort = Runnable { val active = connection; active?.disconnect() }
    private val deadline: java.util.concurrent.ScheduledFuture<*>
    private val scope: Cancellation

    init {
        this.uri = uri; this.scope = scope
        scope.add(abort)
        deadline = TIMER.schedule(Runnable { if (probing.compareAndSet(true, false)) abort.run() }, 2, TimeUnit.SECONDS)
    }
    @Throws(IOException::class)
    fun connect(properties: Map<String, String?>, userAgent: String?) {
        var url = URL(uri.toString())
        val spec = DataSpec.Builder().setUri(uri).build()
        var redirects = 0
        while (redirects <= 5) {
            scope.check(); checkDeadline()
            val active = url.openConnection() as HttpURLConnection; connection = active
            val left = Math.max(1L, until - SystemClock.elapsedRealtime()).toInt()
            active.connectTimeout = left
            // Android's HttpURLConnection captures this when it connects; changing it at handoff
            // does not update the existing socket. The cancellable deadline bounds discovery.
            active.readTimeout = 30000; active.instanceFollowRedirects = false
            for (property in properties.entries) active.setRequestProperty(property.key, property.value)
            active.setRequestProperty("User-Agent", userAgent); active.setRequestProperty("Accept-Encoding", "identity")
            val status = active.responseCode
            scope.check(); checkDeadline()
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                val location = active.getHeaderField("Location"); active.disconnect()
                if (location == null) throw IOException("Media redirect has no destination")
                val next = URL(url, location)
                // Matches the regular Media3 source's default cross-protocol redirect policy.
                if (!url.protocol.equals(next.protocol, ignoreCase = true)) throw IOException("Media redirect changed protocol")
                url = next; redirects++; continue
            }
            if (status < 200 || status > 299) throw HttpDataSource.InvalidResponseCodeException(
                    status, active.responseMessage, null, active.headerFields, spec, ByteArray(0))
            uri = Uri.parse(url.toString())
            val response: MutableMap<String, List<String>> = java.util.LinkedHashMap()
            for (header in active.headerFields.entries)
                if (header.key != null && header.value != null) response[header.key] = Collections.unmodifiableList(java.util.ArrayList(header.value))
            headers = Collections.unmodifiableMap(response)
            remaining = active.contentLengthLong; input = active.inputStream; return
        }
        throw IOException("Too many media redirects")
    }
    @Throws(IOException::class)
    fun finishProbe() {
        scope.check(); checkDeadline()
        if (!probing.compareAndSet(true, false)) throw java.net.SocketTimeoutException("Media format probe timed out")
        // Keep the existing socket and its normal read timeout; only discovery loses its deadline.
        deadline.cancel(false); scope.remove(abort)
    }
    @Throws(java.net.SocketTimeoutException::class)
    private fun checkDeadline() {
        if (!probing.get() || SystemClock.elapsedRealtime() >= until) throw java.net.SocketTimeoutException("Media format probe timed out")
    }
    @Throws(IOException::class)
    fun sniff(target: ByteArray, count: Int): Int { checkDeadline(); val read = readInput(target, 0, count); if (read > 0) prefetched += read; return read }
    fun length(): Long = remaining
    override fun open(spec: DataSpec): Long {
        transferInitializing(spec); transferred = true; transferStarted(spec); if (prefetched > 0) bytesTransferred(prefetched); return remaining
    }
    @Throws(IOException::class)
    override fun read(target: ByteArray, offset: Int, count: Int): Int {
        if (count == 0) return 0
        val read = readInput(target, offset, count); if (read > 0) bytesTransferred(read); return read
    }
    @Throws(IOException::class)
    private fun readInput(target: ByteArray, offset: Int, count: Int): Int {
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val read = input!!.read(target, offset, (if (remaining < 0) count.toLong() else Math.min(count.toLong(), remaining)).toInt())
        if (read < 0 && remaining > 0) throw EOFException("Media response ended early")
        if (read > 0 && remaining >= 0) remaining -= read.toLong()
        return read
    }
    override fun getUri(): Uri? = uri
    override fun getResponseHeaders(): Map<String, List<String>> = headers
    @Throws(IOException::class)
    override fun close() {
        probing.set(false); deadline.cancel(false); scope.remove(abort); abort.run()
        try { if (input != null) input!!.close() }
        finally { input = null; connection = null; if (transferred) { transferred = false; transferEnded() } }
    }
}
