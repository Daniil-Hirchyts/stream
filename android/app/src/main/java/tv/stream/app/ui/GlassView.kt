package tv.stream.app.ui

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import tv.stream.app.R

/**
 * A rounded backdrop-blurred surface: the tab bar and search field, and the buttons, chips and notices on a page.
 * Live blur uses RenderEffect (Android 12+): one small GPU pass for each surface while what is behind it changes, and
 * none while the screen is still. Earlier versions would blur on the CPU before every frame, which stalls a TV stick,
 * so there the chrome uses a denser static material and buttons keep their plain platter. Not for list rows, tiles
 * or keyboard keys: they are many, and rows and keys sit on the plain canvas.
 */
class GlassView : BlurView {
    private var configuredTarget: BlurTarget? = null

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    private fun init() {
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        if (!LIVE_BLUR) setBackgroundResource(R.drawable.tv_tabs_solid)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!LIVE_BLUR) return
        val target: View? = rootView.findViewById(R.id.blur_target)
        if (target is BlurTarget && configuredTarget !== target) {
            configuredTarget = target
            // Downscaled 4x before blurring: soft at TV distance and a sixteenth of the pixels.
            setupWith(target, 4f, false).setBlurRadius(18f).setOverlayColor(Color.TRANSPARENT)
        }
    }

    companion object {
        val LIVE_BLUR: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        /** A plain container where live blur isn't available; the caller sets its platter. */
        fun surface(context: Context): android.widget.FrameLayout {
            return if (LIVE_BLUR) GlassView(context) else android.widget.FrameLayout(context)
        }
    }
}
