package tv.stream.app.addon

import android.os.SystemClock
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException

/** Blocking worker-thread transport. Platform TLS/hostname verification remains untouched. */
class AddonClient : AutoCloseable {
    private val requests = Semaphore(4, true)
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    @Throws(AddonException::class)
    fun json(address: String, cancellation: Cancellation, aggregateDeadline: Long): JSONObject {
        // Built-in add-ons make their own requests, each holding a request permit only while it runs.
        if (address.startsWith(AddonUrl.BUILTIN)) return BuiltinAddons.answer(address, this, cancellation, aggregateDeadline)
        return json(address, java.util.Collections.emptyMap(), cancellation, aggregateDeadline)
    }

    /** With extra request headers, such as the identifying User-Agent some public APIs require of built-in add-ons. */
    @Throws(AddonException::class)
    fun json(address: String, headers: Map<String, String>, cancellation: Cancellation, aggregateDeadline: Long): JSONObject {
        val bytes = fetch(address, MAX_JSON_BYTES, headers, cancellation, aggregateDeadline)
        try { return JSONObject(String(bytes, java.nio.charset.StandardCharsets.UTF_8)) }
        catch (e: JSONException) {
            throw AddonException(AddonException.Kind.MALFORMED, "The add-on returned invalid JSON")
        } catch (e: StackOverflowError) {
            throw AddonException(AddonException.Kind.MALFORMED, "The add-on returned invalid JSON")
        }
    }

    @Throws(AddonException::class)
    fun fetch(address: String, limit: Int, cancellation: Cancellation, aggregateDeadline: Long): ByteArray {
        return fetch(address, limit, java.util.Collections.emptyMap(), cancellation, aggregateDeadline)
    }

    @Throws(AddonException::class)
    private fun fetch(address: String, limit: Int, headers: Map<String, String>, cancellation: Cancellation, aggregateDeadline: Long): ByteArray {
        AddonUrl.web(address)
        val end = Math.min(aggregateDeadline, SystemClock.elapsedRealtime() + REQUEST_MS)
        var acquired = false
        val timedOut = AtomicBoolean()
        try {
            while (!acquired) {
                cancellation.check()
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) throw SocketTimeoutException()
                acquired = requests.tryAcquire(Math.min(100L, left), TimeUnit.MILLISECONDS)
            }
            var current = address
            for (redirects in 0..5) {
                cancellation.check()
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) throw SocketTimeoutException()
                val connection = URL(current).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = left.toInt()
                connection.readTimeout = left.toInt()
                // Caller headers first: Wikimedia's edge refuses a User-Agent sent after Accept and Accept-Encoding.
                for (header in headers.entries) connection.setRequestProperty(header.key, header.value)
                if (!headers.containsKey("Accept")) connection.setRequestProperty("Accept", "application/json, application/x-bittorrent")
                connection.setRequestProperty("Accept-Encoding", "gzip")
                val abort = Runnable { connection.disconnect() }
                cancellation.add(abort)
                val timeout: ScheduledFuture<*> = timer.schedule(Runnable { timedOut.set(true); connection.disconnect() }, left, TimeUnit.MILLISECONDS)
                try {
                    val status = connection.responseCode
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        val location = connection.getHeaderField("Location")
                        if (location == null) throw AddonException(AddonException.Kind.HTTP, "Redirect without an address", status, null)
                        val target = URL(URL(current), location).toExternalForm()
                        val targetUri = AddonUrl.web(target)
                        if ("https".equals(AddonUrl.web(current).scheme, ignoreCase = true)
                                && !"https".equals(targetUri.scheme, ignoreCase = true)) {
                            throw AddonException(AddonException.Kind.INVALID_URL, "Refused an insecure redirect")
                        }
                        current = target
                        continue
                    }
                    if (status < 200 || status >= 300) throw AddonException(AddonException.Kind.HTTP, "Add-on HTTP $status", status, null)
                    if (connection.contentLengthLong > limit) throw tooLarge()
                    connection.inputStream.use { raw ->
                        (if ("gzip".equals(connection.contentEncoding, ignoreCase = true)) GZIPInputStream(raw) else raw).use { input: InputStream ->
                            ByteArrayOutputStream().use { out ->
                                val buffer = ByteArray(8192)
                                var n: Int
                                while (input.read(buffer).also { n = it } != -1) {
                                    cancellation.check()
                                    if (SystemClock.elapsedRealtime() >= end || timedOut.get()) throw SocketTimeoutException()
                                    if (out.size() + n > limit) throw tooLarge()
                                    out.write(buffer, 0, n)
                                }
                                cancellation.check()
                                if (timedOut.get()) throw SocketTimeoutException()
                                return out.toByteArray()
                            }
                        }
                    }
                } finally {
                    timeout.cancel(false)
                    cancellation.remove(abort)
                    connection.disconnect()
                }
            }
            throw AddonException(AddonException.Kind.HTTP, "Too many add-on redirects")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AddonException(AddonException.Kind.CANCELLED, "Request cancelled")
        } catch (e: IOException) {
            cancellation.check()
            if (e is AddonException) throw e
            if (e is SSLException) throw AddonException(AddonException.Kind.CERTIFICATE, "The server certificate could not be verified")
            if (e is SocketTimeoutException || timedOut.get()) throw AddonException(AddonException.Kind.TIMEOUT, "The add-on timed out")
            // Do not expose exception messages/causes: they may contain credential-bearing URLs.
            throw AddonException(AddonException.Kind.NETWORK, "The add-on could not be reached")
        } finally { if (acquired) requests.release() }
    }

    override fun close() { timer.shutdownNow() }

    companion object {
        const val MAX_JSON_BYTES = 2 shl 20
        const val REQUEST_MS = 10_000L
        const val DISCOVERY_MS = 15_000L

        @JvmStatic fun deadline(): Long { return SystemClock.elapsedRealtime() + DISCOVERY_MS }

        private fun tooLarge(): AddonException { return AddonException(AddonException.Kind.MALFORMED, "The add-on response is too large") }
    }
}
