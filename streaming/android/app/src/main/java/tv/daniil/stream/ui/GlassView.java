package tv.daniil.stream.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import eightbitlab.com.blurview.BlurTarget;
import eightbitlab.com.blurview.BlurView;
import tv.daniil.stream.R;

/**
 * A rounded backdrop-blurred surface for the few pieces of app chrome that float over content (the tab bar and search
 * field). Live blur uses RenderEffect (Android 12+), which costs one GPU pass per frame. Earlier versions would blur
 * on the CPU before every frame, which stalls a TV stick, so there the surface uses a denser static material.
 * Never use this for list rows, buttons or cards: one instance per floating surface.
 */
public final class GlassView extends BlurView {
    public static final boolean LIVE_BLUR = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
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
        if (!LIVE_BLUR) setBackgroundResource(R.drawable.tv_tabs_solid);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!LIVE_BLUR) return;
        View target = getRootView().findViewById(R.id.blur_target);
        if (target instanceof BlurTarget && configuredTarget != target) {
            configuredTarget = (BlurTarget) target;
            // Downscaled 4x before blurring: soft at TV distance and a sixteenth of the pixels.
            setupWith(configuredTarget, 4f, false).setBlurRadius(18f).setOverlayColor(Color.TRANSPARENT);
        }
    }
}
