package tv.stream.app.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * The page spans the whole screen, so shelves scroll on beneath the navigation rail instead of stopping at a hard
 * edge beside it. Under the rail they fade out (the platform's fading edge, drawn in one pass), keeping its icons
 * legible over posters its glass does not blur.
 */
class PageViewport(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    init {
        // The fading edge is painted by draw(), which a layout without a background otherwise skips.
        setWillNotDraw(false)
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(0)
    }

    /** The width, in pixels, of the band at the left edge where the page fades out; 0 runs it cleanly to the edge. */
    fun fade(width: Int) {
        if (horizontalFadingEdgeLength == width) return
        setFadingEdgeLength(width)
        invalidate()
    }

    override fun getLeftFadingEdgeStrength(): Float = if (horizontalFadingEdgeLength > 0) 1f else 0f
    override fun getRightFadingEdgeStrength(): Float = 0f
}
