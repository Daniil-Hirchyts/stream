package tv.stream.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Layered, soft pools of colour; each category has its own hue, light position and direction. */
class CategoryBackground(private val index: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var base: Shader? = null
    private var light: Shader? = null
    private var bloom: Shader? = null
    private fun color(hue: Float, saturation: Float, brightness: Float): Int {
        return Color.HSVToColor(floatArrayOf((hue % 360 + 360) % 360, saturation, brightness))
    }
    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        if (w <= 0 || h <= 0) return
        val hue = (218 + index * 137.508f) % 360
        val reverse = index % 2 == 1
        base = LinearGradient(0f, if (reverse) h else 0f, w, if (reverse) 0f else h,
                intArrayOf(color(hue, .55f, .43f), color(hue + 38, .62f, .19f)), null, Shader.TileMode.CLAMP)
        light = RadialGradient(w * (if (index % 3 == 0) .18f else .82f), h * .05f, w * .85f,
                intArrayOf(color(hue - 28, .48f, .67f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        bloom = RadialGradient(w * (if (index % 3 == 0) .95f else .1f), h * 1.1f, w * .68f,
                intArrayOf(color(hue + 65, .58f, .44f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) {
        if (base == null) return
        paint.shader = base; canvas.drawRect(bounds, paint)
        paint.shader = light; canvas.drawRect(bounds, paint)
        paint.shader = bloom; canvas.drawRect(bounds, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int { return PixelFormat.OPAQUE }
}
