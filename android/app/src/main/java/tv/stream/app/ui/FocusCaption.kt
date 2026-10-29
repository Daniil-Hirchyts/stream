package tv.stream.app.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import tv.stream.app.R

/** Two independent labels crossfade, so a focus change never replaces visible text in place. */
class FocusCaption(context: Context) : FrameLayout(context) {
    private val labels = arrayOfNulls<TextView>(2)
    private var active = -1
    private var version = 0
    private var target = ""

    init {
        tag = "action-caption"; clipChildren = false; clipToPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        layoutParams = android.widget.LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Tv.dp(context, 28f))
        for (i in labels.indices) {
            val label = Tv.oneLine(Tv.text(context, "", 12.5f, R.color.tv_text_secondary))
            label.gravity = Gravity.CENTER; label.alpha = 0f
            labels[i] = label; addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
    }
    fun show(text: String, anchor: View, scroll: HorizontalScrollView) {
        if (text == target) return
        target = text; val mine = ++version
        if (active >= 0) fadeOut(labels[active]!!)
        active = (active + 1) % labels.size
        val label = labels[active]!!; label.animate().cancel(); label.text = text
        val width = Math.max(Tv.dp(context, 160f), Math.ceil(label.paint.measureText(text).toDouble()).toInt() + Tv.dp(context, 12f))
        label.layoutParams = LayoutParams(width, LayoutParams.WRAP_CONTENT)
        label.alpha = 0f; label.translationY = Tv.dp(context, 4f).toFloat()
        post {
            if (mine != version) return@post
            val x = anchor.left + anchor.width / 2f - scroll.scrollX - width / 2f
            label.translationX = Math.max(0f, Math.min(x, Math.max(0, getWidth() - width).toFloat()))
            label.animate().alpha(1f).translationY(0f).setStartDelay(60).setDuration(180).start()
        }
    }
    fun hide(text: String) {
        if (text != target) return
        target = ""; version++
        if (active >= 0) fadeOut(labels[active]!!)
    }
    private fun fadeOut(label: TextView) {
        label.animate().cancel(); label.animate().alpha(0f).translationY(-Tv.dp(context, 3f).toFloat())
                .setStartDelay(0).setDuration(120).start()
    }
}
