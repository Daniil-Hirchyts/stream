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

/** A compact menu attached to its opener. Morph snapshots contain only the control or popup, never the page behind it. */
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
    private Runnable motionCleanup;
    private Page displayed;
    private int attachment = -1;
    private boolean closing, closed, cancelAfterClose, leftPressed, refreshPending;
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
        boolean changing = displayed != null && displayed != page;
        boolean animate = ValueAnimator.areAnimatorsEnabled();
        View focused = returning ? page.focus : displayed == page ? getCurrentFocus() : null;
        Object focusedKey = focused == null ? null : focused.getTag();
        Rect start = opening && !original.isEmpty() ? source() : currentBounds();
        // Capture the actual composite before cancelling or rebuilding anything. An
        // interrupted transition must continue from the pixels currently on screen.
        Bitmap previous = changing && animate ? copyView(panel, 1024) : null;
        stopMotion();
        if (page.alert.prepare != null) page.alert.prepare.run();
        Rect end = measure(page);
        if (start.isEmpty()) start.set(end);
        animate = animate && (opening || changing || !start.equals(end));
        View incoming = page.content;
        if (incoming.getParent() != panel) {
            panel.removeAllViews(); detach(incoming);
            panel.addView(incoming, new FrameLayout.LayoutParams(end.width(), end.height()));
        } else {
            incoming.setLayoutParams(new FrameLayout.LayoutParams(end.width(), end.height()));
        }
        displayed = page;
        ((ViewGroup) incoming).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        incoming.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        ImageView ghost = ghost(opening ? openingImage : previous, start.width(), start.height());
        boolean reveal = changing || opening && !original.isEmpty();
        if (ghost != null) {
            // Cover the union of both surfaces, but keep the captured pixels at
            // their original size. Fade this one opaque layer over the new page:
            // fading both layers would darken the content twice at the midpoint.
            ghost.setBackgroundColor(getContext().getColor(R.color.tv_material_dialog));
            ghost.setLayoutParams(new FrameLayout.LayoutParams(Math.max(start.width(), end.width()), Math.max(start.height(), end.height())));
        }
        incoming.setAlpha(animate && reveal && ghost == null ? 0 : 1);
        incoming.setTranslationX(changing && animate ? dp(returning ? -8 : 8) : 0);
        panel.setAlpha(1); setBounds(animate ? start : end); panel.setVisibility(View.VISIBLE);
        float fromRadius = artwork ? dp(10) : Math.min(dp(22), start.height() / 2f);
        if (animate) {
            ValueAnimator animation = ValueAnimator.ofFloat(0, 1);
            animation.setDuration(opening ? 280 : 240); animation.setInterpolator(null);
            Rect from = start;
            animation.addUpdateListener(value -> {
                float progress = (float) value.getAnimatedValue();
                float f = EASE.getInterpolation(progress), blend = smooth(progress);
                setBounds(interpolate(from, end, f));
                shape.setCornerRadius(opening ? fromRadius + (dp(22) - fromRadius) * f : dp(22));
                // Geometry eases quickly into place; the single covering layer
                // dissolves over the full duration without a blank midpoint.
                incoming.setAlpha(reveal && ghost == null ? blend : 1);
                panel.setAlpha(opening && original.isEmpty() ? blend : 1);
                incoming.setTranslationX(changing ? dp((returning ? -8 : 8) * (1 - blend)) : 0);
                if (ghost != null) ghost.setAlpha(1 - blend);
            });
            startMotion(animation, () -> { removeGhost(ghost); recycleOpening(); });
        } else { removeGhost(ghost); shape.setCornerRadius(dp(22)); recycleOpening(); }
        View restored = focusedKey == null ? null : page.alert.surface.findViewWithTag(focusedKey);
        if (restored != null) restored.requestFocus();
        else if (page.alert.initialFocus != null) page.alert.initialFocus.requestFocus();
        if (incoming.findFocus() == null) {
            View first = firstFocusable(page.alert.buttons);
            if (first != null) first.requestFocus();
            else { page.content.setFocusableInTouchMode(true); page.content.requestFocus(); }
        }
        if (!animate && refreshPending) { refreshPending = false; root.post(this::refreshCurrent); }
    }
    void refreshCurrent() {
        if (!closed && !closing && !pages.isEmpty()) {
            if (running()) { refreshPending = true; return; }
            display(top(), false, false);
        }
    }
    private void resize(Page page, boolean animate) {
        if (closed || closing || pages.isEmpty() || top() != page || !isShowing() || !page.content.isAttachedToWindow()) return;
        Rect end = measure(page); if (end.equals(visual)) return;
        stopMotion(); page.content.setLayoutParams(new FrameLayout.LayoutParams(end.width(), end.height()));
        page.content.setAlpha(1); page.content.setTranslationX(0);
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) { setBounds(end); return; }
        Rect start = currentBounds(); ValueAnimator animation = ValueAnimator.ofFloat(0, 1); animation.setDuration(180); animation.setInterpolator(EASE);
        animation.addUpdateListener(value -> setBounds(interpolate(start, end, (float) value.getAnimatedValue())));
        startMotion(animation, null);
    }
    private Bitmap copyControl() {
        return copyView(anchor, 512);
    }
    private Bitmap copyView(View view, int maxEdge) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        float scale = Math.min(1f, (float) maxEdge / Math.max(view.getWidth(), view.getHeight()));
        Bitmap image = Bitmap.createBitmap(Math.max(1, Math.round(view.getWidth() * scale)), Math.max(1, Math.round(view.getHeight() * scale)), Bitmap.Config.ARGB_8888);
        try { Canvas canvas = new Canvas(image); canvas.scale(scale, scale); view.draw(canvas); return image; }
        catch (RuntimeException unsupportedSurface) { image.recycle(); return null; }
    }
    private ImageView ghost(Bitmap image, int width, int height) {
        if (image == null) return null;
        ImageView ghost = new ImageView(getContext()); ghost.setTag("popup-origin"); ghost.setImageBitmap(image);
        ghost.setScaleType(ImageView.ScaleType.MATRIX);
        android.graphics.Matrix matrix = new android.graphics.Matrix(); matrix.setScale((float) width / image.getWidth(), (float) height / image.getHeight());
        ghost.setImageMatrix(matrix); ghost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Keep captured text/icon pixels at their original size while only the
        // enclosing surface changes shape. MATCH_PARENT would stretch them each frame.
        panel.addView(ghost, new FrameLayout.LayoutParams(width, height)); return ghost;
    }
    private void removeGhost(ImageView ghost) { if (ghost != null) { ghost.setImageDrawable(null); panel.removeView(ghost); } }
    private void recycleOpening() { openingImage = null; }
    private Page top() { return pages.get(pages.size() - 1); }
    private boolean running() { return motion != null && motion.isRunning(); }
    private void startMotion(ValueAnimator animation, Runnable cleanup) {
        motion = animation; motionCleanup = cleanup;
        animation.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animator) {
                if (motion != animation) return;
                motion = null; cleanupMotion();
                if (refreshPending) { refreshPending = false; root.post(MorphDialog.this::refreshCurrent); }
            }
        }); animation.start();
    }
    private void cleanupMotion() { Runnable cleanup = motionCleanup; motionCleanup = null; if (cleanup != null) cleanup.run(); }
    private void stopMotion() {
        if (motion != null) {
            ValueAnimator previous = motion; motion = null;
            previous.removeAllUpdateListeners(); previous.removeAllListeners(); previous.cancel();
        }
        cleanupMotion();
    }
    private int dp(float value) { return Tv.dp(getContext(), value); }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
    private static float smooth(float value) { float t = clamp(value); return t * t * (3 - 2 * t); }
    private static Rect rect(int x, int y, int width, int height) { return new Rect(x, y, x + width, y + height); }
    private Rect currentBounds() {
        return panel.isLaidOut() && panel.getWidth() > 1 && panel.getHeight() > 1
                ? new Rect(panel.getLeft(), panel.getTop(), panel.getRight(), panel.getBottom()) : new Rect(visual);
    }
    private void setBounds(Rect rect) {
        if (visual.equals(rect)) return;
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
        Rect start = currentBounds();
        boolean animate = !finishing && isShowing() && panel.isAttachedToWindow() && ValueAnimator.areAnimatorsEnabled();
        Bitmap visibleContent = animate ? copyView(panel, 1024) : null; float initialAlpha = panel.getAlpha();
        closing = true; refreshPending = false; stopMotion();
        if (beforeClose != null) beforeClose.run();
        if (!animate) { closeNow(); return; }
        Rect end = new Rect();
        if (anchor != null && anchor.isAttachedToWindow() && anchor.getGlobalVisibleRect(end)) {
            int[] position = new int[2]; root.getLocationOnScreen(position); end.offset(-position[0], -position[1]);
        } else end.set(start);
        Bitmap image = original.isEmpty() || anchor == null || !anchor.isAttachedToWindow() ? null : copyControl();
        panel.removeAllViews();
        ImageView ghost = ghost(image, end.width(), end.height());
        ImageView outgoing = ghost(visibleContent, start.width(), start.height());
        Rect destination = end;
        ValueAnimator animation = ValueAnimator.ofFloat(0, 1); animation.setDuration(200); animation.setInterpolator(null);
        animation.addUpdateListener(value -> {
            float progress = (float) value.getAnimatedValue(), blend = smooth(progress);
            setBounds(interpolate(start, destination, EASE.getInterpolation(progress)));
            if (outgoing != null) outgoing.setAlpha(1 - blend);
            panel.setAlpha(initialAlpha * (original.isEmpty() ? 1 - blend : 1 - smooth((progress - .65f) / .35f)));
        });
        startMotion(animation, () -> { removeGhost(outgoing); removeGhost(ghost); closeNow(); });
    }
    private void closeNow() {
        if (closed) return; closed = true;
        refreshPending = false; stopMotion();
        recycleOpening();
        if (cancelAfterClose) { cancelAfterClose = false; super.cancel(); }
        super.dismiss(); pages.clear();
        if (opener != null && opener.isAttachedToWindow() && owner != null && !owner.isFinishing()) opener.requestFocus();
    }
}
