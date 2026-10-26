package tv.daniil.stream.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/** A compact player menu grows with its contents until it needs a scrolling viewport. */
public final class PopupScrollView extends ScrollView {
    public PopupScrollView(Context context, AttributeSet attrs) { super(context, attrs); }
    @Override protected void onMeasure(int width, int height) {
        int limit = Math.min(Tv.dp(getContext(), 270), getResources().getDisplayMetrics().heightPixels - Tv.dp(getContext(), 210));
        super.onMeasure(width, MeasureSpec.makeMeasureSpec(Math.max(Tv.dp(getContext(), 92), limit), MeasureSpec.AT_MOST));
    }
}
