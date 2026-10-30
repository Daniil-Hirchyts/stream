package tv.stream.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.function.Consumer
import tv.stream.app.addon.AddonClient
import tv.stream.app.addon.Cancellation

/**
 * Optional, bounded image work: 8 MiB of decoded bitmaps in memory, and the downloaded bytes in a bounded disk cache so
 * scrolling back or reopening a page never downloads artwork again. A failed image never blocks a catalogue or playback.
 *
 * Requests for the same image and size share one decode ([SharedRequests]), and every size of an image shares one
 * download ([SharedWork]). Rebinding a view cancels only that view's request; the work stops when nothing else waits for
 * it. Downloads are capped at 4 MiB and decodes are subsampled to twice the target size, so neither memory nor time grows
 * with the source image. Only bytes that read as an image are saved, and a saved file that stops doing so is discarded
 * and fetched again, so no failure outlives its attempt.
 */
class Artwork
/** @param disk this app's private cache directory for downloaded artwork, or null for memory only */
constructor(private val disk: File?) : AutoCloseable {
    private val cache = object : LruCache<String, Bitmap>(8 shl 20) {
        override fun sizeOf(key: String, value: Bitmap): Int { return value.allocationByteCount }
    }
    private val main = Handler(Looper.getMainLooper())
    private val requests = SharedRequests<Bitmap>(48, 2) { r -> main.post(r) }
    private val downloads = SharedWork<ByteArray>()
    private val client = AddonClient()
    private var diskBytes = -1L
    /** Each bound view's outstanding request, cancelled when the view shows something else. */
    private val bindings = java.util.WeakHashMap<ImageView, SharedRequests<Bitmap>.Ticket>()

    constructor() : this(null)

    /** Requests that ran and requests that joined one already running, for measurements. */
    val started: Int get() = requests.started
    val joined: Int get() = requests.joined
    /** How many images were read from disk or downloaded, however many sizes wanted them. */
    val downloaded: Int get() = downloads.runs
    internal val memoryBytes: Int get() = cache.size()
    internal val boundViews: Int get() = bindings.size

    fun load(image: ImageView, url: String?) {
        bindings.remove(image)?.cancel()
        image.setImageDrawable(null); image.tag = url
        if (url == null) return
        // The request's callbacks hold the view, so the entry must go when it ends or the weak map keeps the view alive.
        val ticket = fetch(url, THUMBNAIL, false, SHARP, Consumer { bitmap -> bindings.remove(image); if (url == image.tag) image.setImageBitmap(bitmap) },
                Runnable { bindings.remove(image) })
        if (ticket != null) bindings[image] = ticket
    }

    /** What a request is for, which fixes its decoded size and format. */
    enum class Purpose(internal val maxSide: Int, internal val transparent: Boolean, internal val mode: Int) {
        THUMBNAIL(Artwork.THUMBNAIL, false, SHARP),
        LOGO(Artwork.LOGO, true, SHARP),
        BACKDROP(Artwork.BACKDROP, false, SHARP),
        BLURRED(Artwork.BLURRED, false, BLUR),
        SOFTENED(Artwork.BLURRED, false, SOFT),
    }

    /** One caller's interest in an image. After [cancel] neither callback runs; other callers' requests carry on. */
    class Handle internal constructor() {
        internal var ticket: SharedRequests<Bitmap>.Ticket? = null
        @Volatile internal var cancelled = false
        fun cancel() { cancelled = true; ticket?.cancel() }
    }

    /**
     * For bindings without a view, such as a composable's DisposableEffect: call [Handle.cancel] from onDispose. Call on
     * the main thread, where the callbacks run too: a memory hit calls [done] before this returns, and a null [url] calls
     * [failed] later. Exactly one of them runs unless cancelled, including by [cancel] when the screen stops, after which
     * an unfinished image needs requesting again. Bitmaps are shared with the cache: never recycle or mutate them.
     */
    fun request(url: String?, purpose: Purpose, done: Consumer<Bitmap>): Handle = request(url, purpose, done, Runnable {})
    fun request(url: String?, purpose: Purpose, done: Consumer<Bitmap>, failed: Runnable): Handle {
        val handle = Handle()
        handle.ticket = fetch(url, purpose.maxSide, purpose.transparent, purpose.mode,
                Consumer { bitmap -> if (!handle.cancelled) done.accept(bitmap) }, Runnable { if (!handle.cancelled) failed.run() })
        return handle
    }

    /** The image if it is already decoded in memory, to draw the first frame without waiting for a callback. */
    fun peek(url: String?, purpose: Purpose): Bitmap? =
            if (url == null) null else cache.get(key(url, purpose.maxSide, purpose.transparent, purpose.mode))

    /**
     * Decodes {@code url} no larger than {@code maxSide} pixels and calls {@code done} on the main thread.
     * Opaque artwork uses RGB_565; logos keep their transparency and lose transparent margins, so they align with text.
     */
    fun fetch(url: String?, maxSide: Int, transparent: Boolean, done: Consumer<Bitmap>): SharedRequests<Bitmap>.Ticket? {
        return fetch(url, maxSide, transparent, SHARP, done)
    }

    /** Resolve a title once before revealing it; a slow/failed logo keeps the text fallback for this binding. */
    fun titleLogo(url: String?, done: Consumer<Bitmap>, failed: Runnable): Handle {
        val handle = Handle()
        val generation = this.generation
        val settled = booleanArrayOf(false) // All completions run on the main thread, including memory-cache hits.
        val timeout = Runnable {
            if (settled[0] || handle.cancelled || generation != this.generation) return@Runnable
            settled[0] = true; failed.run()
        }
        main.postDelayed(timeout, 2000)
        handle.ticket = fetch(url, LOGO, true, SHARP, Consumer { bitmap ->
            if (settled[0] || handle.cancelled) return@Consumer
            settled[0] = true; main.removeCallbacks(timeout); done.accept(bitmap)
        }, timeout)
        return handle
    }

    /** A heavily blurred version, for artwork behind lists (Sources). Computed once per image on a worker. */
    fun blurred(url: String?, done: Consumer<Bitmap>) { fetch(url, BLURRED, false, BLUR, done) }

    /**
     * The blurred version fading from clear at the top to opaque lower down: laid over the sharp artwork, it blurs
     * the part behind text, buttons and shelves, as tvOS does, at the cost of one tiny bitmap.
     */
    fun softened(url: String?, done: Consumer<Bitmap>) { fetch(url, BLURRED, false, SOFT, done) }

    /** Bumped by [cancel]: timeouts of an earlier screen do nothing. */
    private var generation = 0

    private fun fetch(url: String?, maxSide: Int, transparent: Boolean, mode: Int, done: Consumer<Bitmap>): SharedRequests<Bitmap>.Ticket? {
        return fetch(url, maxSide, transparent, mode, done, Runnable {})
    }
    private fun fetch(url: String?, maxSide: Int, transparent: Boolean, mode: Int, done: Consumer<Bitmap>, failed: Runnable): SharedRequests<Bitmap>.Ticket? {
        if (url == null) {
            val generation = this.generation
            main.post { if (generation == this.generation) failed.run() }
            return null
        }
        val key = key(url, maxSide, transparent, mode)
        val known = cache.get(key)
        if (known != null) { done.accept(known); return null }
        return requests.request(key, { request -> decode(url, key, maxSide, transparent, mode, request) }, { bitmap -> done.accept(bitmap) }, { failed.run() })
    }

    private fun key(url: String, maxSide: Int, transparent: Boolean, mode: Int): String =
            url + "@" + maxSide + (if (transparent) "@alpha" else "") + (if (mode == BLUR) "@blur" else if (mode == SOFT) "@soft" else "")

    @Throws(Exception::class)
    private fun decode(url: String, key: String, maxSide: Int, transparent: Boolean, mode: Int, request: Cancellation): Bitmap {
        // An identical request may have finished between the caller's look at the cache and this job starting.
        cache.get(key)?.let { return it }
        val bytes = bytes(url, request)
        request.check()
        val options = bounds(bytes) ?: throw java.io.IOException("Invalid artwork")
        options.inSampleSize = 1
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > maxSide * 2) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        options.inPreferredConfig = if (transparent || mode != SHARP) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        val decoded: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (decoded == null) { forget(url); throw java.io.IOException("Invalid artwork") }
        request.check()
        var bitmap = fit(decoded, maxSide)
        if (transparent) bitmap = trim(bitmap)
        if (mode != SHARP) bitmap = blur(bitmap, mode == SOFT)
        // A PNG with an alpha channel can ignore inPreferredConfig even when this purpose is opaque.
        if (!transparent && mode == SHARP && bitmap.config != Bitmap.Config.RGB_565) {
            val opaque = bitmap.copy(Bitmap.Config.RGB_565, false) ?: throw java.io.IOException("Invalid artwork")
            bitmap.recycle(); bitmap = opaque
        }
        cache.put(key, bitmap)
        return bitmap
    }

    /** An image's size, or null when the bytes don't read as an image. */
    private fun bounds(bytes: ByteArray): BitmapFactory.Options? {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options else null
    }

    /**
     * The image's bytes from the disk cache, else downloaded and saved there; one read or download per image at a time,
     * for every size waiting. Only an image is saved: an error page or damaged file must not fail every later attempt.
     */
    @Throws(Exception::class)
    private fun bytes(url: String, request: Cancellation): ByteArray = downloads.get(url, request) { shared ->
        val file = if (disk == null) null else File(disk, name(url))
        var saved: ByteArray? = null
        if (file != null && file.isFile) {
            saved = try { Files.readAllBytes(file.toPath()) } catch (damaged: Exception) { null }
            if (saved == null || bounds(saved) == null) { saved = null; forget(file) }
            else file.setLastModified(System.currentTimeMillis())
        }
        saved ?: client.fetch(url, MAX_DOWNLOAD, shared, AddonClient.deadline()).also { bytes ->
            if (bounds(bytes) == null) throw java.io.IOException("Invalid artwork")
            if (file != null) store(file, bytes)
        }
    }

    private fun forget(url: String) { if (disk != null) forget(File(disk, name(url))) }
    @Synchronized private fun forget(file: File) {
        val size = file.length()
        if (file.delete() && diskBytes >= 0) diskBytes -= size
    }

    @Synchronized private fun store(file: File, bytes: ByteArray) {
        val disk = disk!!
        val partial = File(disk, file.name + ".part")
        try {
            if (!disk.isDirectory && !disk.mkdirs()) return
            FileOutputStream(partial).use { out -> out.write(bytes) }
            val replaced = file.length()
            if (!partial.renameTo(file)) return
            if (diskBytes < 0) { diskBytes = 0; val all = disk.listFiles(); if (all != null) for (f in all) diskBytes += f.length() }
            else diskBytes += bytes.size.toLong() - replaced
            if (diskBytes <= DISK_LIMIT) return
            // Least recently shown first, down to the trim size. Times are read once: readers touch them during the sort.
            val all = disk.listFiles() ?: return
            for (old in all.map { it to it.lastModified() }.sortedBy { it.second }.map { it.first }) {
                if (diskBytes <= DISK_TRIM) break
                val size = old.length()
                if (old.delete()) diskBytes -= size
            }
        } catch (ignored: Exception) { /* the cache is optional */ }
        finally { partial.delete() }
    }

    fun cancel() { generation++; bindings.clear(); requests.cancelAll() }
    override fun close() { generation++; bindings.clear(); requests.close(); client.close(); cache.evictAll(); main.removeCallbacksAndMessages(null) }

    companion object {
        /** Posters and thumbnails, decoded near their on-screen size. */
        const val THUMBNAIL = 440
        /** Logos keep a little more detail: they are drawn wider than posters. */
        const val LOGO = 512
        /** Full-screen backdrops: soft at TV distance, and a quarter of the memory of 1080p. */
        const val BACKDROP = 1280
        /** Blurred backdrops are rendered tiny and scaled up: the scaling itself is most of the blur. */
        private const val BLURRED = 96
        private const val MAX_DOWNLOAD = 4 shl 20
        private const val DISK_LIMIT = 40L shl 20
        private const val DISK_TRIM = 30L shl 20

        private const val SHARP = 0
        private const val BLUR = 1
        private const val SOFT = 2

        @Throws(Exception::class)
        private fun name(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(StandardCharsets.UTF_8))
            val out = StringBuilder()
            for (i in 0 until 16) out.append(String.format(java.util.Locale.ROOT, "%02x", digest[i].toInt() and 255))
            return out.toString()
        }

        private fun fit(source: Bitmap, maxSide: Int): Bitmap {
            val side = Math.max(source.width, source.height)
            if (side <= maxSide) return source
            val scale = maxSide / side.toFloat()
            val scaled = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.width * scale)), Math.max(1, Math.round(source.height * scale)), true)
            if (scaled !== source) source.recycle()
            return scaled
        }

        /** Crops fully transparent margins (logo files often centre a small mark in a wide canvas). */
        fun trim(source: Bitmap): Bitmap {
            val width = source.width
            val height = source.height
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            var left = width
            var right = -1
            var top = height
            var bottom = -1
            for (y in 0 until height) for (x in 0 until width) {
                if ((pixels[y * width + x] ushr 24) > 12) { left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y) }
            }
            if (right < 0 || (left == 0 && top == 0 && right == width - 1 && bottom == height - 1)) return source
            val cropped = Bitmap.createBitmap(source, left, top, right - left + 1, bottom - top + 1)
            if (cropped !== source) source.recycle()
            return cropped
        }

        /** Three box-blur passes over a tiny bitmap approximate a wide Gaussian blur once it is scaled to the screen. */
        fun blur(source: Bitmap): Bitmap { return blur(source, false) }
        /** @param fade make the top transparent, easing to opaque from 30% to 75% of the height */
        fun blur(source: Bitmap, fade: Boolean): Bitmap {
            val width = source.width
            val height = source.height
            val radius = Math.max(2, Math.min(width, height) / 12)
            val pixels = IntArray(width * height)
            val buffer = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            for (pass in 0 until 3) { box(pixels, buffer, width, height, radius, true); box(buffer, pixels, width, height, radius, false) }
            if (fade) for (y in 0 until height) {
                val t = Math.max(0f, Math.min(1f, (y / Math.max(1, height - 1).toFloat() - 0.3f) / 0.45f))
                val alpha = Math.round(255 * t * t * (3 - 2 * t))
                for (x in 0 until width) pixels[y * width + x] = (alpha shl 24) or (pixels[y * width + x] and 0xFFFFFF)
            }
            val out = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            source.recycle()
            return out
        }
        private fun box(input: IntArray, out: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
            val lines = if (horizontal) height else width
            val length = if (horizontal) width else height
            for (line in 0 until lines) {
                for (i in 0 until length) {
                    var r = 0
                    var g = 0
                    var b = 0
                    var n = 0
                    for (k in Math.max(0, i - radius)..Math.min(length - 1, i + radius)) {
                        val p = input[if (horizontal) line * width + k else k * width + line]
                        r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; n++
                    }
                    out[if (horizontal) line * width + i else i * width + line] = 0xFF000000.toInt() or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
        }
    }
}
