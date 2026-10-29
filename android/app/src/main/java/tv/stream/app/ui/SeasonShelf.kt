package tv.stream.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout

/** Only the episode shelf moves; the selected season and its focus stay attached. */
class SeasonShelf(context: Context) : FrameLayout(context) {
    private var motion: ValueAnimator? = null
    private var outgoing: BitmapDrawable? = null

    init {
        tag = "season-shelf"; clipChildren = false; clipToPadding = false
    }

    fun show(content: View, direction: Int) {
        val animate = childCount > 0 && isLaidOut && width > 0 && height > 0 &&
                ValueAnimator.areAnimatorsEnabled()
        // Capture the current composite before cancelling, so a rapid reversal starts from what was on screen.
        val image: Bitmap? = if (animate) snapshot() else null
        finishMotion(); removeAllViews(); addView(content)
        if (image == null) return
        val width = this.width; val height = this.height
        val drawable = BitmapDrawable(resources, image)
        outgoing = drawable
        drawable.setBounds(0, 0, width, height); overlay.add(drawable)
        val travel = (Tv.dp(context, 24f) * direction).toFloat()
        content.alpha = 0f; content.translationX = travel
        val animator = ValueAnimator.ofFloat(0f, 1f)
        motion = animator
        animator.duration = 260; animator.interpolator = PathInterpolator(.22f, 1f, .36f, 1f)
        animator.addUpdateListener { animation ->
            val fraction = animation.animatedValue as Float
            content.alpha = fraction; content.translationX = travel * (1f - fraction)
            val x = Math.round(-travel * fraction)
            outgoing!!.setBounds(x, 0, x + width, height); outgoing!!.alpha = Math.round(255 * (1f - fraction))
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { clearImage(); content.alpha = 1f; content.translationX = 0f; motion = null }
        })
        animator.start()
    }

    private fun snapshot(): Bitmap? {
        // Keep the transient image below about two MiB, including on 4K TVs.
        val scale = Math.min(1f, Math.min(960f / Math.max(width, height),
                Math.sqrt((512f * 1024f) / (width.toDouble() * height)).toFloat()))
        val image = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image); canvas.scale(scale, scale); draw(canvas)
        return image
    }
    private fun clearImage() {
        val drawable = outgoing ?: return
        overlay.remove(drawable); drawable.bitmap.recycle(); outgoing = null
    }
    private fun finishMotion() {
        if (motion != null) motion!!.cancel()
        clearImage()
    }
    override fun onDetachedFromWindow() { finishMotion(); super.onDetachedFromWindow() }
}
