package tv.stream.app.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import tv.stream.app.R;

/** Circular resting rail morphing into a contextual navigation popup above an unchanged page. */
public final class NavigationRail extends FrameLayout {
    public enum State { HIDDEN, WRAPPED, EXPANDED }
    public static final int COLLAPSED = 56, EXPANDED = 144, CONTENT_START = 104, HIDDEN_CONTENT_START = 16;
    private static final int LABEL_START = 44, LABEL_END = 16, SURFACE_INSET = 6;
    private int expandedWidth = EXPANDED;
    private final List<TextView> labels = new ArrayList<>();
    private final List<View> rows = new ArrayList<>();
    private float expansion, reveal = 1;
    private State state = State.WRAPPED;
    private ValueAnimator morph;
    private final GradientDrawable surface = new GradientDrawable();
    private View platter;
    private static final PathInterpolator EASE = new PathInterpolator(.2f, .7f, .15f, 1f);

    public NavigationRail(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        platter = findViewById(R.id.top_bar_controls);
        platter.setBackground(surface);
        apply(expansion, reveal);
    }
    public State state() { return state; }
    /** Wrapped icons grow slightly; expanded rows stay inside the popup's even inset. */
    public double lift() { return state == State.EXPANDED ? 1f : 1.08f; }
    public int expandedWidth() { return expandedWidth; }
    /** The 44 dp row exactly matches its width when wrapped; text never participates in icon geometry. */
    public void decorate(FrameLayout row, int icon, String label) {
        ImageView glyph = new ImageView(getContext()); glyph.setImageResource(icon);
        glyph.setImageTintList(getContext().getColorStateList(R.color.tv_tab_text));
        glyph.setDuplicateParentStateEnabled(true); glyph.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams image = new FrameLayout.LayoutParams(Tv.dp(getContext(), 20), Tv.dp(getContext(), 20), Gravity.START | Gravity.CENTER_VERTICAL);
        image.leftMargin = Tv.dp(getContext(), 12); row.addView(glyph, image); rows.add(row);
        TextView text = Tv.oneLine(Tv.heading(getContext(), label, 15, R.color.tv_tab_text));
        text.setDuplicateParentStateEnabled(true); text.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Keep a stable text width during the morph; the row reveals it without reflowing or scaling letters.
        FrameLayout.LayoutParams caption = new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
        caption.leftMargin = Tv.dp(getContext(), LABEL_START); row.addView(text, caption); labels.add(text);
        measureLabels();
        row.setClipChildren(true); row.setContentDescription(label); apply(expansion, reveal);
    }

    public void state(State next) {
        int previousWidth = expandedWidth;
        if (next == State.EXPANDED) measureLabels();
        if (state == next) { if (expandedWidth != previousWidth) apply(expansion, reveal); return; }
        state = next;
        for (View row : rows) if (row.isFocused() || row.isHovered()) Tv.animate(row, true, (float) lift(), false);
        if (morph != null) morph.cancel();
        if (next != State.HIDDEN) setVisibility(VISIBLE);
        // A hiding rail is still drawn while it slides out; a quick Down must find the page, not the rail.
        setDescendantFocusability(next == State.HIDDEN ? FOCUS_BLOCK_DESCENDANTS : FOCUS_AFTER_DESCENDANTS);
        float fromWidth = expansion, fromReveal = reveal;
        float toWidth = next == State.EXPANDED ? 1 : 0, toReveal = next == State.HIDDEN ? 0 : 1;
        morph = ValueAnimator.ofFloat(0, 1);
        morph.setDuration(240); morph.setInterpolator(EASE);
        morph.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            apply(fromWidth + (toWidth - fromWidth) * t, fromReveal + (toReveal - fromReveal) * t);
        });
        morph.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (!cancelled && state == State.HIDDEN) setVisibility(INVISIBLE);
            }
        });
        morph.start();
    }

    private void apply(float width, float visible) {
        expansion = width; reveal = visible;
        int extra = Tv.dp(getContext(), (expandedWidth - COLLAPSED) * width);
        ViewGroup.LayoutParams params = getLayoutParams();
        if (params != null && params.width != Tv.dp(getContext(), COLLAPSED) + extra) {
            params.width = Tv.dp(getContext(), COLLAPSED) + extra; setLayoutParams(params);
        }
        if (platter != null) {
            int resting = Color.TRANSPARENT;
            int popup = getContext().getColor(R.color.tv_material_dialog);
            surface.setColor(blend(resting, popup, width));
            surface.setCornerRadius(Tv.dp(getContext(), 28));
            platter.setElevation(Tv.dp(getContext(), 12 * width));
            platter.invalidateOutline();
        }
        setAlpha(visible); setTranslationX(-Tv.dp(getContext(), 96) * (1 - visible));
        setImportantForAccessibility(visible == 0 ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        float alpha = Math.max(0, (width - .25f) / .75f);
        for (TextView text : labels) { text.setAlpha(alpha); text.setVisibility(alpha == 0 ? INVISIBLE : VISIBLE); }
    }

    /** Fit the longest actual label, preserving a 12 dp icon gap and 16 dp trailing air. Grow for larger text. */
    private void measureLabels() {
        float longest = 0;
        float density = getResources().getDisplayMetrics().density;
        for (TextView label : labels) longest = Math.max(longest, label.getPaint().measureText(label.getText().toString()) / density);
        int required = (int) Math.ceil((SURFACE_INSET * 2 + LABEL_START + longest + LABEL_END) / 8f) * 8;
        expandedWidth = Math.max(EXPANDED, required);
        for (TextView label : labels) {
            ViewGroup.LayoutParams params = label.getLayoutParams();
            int width = Tv.dp(getContext(), expandedWidth - SURFACE_INSET * 2 - LABEL_START - LABEL_END);
            if (params.width != width) { params.width = width; label.setLayoutParams(params); }
        }
    }

    private static int blend(int from, int to, float amount) {
        return Color.argb(Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * amount),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }

    @Override protected void onDetachedFromWindow() {
        if (morph != null) morph.cancel();
        super.onDetachedFromWindow();
    }
}
