package tv.stream.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.SeekBar
import tv.stream.app.R

/** Stream's timeline: a tap toggles playback, a drag retains the shared player's seek-on-release behavior. */
class PlayerTimeline(context: Context, attributes: AttributeSet?) : SeekBar(context, attributes) {
    private val slop: Int
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var dragging = false
    private var paused = false
    private var readout: LinearLayout? = null
    private var time: View? = null
    private var status: View? = null
    private var pause: View? = null
    private var seek: View? = null
    private var end: View? = null
    private val placeTime = ViewTreeObserver.OnPreDrawListener { positionTime() }

    init {
        slop = ViewConfiguration.get(context).scaledTouchSlop
        pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HAND)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(placeTime)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(placeTime)
        super.onDetachedFromWindow()
    }

    /** The readout owns pause/seek priority so a transient skip never crowds a second status icon. */
    fun setPaused(paused: Boolean) {
        if (this.paused == paused) return
        this.paused = paused
        invalidate()
    }

    private fun positionTime(): Boolean {
        if (readout == null) {
            val root = rootView
            readout = root.findViewById(R.id.timeline_info)
            time = root.findViewById(R.id.elapsed)
            status = root.findViewById(R.id.timeline_status)
            pause = root.findViewById(R.id.timeline_paused)
            seek = root.findViewById(R.id.seek_bubble)
            end = root.findViewById(R.id.remaining)
        }
        val readout = readout
        if (readout == null || !isShown) return true
        val seeking = seek!!.visibility == VISIBLE
        // Re-measure before drawing when status changes; don't expose one frame of stale bounds.
        var changed = PlayerTimeline.visibility(pause!!, paused && !seeking)
        changed = changed or PlayerTimeline.visibility(status!!, paused || seeking)
        if (changed) { postInvalidateOnAnimation(); return false }
        if (readout.width == 0 || time!!.width == 0) return true
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var fraction = if (max == 0) 0f else progress / max.toFloat()
        if (rtl) fraction = 1 - fraction
        val playhead = paddingLeft + (width - paddingLeft - paddingRight) * fraction
        val gap = Tv.dp(context, 12f).toFloat()
        val statusGap = Tv.dp(context, 6f).toFloat()
        val left = if (rtl) end!!.right + gap else 0f
        val right = if (rtl) width.toFloat() else end!!.left - gap
        val halfTime = time!!.width / 2f
        val statusFirst = status!!.visibility == VISIBLE && (if (rtl)
                playhead - halfTime - status!!.width - statusGap < left
                else playhead + halfTime + status!!.width + statusGap > right)
        if ((readout.getChildAt(0) === status) != statusFirst) {
            readout.removeView(status)
            readout.addView(status, if (statusFirst) 0 else 1)
            val params = status!!.layoutParams as LinearLayout.LayoutParams
            params.marginStart = if (statusFirst) 0 else statusGap.toInt()
            params.marginEnd = if (statusFirst) statusGap.toInt() else 0
            status!!.layoutParams = params
            postInvalidateOnAnimation()
            return false
        }
        // Anchor the digits, rather than the variable width of digits + icons, to the playhead.
        val desired = playhead - time!!.left - halfTime
        readout.translationX = Math.max(left, Math.min(desired, right - readout.width)) - readout.left
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downTime = event.downTime; dragging = false
                parent.requestDisallowInterceptTouchEvent(true)
                isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (Math.abs(event.x - downX) > slop || Math.abs(event.y - downY) > slop)) {
                    dragging = true
                    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, downX, downY, event.metaState)
                    super.onTouchEvent(down); down.recycle()
                }
                if (dragging) super.onTouchEvent(event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                parent.requestDisallowInterceptTouchEvent(false)
                if (dragging) { dragging = false; return super.onTouchEvent(event) }
                return performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                parent.requestDisallowInterceptTouchEvent(false)
                if (dragging) { dragging = false; return super.onTouchEvent(event) }
                return true
            }
            else -> return super.onTouchEvent(event)
        }
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        private fun visibility(view: View, visible: Boolean): Boolean {
            val value = if (visible) View.VISIBLE else View.GONE
            if (view.visibility == value) return false
            view.visibility = value
            return true
        }
    }
}
