package tv.daniil.stream.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import tv.daniil.stream.R;

/** Stream's timeline: a tap toggles playback, a drag retains the shared player's seek-on-release behavior. */
public final class PlayerTimeline extends SeekBar {
    private final int slop;
    private float downX, downY;
    private long downTime;
    private boolean dragging;
    private boolean paused;
    private LinearLayout readout;
    private View time, status, pause, seek, end;
    private final ViewTreeObserver.OnPreDrawListener placeTime = this::positionTime;

    public PlayerTimeline(Context context, AttributeSet attributes) {
        super(context, attributes);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setPointerIcon(PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HAND));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(placeTime);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(placeTime);
        super.onDetachedFromWindow();
    }

    /** The readout owns pause/seek priority so a transient skip never crowds a second status icon. */
    public void setPaused(boolean paused) {
        if (this.paused == paused) return;
        this.paused = paused;
        invalidate();
    }

    private boolean positionTime() {
        if (readout == null) {
            View root = getRootView();
            readout = root.findViewById(R.id.timeline_info);
            time = root.findViewById(R.id.elapsed);
            status = root.findViewById(R.id.timeline_status);
            pause = root.findViewById(R.id.timeline_paused);
            seek = root.findViewById(R.id.seek_bubble);
            end = root.findViewById(R.id.remaining);
        }
        if (readout == null || !isShown()) return true;
        boolean seeking = seek.getVisibility() == VISIBLE;
        // Re-measure before drawing when status changes; don't expose one frame of stale bounds.
        boolean changed = visibility(pause, paused && !seeking);
        changed |= visibility(status, paused || seeking);
        if (changed) { postInvalidateOnAnimation(); return false; }
        if (readout.getWidth() == 0 || time.getWidth() == 0) return true;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float fraction = getMax() == 0 ? 0 : getProgress() / (float) getMax();
        if (rtl) fraction = 1 - fraction;
        float playhead = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight()) * fraction;
        float gap = Tv.dp(getContext(), 12), statusGap = Tv.dp(getContext(), 6);
        float left = rtl ? end.getRight() + gap : 0;
        float right = rtl ? getWidth() : end.getLeft() - gap;
        float halfTime = time.getWidth() / 2f;
        boolean statusFirst = status.getVisibility() == VISIBLE && (rtl
                ? playhead - halfTime - status.getWidth() - statusGap < left
                : playhead + halfTime + status.getWidth() + statusGap > right);
        if ((readout.getChildAt(0) == status) != statusFirst) {
            readout.removeView(status);
            readout.addView(status, statusFirst ? 0 : 1);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) status.getLayoutParams();
            params.setMarginStart(statusFirst ? 0 : (int) statusGap);
            params.setMarginEnd(statusFirst ? (int) statusGap : 0);
            status.setLayoutParams(params);
            postInvalidateOnAnimation();
            return false;
        }
        // Anchor the digits, rather than the variable width of digits + icons, to the playhead.
        float desired = playhead - time.getLeft() - halfTime;
        readout.setTranslationX(Math.max(left, Math.min(desired, right - readout.getWidth())) - readout.getLeft());
        return true;
    }

    private static boolean visibility(View view, boolean visible) {
        int value = visible ? VISIBLE : GONE;
        if (view.getVisibility() == value) return false;
        view.setVisibility(value);
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX(); downY = event.getY(); downTime = event.getDownTime(); dragging = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                setPressed(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop)) {
                    dragging = true;
                    MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, downX, downY, event.getMetaState());
                    super.onTouchEvent(down); down.recycle();
                }
                if (dragging) super.onTouchEvent(event);
                return true;
            case MotionEvent.ACTION_UP:
                setPressed(false);
                getParent().requestDisallowInterceptTouchEvent(false);
                if (dragging) { dragging = false; return super.onTouchEvent(event); }
                return performClick();
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                getParent().requestDisallowInterceptTouchEvent(false);
                if (dragging) { dragging = false; return super.onTouchEvent(event); }
                return true;
            default: return super.onTouchEvent(event);
        }
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
