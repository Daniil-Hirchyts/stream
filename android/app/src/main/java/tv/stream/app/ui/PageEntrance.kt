package tv.stream.app.ui

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView

/**
 * A new page slides a little way in from the direction of travel and fades up, starting with its first laid-out
 * frame rather than while its worker is still fetching or building it.
 */
internal class PageEntrance(private val list: RecyclerView) {
    private var pending: ViewTreeObserver.OnPreDrawListener? = null
    /** 1 after a push, -1 after Back, 0 for a root page; read once by the next [prepare]. */
    var direction = 0
    /** From [prepare] until the slide ends: a page delivered meanwhile must not interrupt it. */
    var entering = false
        private set

    /** Hides the list until the page `current` still names has a visible block, then slides it in. */
    fun prepare(current: () -> Boolean) {
        cancel()
        val from = direction; direction = 0
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return
        entering = true; list.alpha = 0f
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!current()) { cancel(); return true }
                if (list.adapter?.itemCount == 0 || !hasVisibleBlock() || list.hasPendingAdapterUpdates()) return true
                list.viewTreeObserver.removeOnPreDrawListener(this); pending = null
                enter(from); return true
            }
        }
        pending = listener
        list.viewTreeObserver.addOnPreDrawListener(listener)
    }

    fun cancel() {
        pending?.let { list.viewTreeObserver.removeOnPreDrawListener(it) }; pending = null
        entering = false
        list.animate().cancel(); list.alpha = 1f; list.translationX = 0f; list.translationY = 0f
    }

    private fun hasVisibleBlock(): Boolean {
        for (i in 0 until list.childCount) {
            val block = list.getChildAt(i) as ViewGroup
            if (block.childCount > 0 && block.getChildAt(0).visibility == View.VISIBLE && block.height > 0) return true
        }
        return false
    }

    private fun enter(direction: Int) {
        list.animate().cancel()
        list.alpha = 0f
        list.translationX = (direction * Tv.dp(list.context, 36f)).toFloat()
        list.translationY = (if (direction == 0) Tv.dp(list.context, 16f) else 0).toFloat()
        list.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
                .withEndAction { entering = false }.start()
    }
}
