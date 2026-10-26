package tv.daniil.stream.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;
import tv.daniil.stream.R;

/** A compact menu attached to its real opener. Only that control is copied for the morph; never the page behind it. */
final class MorphDialog extends Dialog {
    private static final int BELOW = 0, ABOVE = 1, RIGHT = 2, LEFT = 3, OVER = 4;
    private static final PathInterpolator EASE = new PathInterpolator(.2f, .7f, .15f, 1f);
    private final class Page {
        final Tv.Alert alert;
        final ScrollView content;
        View focus;
        Page(Tv.Alert alert) {
            this.alert = alert;
            content = new ScrollView(getContext()); content.setVerticalScrollBarEnabled(false); content.setFocusable(false);
            content.addView(alert.surface, new FrameLayout.LayoutParams(-1, -2));
            alert.surface.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
                if (oldB > oldT && b - t != oldB - oldT && !running()) root.post(() -> resize(this, true));
            });
        }
    }
    private final List<Page> pages = new ArrayList<>();
    private final FrameLayout root, panel;
    private final GradientDrawable shape = new GradientDrawable();
    private final View opener, anchor;
    private final Activity owner;
    private final boolean artwork;
    private final Rect original = new Rect(), visual = new Rect();
    private Bitmap openingImage;
    private ValueAnimator motion;
    private int attachment = -1;
    private boolean closing, closed, cancelAfterClose, leftPressed;
    private Object backCallback;
    Runnable beforeClose;

    MorphDialog(Context context) { this(context, true); }
    MorphDialog(Context context, boolean contextual) {
        super(context); requestWindowFeature(Window.FEATURE_NO_TITLE);
        Context base = context;
        while (base instanceof ContextWrapper && !(base instanceof Activity)) base = ((ContextWrapper) base).getBaseContext();
        owner = base instanceof Activity ? (Activity) base : null;
        View activated = Tv.activatingControl();
        opener = activated != null ? activated : owner == null || !contextual ? null : owner.getCurrentFocus();
        View surface = opener;
        Object tagged = surface == null ? null : surface.getTag(R.id.popup_anchor);
        artwork = tagged instanceof View;
        if (artwork) surface = (View) tagged;
        else if (surface instanceof ViewGroup) {
            View selected = selectedControl((ViewGroup) surface); if (selected != null) surface = selected;
        }
        anchor = surface;
        if (anchor != null && anchor.isAttachedToWindow() && anchor.getGlobalVisibleRect(original)
                && (original.width() < context.getResources().getDisplayMetrics().widthPixels * .8f
                || original.height() < context.getResources().getDisplayMetrics().heightPixels * .4f)) openingImage = copyControl();
        else original.setEmpty();

        root = new FrameLayout(context); root.setTag("popup-root"); root.setClipChildren(false);
        root.setOnClickListener(v -> cancel()); root.setFocusable(false);
        panel = new FrameLayout(context); panel.setTag("popup-panel");
        shape.setColor(context.getColor(R.color.tv_material_dialog)); shape.setCornerRadius(dp(22));
        panel.setBackground(shape); panel.setClipToOutline(true); panel.setElevation(dp(12));
        panel.setClickable(true); panel.setFocusable(false); panel.setVisibility(View.INVISIBLE);
        root.addView(panel, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.LEFT));
        root.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (oldR > oldL && oldB > oldT && (r - l != oldR - oldL || b - t != oldB - oldT) && !pages.isEmpty()) resize(top(), false);
        });
        setContentView(root);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); window.setWindowAnimations(0);
        }
        setOnKeyListener((dialog, code, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && Sounds.arrow(code)) Sounds.input();
            if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_CAPTIONS) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) cancel();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) { leftPressed = true; back(); }
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    // Android can consume the first arrow-down while leaving pointer mode. Its up must still work.
                    if (!leftPressed && !event.isCanceled()) back();
                    leftPressed = false;
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && getCurrentFocus() != null
                    && "submenu".equals(getCurrentFocus().getTag(R.id.popup_navigation))) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) getCurrentFocus().performClick();
                return true;
            }
            return false;
        });
    }

    void present(Tv.Alert alert) {
        if (closing || closed) return;
        if (!pages.isEmpty()) top().focus = getCurrentFocus();
        Page page = new Page(alert); pages.add(page);
        if (!isShowing()) {
            super.show();
            if (getWindow() != null) getWindow().setLayout(-1, -1);
            Sounds.play(Sounds.Sound.ALERT); root.post(() -> display(page, true, false));
        } else display(page, false, false);
    }
    /** Both remote exit keys pop one level, or cancel at the root. */
    void back() {
        if (closing || closed) return;
        Sounds.play(Sounds.Sound.BACK);
        if (pages.size() <= 1) { cancel(); return; }
        pages.remove(pages.size() - 1); Page parent = top();
        display(parent, false, true);
        if (parent.focus != null) {
            Object tag = parent.focus.getTag(); View replacement = tag == null ? null : parent.alert.surface.findViewWithTag(tag);
            if (replacement != null) replacement.requestFocus();
        }
    }
    @Override public void onBackPressed() { back(); }
    @Override protected void onStart() {
        super.onStart(); if (Build.VERSION.SDK_INT >= 33) backCallback = BackApi.register(this);
    }
    @Override protected void onStop() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) BackApi.unregister(this, backCallback);
        super.onStop();
    }
    private static final class BackApi {
        static Object register(MorphDialog dialog) {
            android.window.OnBackInvokedCallback callback = dialog::back;
            dialog.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }
        static void unregister(MorphDialog dialog, Object callback) {
            dialog.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) callback);
        }
    }

    private Rect measure(Page page) {
        int limit = Math.min(dp(520), Math.max(dp(160), root.getWidth() - dp(48)));
        int width = Math.min(limit, Math.max(dp(200), preferredWidth(page.alert.surface, limit)));
        int available = Math.max(dp(92), root.getHeight() - dp(96));
        page.content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST));
        return target(width, Math.min(available, page.content.getMeasuredHeight()));
    }
    /** Weighted rows and MATCH_PARENT fields must not turn an intrinsic measurement into the screen width. */
    private int preferredWidth(View view, int limit) {
        if (view.getVisibility() == View.GONE) return 0;
        int content = 0;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            CharSequence value = text.length() == 0 && text instanceof EditText ? text.getHint() : text.getText();
            content = value == null ? 0 : (int) Math.ceil(android.text.Layout.getDesiredWidth(value, text.getPaint()));
            content += text.getCompoundPaddingLeft() + text.getCompoundPaddingRight();
            if (text instanceof EditText) content = Math.max(content, dp(300));
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            boolean horizontal = group instanceof LinearLayout && ((LinearLayout) group).getOrientation() == LinearLayout.HORIZONTAL;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i); if (child.getVisibility() == View.GONE) continue;
                int width = preferredWidth(child, limit);
                if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
                    width += margins.leftMargin + margins.rightMargin;
                }
                content = horizontal ? content + width : Math.max(content, width);
            }
            content += view.getPaddingLeft() + view.getPaddingRight();
        } else content = view.getMinimumWidth();
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && params.width > 0) content = Math.max(content, params.width);
        return Math.min(limit, Math.max(view.getMinimumWidth(), content));
    }
    private Rect source() {
        if (original.isEmpty()) return new Rect();
        Rect rect = new Rect(original); int[] position = new int[2]; root.getLocationOnScreen(position);
        rect.offset(-position[0], -position[1]); return rect;
    }
    /** Keep the same attachment edge through nested pages; changing height must never flip the menu across its opener. */
    private Rect target(int width, int height) {
        int inset = dp(24), topInset = dp(72), gap = dp(10); Rect source = source();
        if (source.isEmpty()) return rect((root.getWidth() - width) / 2, topInset + (root.getHeight() - topInset - inset - height) / 2, width, height);
        if (attachment < 0) {
            if (artwork && root.getWidth() - inset - source.right >= width + gap) attachment = RIGHT;
            else if (artwork && source.left - inset >= width + gap) attachment = LEFT;
            else if (root.getHeight() - inset - source.bottom >= height + gap) attachment = BELOW;
            else if (source.top - topInset >= height + gap) attachment = ABOVE;
            else if (root.getWidth() - inset - source.right >= width + gap) attachment = RIGHT;
            else if (source.left - inset >= width + gap) attachment = LEFT;
            else attachment = OVER;
        }
        int x = source.left, y = source.top;
        if (attachment == BELOW) y = source.bottom + gap;
        else if (attachment == ABOVE) y = source.top - height - gap;
        else if (attachment == RIGHT) x = source.right + gap;
        else if (attachment == LEFT) x = source.left - width - gap;
        else { x = source.right - width; y = source.centerY() - height / 2; }
        x = Math.max(inset, Math.min(root.getWidth() - inset - width, x));
        y = Math.max(topInset, Math.min(root.getHeight() - inset - height, y));
        return rect(x, y, width, height);
    }

    private void display(Page page, boolean opening, boolean returning) {
        if (!isShowing() || closing || closed) return;
        stopMotion();
        Object focusedKey = getCurrentFocus() == null ? null : getCurrentFocus().getTag();
        if (page.alert.prepare != null) page.alert.prepare.run();
        Rect end = measure(page), start = opening && !original.isEmpty() ? source() : new Rect(visual);
        if (start.isEmpty()) start.set(end);
        View previous = panel.getChildCount() > 0 ? panel.getChildAt(panel.getChildCount() - 1) : null;
        panel.removeAllViews();
        View incoming = page.content, outgoing = previous == incoming ? null : previous;
        if (outgoing != null) {
            int previousWidth = outgoing.getWidth();
            detach(outgoing); panel.addView(outgoing, new FrameLayout.LayoutParams(previousWidth, outgoing.getMeasuredHeight()));
            outgoing.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            ((ViewGroup) outgoing).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        detach(incoming); panel.addView(incoming, new FrameLayout.LayoutParams(end.width(), end.height()));
        ((ViewGroup) incoming).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        incoming.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        ImageView ghost = opening ? ghost(openingImage) : null;
        boolean animate = ValueAnimator.areAnimatorsEnabled();
        incoming.setAlpha(animate ? 0 : 1); incoming.setTranslationX(opening || !animate ? 0 : dp(returning ? -10 : 10));
        panel.setAlpha(1); setBounds(animate ? start : end); panel.setVisibility(View.VISIBLE);
        float fromRadius = artwork ? dp(10) : Math.min(dp(22), start.height() / 2f);
        if (animate) {
            motion = ValueAnimator.ofFloat(0, 1); motion.setDuration(opening ? 280 : 220); motion.setInterpolator(EASE);
            Rect from = start;
            motion.addUpdateListener(value -> {
                float f = (float) value.getAnimatedValue(); setBounds(interpolate(from, end, f));
                shape.setCornerRadius(opening ? fromRadius + (dp(22) - fromRadius) * f : dp(22));
                incoming.setAlpha(opening ? original.isEmpty() ? f : clamp((f - .6f) / .4f) : clamp((f - .2f) / .8f));
                panel.setAlpha(opening && original.isEmpty() ? f : 1);
                incoming.setTranslationX(opening ? 0 : dp((returning ? -10 : 10) * (1 - f)));
                if (ghost != null) ghost.setAlpha(clamp(1 - f * 3));
                if (outgoing != null) outgoing.setAlpha(clamp(1 - f * 2));
            });
            motion.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animator) {
                    if (outgoing != null) panel.removeView(outgoing);
                    if (ghost != null) { ghost.setImageDrawable(null); panel.removeView(ghost); }
                    recycleOpening();
                }
            }); motion.start();
        } else { if (outgoing != null) panel.removeView(outgoing); if (ghost != null) panel.removeView(ghost); recycleOpening(); }
        View restored = focusedKey == null ? null : page.alert.surface.findViewWithTag(focusedKey);
        if (restored != null) restored.requestFocus();
        else if (page.alert.initialFocus != null) page.alert.initialFocus.requestFocus();
        if (incoming.findFocus() == null) {
            View first = firstFocusable(page.alert.buttons);
            if (first != null) first.requestFocus();
            else { page.content.setFocusableInTouchMode(true); page.content.requestFocus(); }
        }
    }
    void refreshCurrent() {
        if (!closed && !closing && !pages.isEmpty()) {
            if (running()) { root.postDelayed(this::refreshCurrent, 100); return; }
            display(top(), false, false);
        }
    }
    private void resize(Page page, boolean animate) {
        if (closed || closing || pages.isEmpty() || top() != page || !isShowing() || !page.content.isAttachedToWindow()) return;
        Rect end = measure(page); if (end.equals(visual)) return;
        stopMotion(); page.content.setLayoutParams(new FrameLayout.LayoutParams(end.width(), end.height()));
        page.content.setAlpha(1); page.content.setTranslationX(0);
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) { setBounds(end); return; }
        Rect start = new Rect(visual); motion = ValueAnimator.ofFloat(0, 1); motion.setDuration(180); motion.setInterpolator(EASE);
        motion.addUpdateListener(value -> setBounds(interpolate(start, end, (float) value.getAnimatedValue()))); motion.start();
    }
    private Bitmap copyControl() {
        if (anchor == null || anchor.getWidth() <= 0 || anchor.getHeight() <= 0) return null;
        float scale = Math.min(1f, 512f / Math.max(anchor.getWidth(), anchor.getHeight()));
        Bitmap image = Bitmap.createBitmap(Math.max(1, Math.round(anchor.getWidth() * scale)), Math.max(1, Math.round(anchor.getHeight() * scale)), Bitmap.Config.ARGB_8888);
        try { Canvas canvas = new Canvas(image); canvas.scale(scale, scale); anchor.draw(canvas); return image; }
        catch (RuntimeException unsupportedSurface) { image.recycle(); return null; }
    }
    private ImageView ghost(Bitmap image) {
        if (image == null) return null;
        ImageView ghost = new ImageView(getContext()); ghost.setTag("popup-origin"); ghost.setImageBitmap(image);
        ghost.setScaleType(ImageView.ScaleType.FIT_CENTER); ghost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        panel.addView(ghost, new FrameLayout.LayoutParams(-1, -1)); return ghost;
    }
    private void recycleOpening() { openingImage = null; }
    private Page top() { return pages.get(pages.size() - 1); }
    private boolean running() { return motion != null && motion.isRunning(); }
    private void stopMotion() { if (motion != null) { motion.cancel(); motion = null; } }
    private int dp(float value) { return Tv.dp(getContext(), value); }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
    private static Rect rect(int x, int y, int width, int height) { return new Rect(x, y, x + width, y + height); }
    private void setBounds(Rect rect) {
        visual.set(rect); FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) panel.getLayoutParams();
        p.width = Math.max(1, rect.width()); p.height = Math.max(1, rect.height()); p.leftMargin = rect.left; p.topMargin = rect.top; panel.setLayoutParams(p);
    }
    private static Rect interpolate(Rect a, Rect b, float f) {
        return new Rect(Math.round(a.left + (b.left - a.left) * f), Math.round(a.top + (b.top - a.top) * f),
                Math.round(a.right + (b.right - a.right) * f), Math.round(a.bottom + (b.bottom - a.bottom) * f));
    }
    private static void detach(View view) { if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view); }
    private static View selectedControl(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.isSelected() && child.isClickable()) return child;
            if (child instanceof ViewGroup) { View selected = selectedControl((ViewGroup) child); if (selected != null) return selected; }
        }
        return null;
    }
    private static View firstFocusable(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view.isFocusable()) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View child = firstFocusable(((ViewGroup) view).getChildAt(i)); if (child != null) return child;
        }
        return null;
    }
    @Override public void cancel() { if (!closed) { cancelAfterClose = true; dismiss(); } }
    @Override public void dismiss() {
        if (closed) return;
        boolean finishing = owner != null && (owner.isFinishing() || owner.isDestroyed());
        if (closing) { if (finishing) closeNow(); return; }
        closing = true; stopMotion();
        if (beforeClose != null) beforeClose.run();
        if (finishing || !isShowing() || !panel.isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) { closeNow(); return; }
        Rect start = new Rect(visual), end = new Rect();
        if (anchor != null && anchor.isAttachedToWindow() && anchor.getGlobalVisibleRect(end)) {
            int[] position = new int[2]; root.getLocationOnScreen(position); end.offset(-position[0], -position[1]);
        } else end.set(start);
        Bitmap image = original.isEmpty() || anchor == null || !anchor.isAttachedToWindow() ? null : copyControl();
        ImageView ghost = ghost(image); if (ghost != null) ghost.setAlpha(0);
        Rect destination = end;
        motion = ValueAnimator.ofFloat(0, 1); motion.setDuration(180); motion.setInterpolator(EASE);
        motion.addUpdateListener(value -> {
            float f = (float) value.getAnimatedValue(); setBounds(interpolate(start, destination, f));
            for (int i = 0; i < panel.getChildCount(); i++) if (panel.getChildAt(i) != ghost) panel.getChildAt(i).setAlpha(clamp(1 - f * 3));
            if (ghost != null) ghost.setAlpha(clamp((f - .5f) * 2));
            panel.setAlpha(original.isEmpty() ? 1 - f : clamp((1 - f) * 3));
        });
        motion.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (ghost != null) { ghost.setImageDrawable(null); panel.removeView(ghost); }
                closeNow();
            }
        }); motion.start();
    }
    private void closeNow() {
        if (closed) return; closed = true;
        if (motion != null) { motion.removeAllListeners(); motion.cancel(); motion = null; }
        recycleOpening();
        if (cancelAfterClose) { cancelAfterClose = false; super.cancel(); }
        super.dismiss(); pages.clear();
        if (opener != null && opener.isAttachedToWindow() && owner != null && !owner.isFinishing()) opener.requestFocus();
    }
}
