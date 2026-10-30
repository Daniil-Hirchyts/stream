package tv.stream.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.util.ArrayList
import tv.stream.app.R

/** Circular resting rail morphing into a contextual navigation popup above an unchanged page. */
class NavigationRail(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    enum class State { HIDDEN, WRAPPED, EXPANDED }
    private var expandedWidth = EXPANDED
    private val labels: MutableList<TextView> = ArrayList()
    private val rows: MutableList<View> = ArrayList()
    private var expansion = 0f
    private var reveal = 1f
    private var state = State.WRAPPED
    private var morph: ValueAnimator? = null
    private val surface = GradientDrawable()
    private var platter: View? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        val found: View = findViewById(R.id.top_bar_controls)
        platter = found
        found.background = surface
        apply(expansion, reveal)
    }
    fun state(): State { return state }
    /** Wrapped icons grow slightly; expanded rows stay inside the popup's even inset. */
    fun lift(): Double { return (if (state == State.EXPANDED) 1f else LIFT_WRAPPED).toDouble() }
    fun expandedWidth(): Int { return expandedWidth }
    /** The 44 dp row exactly matches its width when wrapped; text never participates in icon geometry. */
    fun decorate(row: FrameLayout, icon: Int, label: String) {
        val glyph = ImageView(context); glyph.setImageResource(icon)
        glyph.imageTintList = context.getColorStateList(R.color.tv_tab_text)
        glyph.isDuplicateParentStateEnabled = true; glyph.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val image = FrameLayout.LayoutParams(Tv.dp(context, 20f), Tv.dp(context, 20f), Gravity.START or Gravity.CENTER_VERTICAL)
        image.leftMargin = Tv.dp(context, 12f); row.addView(glyph, image); rows.add(row)
        val text = Tv.oneLine(Tv.heading(context, label, 15f, R.color.tv_tab_text))
        text.isDuplicateParentStateEnabled = true; text.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Keep a stable text width during the morph; the row reveals it without reflowing or scaling letters.
        val caption = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL)
        caption.leftMargin = Tv.dp(context, LABEL_START.toFloat()); row.addView(text, caption); labels.add(text)
        measureLabels()
        row.clipChildren = true; row.contentDescription = label; apply(expansion, reveal)
    }

    fun state(next: State) {
        val previousWidth = expandedWidth
        if (next == State.EXPANDED) measureLabels()
        if (state == next) { if (expandedWidth != previousWidth) apply(expansion, reveal); return }
        state = next
        for (row in rows) if (row.isFocused || row.isHovered) Tv.animate(row, true, lift().toFloat(), false)
        morph?.cancel()
        if (next != State.HIDDEN) visibility = VISIBLE
        // A hiding rail is still drawn while it slides out; a quick Down must find the page, not the rail.
        descendantFocusability = if (next == State.HIDDEN) FOCUS_BLOCK_DESCENDANTS else FOCUS_AFTER_DESCENDANTS
        val fromWidth = expansion
        val fromReveal = reveal
        val toWidth = if (next == State.EXPANDED) 1f else 0f
        val toReveal = if (next == State.HIDDEN) 0f else 1f
        val animator = ValueAnimator.ofFloat(0f, 1f)
        morph = animator
        animator.duration = 240; animator.interpolator = EASE
        animator.addUpdateListener { animation ->
            val t = animation.animatedValue as Float
            apply(fromWidth + (toWidth - fromWidth) * t, fromReveal + (toReveal - fromReveal) * t)
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled && state == State.HIDDEN) visibility = INVISIBLE
            }
        })
        animator.start()
    }

    private fun apply(width: Float, visible: Float) {
        expansion = width; reveal = visible
        val extra = Tv.dp(context, (expandedWidth - COLLAPSED) * width)
        val params = layoutParams
        if (params != null && params.width != Tv.dp(context, COLLAPSED.toFloat()) + extra) {
            params.width = Tv.dp(context, COLLAPSED.toFloat()) + extra; layoutParams = params
        }
        val platter = platter
        if (platter != null) {
            val resting = Color.TRANSPARENT
            val popup = context.getColor(R.color.tv_material_dialog)
            surface.setColor(blend(resting, popup, width))
            surface.cornerRadius = Tv.dp(context, 28f).toFloat()
            platter.elevation = Tv.dp(context, 12 * width).toFloat()
            platter.invalidateOutline()
        }
        alpha = visible; translationX = -Tv.dp(context, 96f) * (1 - visible)
        importantForAccessibility = if (visible == 0f) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        val alpha = Math.max(0f, (width - .25f) / .75f)
        for (text in labels) { text.alpha = alpha; text.visibility = if (alpha == 0f) INVISIBLE else VISIBLE }
    }

    /** Fit the longest actual label, preserving a 12 dp icon gap and 16 dp trailing air. Grow for larger text. */
    private fun measureLabels() {
        var longest = 0f
        val density = resources.displayMetrics.density
        for (label in labels) longest = Math.max(longest, label.paint.measureText(label.text.toString()) / density)
        val required = Math.ceil(((SURFACE_INSET * 2 + LABEL_START + longest + LABEL_END) / 8f).toDouble()).toInt() * 8
        expandedWidth = Math.max(EXPANDED, required)
        for (label in labels) {
            val params = label.layoutParams
            val width = Tv.dp(context, (expandedWidth - SURFACE_INSET * 2 - LABEL_START - LABEL_END).toFloat())
            if (params.width != width) { params.width = width; label.layoutParams = params }
        }
    }

    override fun onDetachedFromWindow() {
        morph?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        const val LIFT_WRAPPED = 1.08f
        const val COLLAPSED = 56
        const val EXPANDED = 144
        const val CONTENT_START = 104
        const val HIDDEN_CONTENT_START = 16
        private const val LABEL_START = 44
        private const val LABEL_END = 16
        private const val SURFACE_INSET = 6
        private val EASE = PathInterpolator(.2f, .7f, .15f, 1f)

        private fun blend(from: Int, to: Int, amount: Float): Int {
            return Color.argb(Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * amount),
                    Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                    Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                    Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount))
        }
    }
}
