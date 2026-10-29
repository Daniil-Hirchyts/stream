package tv.stream.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/** A compact player menu grows with its contents until it needs a scrolling viewport. */
class PopupScrollView(context: Context, attrs: AttributeSet?) : ScrollView(context, attrs) {
    override fun onMeasure(width: Int, height: Int) {
        val limit = Math.min(Tv.dp(context, 270f), resources.displayMetrics.heightPixels - Tv.dp(context, 210f))
        super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(Math.max(Tv.dp(context, 92f), limit), View.MeasureSpec.AT_MOST))
    }
}
