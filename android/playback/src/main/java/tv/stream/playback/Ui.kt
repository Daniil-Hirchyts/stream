package tv.stream.playback

import android.view.View
import android.view.PointerIcon
import android.widget.Button

import java.util.Locale

/** View helpers shared by player screens and the apps around them. */
object Ui {
    private const val FADE_MS = 180

    /** Give every action the same pointer, accessibility and keyboard semantics. */
    fun action(view: View, action: Runnable) {
        view.isClickable = true
        view.isFocusable = true
        view.pointerIcon = PointerIcon.getSystemIcon(view.context, PointerIcon.TYPE_HAND)
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View,
                    info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
        view.setOnClickListener { ignored -> action.run() }
    }

    /** Cheap to call repeatedly: a view already in the requested state is left alone. */
    fun fade(view: View, visible: Boolean) {
        // Cancelling also drops a pending fade-out's "gone" step.
        view.animate().cancel()
        if (visible) {
            if (view.visibility != View.VISIBLE) {
                view.alpha = 0f
                view.visibility = View.VISIBLE
            }
            if (view.alpha < 1f) view.animate().alpha(1f).setDuration(FADE_MS.toLong()).start()
        } else if (view.visibility == View.VISIBLE) {
            view.animate().alpha(0f).setDuration(FADE_MS.toLong()).withEndAction { view.visibility = View.GONE }.start()
        }
    }

    /** 0:42, 12:05 or 1:02:09. */
    fun time(ms: Long): String {
        val total = Math.max(0, ms / 1000)
        val hours = total / 3600
        val minutes = total / 60 % 60
        val seconds = total % 60
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
