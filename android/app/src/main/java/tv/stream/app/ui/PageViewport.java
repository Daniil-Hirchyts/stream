package tv.stream.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * The page spans the whole screen, so shelves scroll on beneath the navigation rail instead of stopping at a hard
 * edge beside it. Under the rail they fade out (the platform's fading edge, drawn in one pass), keeping its icons
 * legible over posters its glass does not blur.
 */
public final class PageViewport extends FrameLayout {
    public PageViewport(Context context, AttributeSet attrs) {
        super(context, attrs);
        // The fading edge is painted by draw(), which a layout without a background otherwise skips.
        setWillNotDraw(false);
        setHorizontalFadingEdgeEnabled(true);
        setFadingEdgeLength(0);
    }

    /** The width, in pixels, of the band at the left edge where the page fades out; 0 runs it cleanly to the edge. */
    public void fade(int width) {
        if (getHorizontalFadingEdgeLength() == width) return;
        setFadingEdgeLength(width);
        invalidate();
    }

    @Override protected float getLeftFadingEdgeStrength() { return getHorizontalFadingEdgeLength() > 0 ? 1f : 0f; }
    @Override protected float getRightFadingEdgeStrength() { return 0f; }
}
