package tv.stream.app.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import java.util.Objects
import java.util.function.Consumer
import tv.stream.app.addon.Title

/**
 * Full-screen artwork behind the browse screens. Changes wait briefly so scrolling past cards doesn't
 * download every backdrop, then cross-fade between two image layers.
 */
class Backdrop(
    private val artwork: Artwork,
    /** {@code top} is drawn above {@code bottom}; the newest image alternates between them. */
    private val top: ImageView,
    private val bottom: ImageView,
    /** The softened (lower-half blurred) copy of the current artwork, cross-faded above the sharp layers. */
    private val soft: ImageView,
    private val scrim: View
) {
    private var topCurrent = false
    private val main = Handler(Looper.getMainLooper())
    private var shown: String? = null
    private var wanted: String? = null
    private var generation = 0

    /** Shows the title's artwork, sharp or blurred, or the plain canvas for null. */
    fun show(title: Title?, immediately: Boolean, blurred: Boolean) {
        val url = art(title)
        val key = if (url == null) null else if (blurred) "$url#blurred" else url
        if (Objects.equals(key, wanted)) return
        wanted = key
        val mine = ++generation
        main.removeCallbacksAndMessages(null)
        if (url == null) { clear(); return }
        val done = Consumer<Bitmap> { bitmap -> if (mine == generation) swap(key!!, bitmap) }
        main.postDelayed({
            if (blurred) { artwork.blurred(url, done); soften(null); return@postDelayed }
            artwork.fetch(url, Artwork.BACKDROP, false, done)
            artwork.softened(url, Consumer { bitmap -> if (mine == generation) soften(bitmap) })
        }, if (immediately) 0L else SETTLE_MS)
    }

    private fun swap(url: String, bitmap: Bitmap) {
        if (url == shown) return
        val wasVisible = shown != null
        shown = url
        top.animate().cancel(); bottom.animate().cancel()
        topCurrent = !topCurrent || !wasVisible
        if (topCurrent) {
            // Fade the new image in above the old one.
            top.setImageBitmap(bitmap); top.alpha = 0f; top.animate().alpha(1f).setDuration(FADE_MS).start()
            if (!wasVisible) bottom.alpha = 0f
        } else {
            // Put the new image underneath, then fade the old one away.
            bottom.setImageBitmap(bitmap); bottom.alpha = 1f; top.animate().alpha(0f).setDuration(FADE_MS).start()
        }
        scrim.animate().cancel(); scrim.animate().alpha(1f).setStartDelay(0).setDuration(FADE_MS).start()
    }

    /** Cross-fades the softened layer to {@code bitmap}, or away for none. */
    private fun soften(bitmap: Bitmap?) {
        var before: android.graphics.drawable.Drawable? = if (soft.drawable is android.graphics.drawable.TransitionDrawable)
            (soft.drawable as android.graphics.drawable.TransitionDrawable).getDrawable(1) else soft.drawable
        if (before == null) before = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        val after: android.graphics.drawable.Drawable = if (bitmap == null) android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            else android.graphics.drawable.BitmapDrawable(soft.resources, bitmap)
        val fade = android.graphics.drawable.TransitionDrawable(arrayOf(before, after))
        fade.setCrossFadeEnabled(true)
        soft.setImageDrawable(fade)
        fade.startTransition(FADE_MS.toInt())
    }

    private fun clear() {
        shown = null
        soften(null)
        for (layer in arrayOf<View>(top, bottom)) { layer.animate().cancel(); layer.animate().alpha(0f).setDuration(FADE_MS).start() }
        // The scrim leaves once the artwork has mostly gone; fading both together would brighten the artwork midway.
        scrim.animate().cancel()
        scrim.animate().alpha(0f).setStartDelay(FADE_MS / 2).setDuration(FADE_MS / 2).start()
    }

    fun release() { main.removeCallbacksAndMessages(null); generation++ }

    companion object {
        private const val SETTLE_MS = 280L
        private const val FADE_MS = 420L

        @JvmStatic fun art(title: Title?): String? {
            if (title == null) return null
            return if (title.background != null) title.background else title.poster
        }
    }
}
