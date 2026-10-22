package tv.daniil.stream.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import eightbitlab.com.blurview.BlurTarget;
import eightbitlab.com.blurview.BlurView;
import tv.daniil.stream.R;

/** A rounded, reusable backdrop-blurred surface for translucent app chrome. */
public final class GlassView extends BlurView {
    private BlurTarget configuredTarget;

    public GlassView(Context context) {
        super(context);
        init();
    }

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        setClipToOutline(true);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        View target = getRootView().findViewById(R.id.blur_target);
        if (target instanceof BlurTarget && configuredTarget != target) {
            configuredTarget = (BlurTarget) target;
            setupWith(configuredTarget)
                    .setBlurRadius(20f)
                    .setOverlayColor(Color.TRANSPARENT);
        }
    }
}
