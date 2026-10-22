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
 * A rounded backdrop-blurred surface: the tab bar and search field, and the buttons, chips and notices on a page.
 * Live blur uses RenderEffect (Android 12+): one small GPU pass for each surface while what is behind it changes, and
 * none while the screen is still. Earlier versions would blur on the CPU before every frame, which stalls a TV stick,
 * so there the chrome uses a denser static material and buttons keep their plain platter. Not for list rows, tiles
 * or keyboard keys: they are many, and rows and keys sit on the plain canvas.
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

    /** A plain container where live blur isn't available; the caller sets its platter. */
    static android.widget.FrameLayout surface(Context context) {
        return LIVE_BLUR ? new GlassView(context) : new android.widget.FrameLayout(context);
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
