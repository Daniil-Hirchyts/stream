package tv.stream.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import java.util.ArrayList
import tv.stream.app.R

/**
 * The start screen, drawn while the app really loads: drifting light behind a tilted wall of poster placeholders
 * that fill with the catalogues' own artwork as it arrives, the play mark tracing itself and the name rising
 * into place. [finish] zooms through the mark and fades the whole screen away over the page that is already
 * in place beneath it.
 */
class IntroView(context: Context) : View(context) {
    private val dp: Float
    private val started = SystemClock.uptimeMillis()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val poster = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val name = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lights = arrayOfNulls<Paint>(3)
    private val halo: Shader = RadialGradient(56.75f, 54f, 46f, intArrayOf(0x55FFFFFF, 0x1AEBEBF5, 0x00000000), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    private val glass: Shader = LinearGradient(0f, 35.5f, 0f, 72.5f, 0xB2FFFFFF.toInt(), 0x5CFFFFFF, Shader.TileMode.CLAMP)
    private val sheen: Shader = LinearGradient(-9f, 0f, 9f, 0f,
            intArrayOf(0x00FFFFFF, 0x99FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    private var pool: Shader? = null
    private val matrix = Matrix()
    private val mark = Path()
    private val traced = Path()
    private val measure = PathMeasure()
    private val rect = RectF()
    private val markBounds = RectF()
    private val posters: MutableList<Bitmap> = ArrayList()
    private val posterArrived: MutableList<Long> = ArrayList()
    private val posterShaders: MutableList<BitmapShader> = ArrayList()
    private var exiting = -1L
    private var gone: Runnable? = null

    init {
        dp = context.resources.displayMetrics.density
        setBackgroundColor(context.getColor(R.color.tv_background))
        isClickable = true; isFocusable = false
        contentDescription = "Loading $NAME"
        val colours = intArrayOf(0x332C2C2E, 0x26202024, 0x1FEBEBF5)
        for (i in lights.indices) {
            lights[i] = Paint(Paint.ANTI_ALIAS_FLAG)
            lights[i]!!.shader = RadialGradient(0f, 0f, 1f, colours[i], Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        stroke.style = Paint.Style.STROKE; stroke.strokeJoin = Paint.Join.ROUND; stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = Color.WHITE
        name.typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.geist_semibold)
        name.color = context.getColor(R.color.tv_text); name.letterSpacing = 0.12f
        name.textAlign = Paint.Align.CENTER
        val parsed: android.graphics.Path = androidx.core.graphics.PathParser.createPathFromPathData(MARK)
        mark.set(parsed); mark.close()
        mark.computeBounds(markBounds, true)
        measure.setPath(mark, true)
    }

    /** Artwork from the catalogues being loaded: it takes the next place on the poster wall. */
    fun poster(bitmap: Bitmap) {
        if (posters.size >= ROWS * POSTERS_PER_ROW) return
        // Cached posters arrive together; they still cascade onto the wall one after another.
        val now = SystemClock.uptimeMillis()
        val previous = if (posterArrived.isEmpty()) 0L else posterArrived[posterArrived.size - 1]
        posters.add(bitmap); posterArrived.add(Math.max(now, previous + 45))
        posterShaders.add(BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
    }
    /** How long the opening animation needs before the screen may leave, so it never cuts off mid-gesture. */
    /** How long the wall needs to show the posters it has been given. */
    fun postersMs(): Long { return if (posterArrived.isEmpty()) 0L else Math.max(0L, posterArrived[posterArrived.size - 1] + 700 - SystemClock.uptimeMillis()) }
    fun finishing(): Boolean { return exiting >= 0 }
    /** Zooms through the mark and fades out; {@code done} runs once the screen is invisible. */
    fun finish(done: Runnable?) {
        if (exiting >= 0) return
        gone = done
        exiting = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        pool = RadialGradient(width / 2f, height / 2f, Math.max(1, Math.max(width, height)) * 0.42f,
                intArrayOf(0xF20A0A0D.toInt(), 0xB30A0A0D.toInt(), 0x000A0A0D), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - started) / 1000f
        val exit = if (exiting < 0 || now < exiting) 0f else Math.min(1f, (now - exiting) / EXIT_MS.toFloat())
        val out = ease(exit)
        val width = width
        val height = height
        val cx = width / 2f
        val cy = height / 2f

        drawLights(canvas, t, width, height, 1f - out)
        drawWall(canvas, now, t, width, height, out)
        // A dark pool behind the mark and name keeps them legible over the posters.
        glow.shader = pool
        glow.alpha = Math.round(255 * (1f - out))
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)

        val size = 132 * dp
        val markY = cy - 40 * dp
        drawMark(canvas, now, t, cx, markY, size * (1f + 0.9f * out), 1f - Math.min(1f, out * 1.6f))
        drawName(canvas, now, cx, markY + size * 0.5f + 34 * dp, 1f - Math.min(1f, out * 2f))

        alpha = 1f - ease(Math.max(0f, (exit - 0.35f) / 0.65f))
        if (exit >= 1f) {
            val done = gone; gone = null
            if (done != null) post(done)
            return
        }
        postInvalidateOnAnimation()
    }

    /** Three soft neutral lights wandering slowly on Lissajous paths. */
    private fun drawLights(canvas: Canvas, t: Float, width: Int, height: Int, alpha: Float) {
        val radius = Math.max(width, height) * 0.55f
        val paths = arrayOf(floatArrayOf(0.30f, 0.35f, 0.21f, 0.17f), floatArrayOf(0.72f, 0.62f, 0.15f, 0.23f), floatArrayOf(0.55f, 0.25f, 0.27f, 0.12f))
        for (i in lights.indices) {
            val p = paths[i]
            val x = width * (p[0] + 0.12f * Math.sin((t * p[2] + i * 2.1f).toDouble()).toFloat())
            val y = height * (p[1] + 0.10f * Math.cos((t * p[3] + i * 1.3f).toDouble()).toFloat())
            val r = radius * (0.85f + 0.15f * Math.sin((t * 0.4f + i).toDouble()).toFloat())
            matrix.setScale(r, r); matrix.postTranslate(x, y)
            lights[i]!!.shader.setLocalMatrix(matrix)
            lights[i]!!.alpha = Math.round(255 * alpha)
            canvas.drawCircle(x, y, r, lights[i]!!)
        }
    }

    /**
     * A tilted wall of poster cards drifting sideways, row by row in alternating directions. Empty cards shimmer
     * as placeholders; each arriving poster fades into the next place.
     */
    private fun drawWall(canvas: Canvas, now: Long, t: Float, width: Int, height: Int, out: Float) {
        val cardW = 118 * dp
        val cardH = cardW * 1.5f
        val gap = 18 * dp
        val radius = 10 * dp
        val rowW = POSTERS_PER_ROW * (cardW + gap)
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.rotate(-9f)
        canvas.scale(1.08f + 0.25f * out, 1.08f + 0.25f * out)
        val wallTop = -(ROWS * (cardH + gap)) / 2f
        for (row in 0 until ROWS) {
            val direction = if (row % 2 == 0) -1f else 1f
            val drift = ((t * (14 + 6 * row) * dp) % rowW) * direction
            val y = wallTop + row * (cardH + gap)
            // Two copies of the row cover the screen however far it has drifted.
            for (copy in -2..1) for (col in 0 until POSTERS_PER_ROW) {
                val x = copy * rowW + col * (cardW + gap) + drift
                if (x > width || x + cardW < -width) continue
                rect.set(x, y, x + cardW, y + cardH)
                // Fill places so posters spread over the rows rather than completing one row first.
                val slot = col * ROWS + row
                val shimmer = 0.5f + 0.5f * Math.sin((t * 2.2f - col * 0.6f - row * 0.9f).toDouble()).toFloat()
                fill.shader = null
                fill.color = Color.WHITE
                fill.alpha = Math.round(255 * (0.035f + 0.035f * shimmer))
                canvas.drawRoundRect(rect, radius, radius, fill)
                if (slot < posters.size && now >= posterArrived[slot]) {
                    val arrived = Math.min(1f, (now - posterArrived[slot]) / 600f)
                    val bitmap = posters[slot]
                    val scale = Math.max(cardW / bitmap.width, cardH / bitmap.height)
                    matrix.setScale(scale, scale)
                    matrix.postTranslate(x + (cardW - bitmap.width * scale) / 2f, y + (cardH - bitmap.height * scale) / 2f)
                    val shader = posterShaders[slot]
                    shader.setLocalMatrix(matrix)
                    poster.shader = shader
                    poster.alpha = Math.round(255 * 0.42f * ease(arrived))
                    canvas.drawRoundRect(rect, radius, radius, poster)
                }
            }
        }
        canvas.restore()
    }

    /** Glass fills the play mark, then a broad light sweeps through its entire interior. */
    private fun drawMark(canvas: Canvas, now: Long, t: Float, cx: Float, cy: Float, size: Float, alpha: Float) {
        if (alpha <= 0f) return
        val age = now - started
        val trace = ease(Math.min(1f, age / TRACE_MS.toFloat()))
        val filled = ease(Math.max(0f, Math.min(1f, (age - TRACE_MS * 0.6f) / FILL_MS)))
        val breathe = filled * 0.03f * Math.sin((t * 2.4f).toDouble()).toFloat()
        val scale = size / 108f * (1f + breathe)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        canvas.translate(-56.75f, -54f)

        // A halo that blooms as the glass fills.
        glow.shader = halo
        glow.alpha = Math.round(255 * alpha * filled * (0.8f + 0.2f * Math.sin((t * 2.4f).toDouble()).toFloat()))
        canvas.drawCircle(56.75f, 54f, 46f, glow)

        fill.shader = glass
        fill.alpha = Math.round(255 * alpha * filled)
        canvas.drawPath(mark, fill)

        if (age >= TRACE_MS && filled > 0f) {
            val sweep = ((age - TRACE_MS) % 3200) / 1100f
            if (sweep <= 1f) {
                // The diagonal band's projection includes the whole path plus its width at both ends.
                // Move the shader, rather than skewing the drawing after calculating its travel.
                val from = markBounds.left + 0.35f * markBounds.top - 12f
                val to = markBounds.right + 0.35f * markBounds.bottom + 12f
                val x = from + (to - from) * ease(sweep)
                matrix.setSkew(-0.35f, 0f); matrix.postTranslate(x, 0f)
                sheen.setLocalMatrix(matrix)
                fill.shader = sheen; fill.alpha = Math.round(255 * alpha * filled)
                canvas.drawPath(mark, fill)
            }
        }

        traced.reset()
        measure.getSegment(0f, measure.length * trace, traced, true)
        stroke.strokeWidth = 1.6f
        stroke.alpha = Math.round(255 * alpha * (1f - 0.55f * filled))
        canvas.drawPath(traced, stroke)
        canvas.restore()
    }

    /** Shape and draw the entire word together so tracking and kerning stay consistent. */
    private fun drawName(canvas: Canvas, now: Long, cx: Float, baseline: Float, alpha: Float) {
        if (alpha <= 0f) return
        name.textSize = 28 * dp
        val start = started + (TRACE_MS * 0.7f).toLong()
        val shown = ease(Math.max(0f, Math.min(1f, (now - start) / LETTER_MS.toFloat())))
        name.alpha = Math.round(255 * alpha * shown)
        canvas.drawText(NAME, cx, baseline + (1f - shown) * 12 * dp, name)
    }

    companion object {
        private const val NAME = "STREAM"
        /** The launcher mark's play symbol, in its 108-unit viewport. */
        private const val MARK = "M40.50,42.00 L40.50,66.00 Q40.50,72.50 46.15,69.28 L67.35,57.22 Q73.00,54.00 67.35,50.78 L46.15,38.72 Q40.50,35.50 40.50,42.00 Z"
        private const val TRACE_MS = 900L
        private const val FILL_MS = 520L
        private const val LETTER_MS = 420L
        private const val LETTER_STAGGER = 55L
        private const val EXIT_MS = 700L
        private const val ROWS = 3
        private const val POSTERS_PER_ROW = 9

        @JvmStatic fun minimumMs(): Long { return TRACE_MS + FILL_MS + LETTER_STAGGER * NAME.length + LETTER_MS + 500 }

        private fun ease(x: Float): Float { val inv = 1f - x; return 1f - inv * inv * inv }
    }
}
