package tv.daniil.stream.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;
import java.util.function.Consumer;
import tv.daniil.stream.R;

/**
 * Shared view building after tvOS: type, focus motion, platters and alerts, and the click/touch/remote wiring every
 * control uses. Sizes are in dp on the 960×540 dp TV canvas, half of tvOS's 1920×1080 points.
 */
public final class Tv {
    private Tv() {}
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2.2f);
    /**
     * Focus growth. Artwork grows most; buttons a little. List rows and tabs don't grow at all: they are wide, so any
     * scale visibly shifts their text sideways. They show focus by turning white instead.
     */
    public static final float LIFT_TILE = 1.1f, LIFT_BUTTON = 1.05f, LIFT_ROW = 1f;

    public static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    /**
     * The app's type: Inter, the open typeface closest to Apple's SF, at three weights for text (regular, medium for
     * headings and controls, semibold for emphasis) and its Display cut for large titles.
     */
    public enum Font {
        REGULAR(R.font.inter_regular), MEDIUM(R.font.inter_medium), SEMIBOLD(R.font.inter_semibold), DISPLAY(R.font.inter_display_semibold);
        final int resource;
        private Typeface typeface;
        Font(int resource) { this.resource = resource; }
        public Typeface get(Context context) {
            if (typeface == null) typeface = context.getResources().getFont(resource);
            return typeface;
        }
    }
    /** Titles from this size up use the Display cut, drawn for large sizes. */
    private static final float DISPLAY_SP = 24;

    public static TextView text(Context context, String value, float sp, @ColorRes int color) {
        TextView view = new TextView(context);
        view.setText(value); view.setTextSize(sp); view.setTextColor(context.getColorStateList(color));
        view.setTypeface(Font.REGULAR.get(context));
        view.setIncludeFontPadding(false);
        return view;
    }
    public static TextView heading(Context context, String value, float sp, @ColorRes int color) {
        TextView view = text(context, value, sp, color);
        view.setTypeface(Font.MEDIUM.get(context));
        return view;
    }
    public static TextView bold(Context context, String value, float sp) {
        TextView view = text(context, value, sp, R.color.tv_text);
        boolean display = sp >= DISPLAY_SP;
        view.setTypeface((display ? Font.DISPLAY : Font.SEMIBOLD).get(context));
        view.setLetterSpacing(display ? -0.005f : -0.01f);
        return view;
    }
    /**
     * Text that can sit over artwork gets a soft dark shadow, so it stays readable on light frames the scrim doesn't
     * fully darken. On the plain canvas the shadow is invisible.
     */
    public static TextView onArtwork(TextView view) {
        Context context = view.getContext();
        view.setShadowLayer(dp(context, 5), 0, dp(context, 1), 0xB3000000);
        return view;
    }
    public static TextView oneLine(TextView view) { view.setSingleLine(true); view.setEllipsize(TextUtils.TruncateAt.END); return view; }
    public static TextView lines(TextView view, int lines) {
        view.setMaxLines(lines); view.setEllipsize(TextUtils.TruncateAt.END); view.setLineSpacing(0, 1.18f); return view;
    }

    /**
     * Makes {@code view} a control: focusable by remote and pointer, clicked by OK, mouse or touch, and reporting
     * focus by key. Touch requests focus on press so the first tap both focuses and activates.
     */
    public static void control(View view, String key, Runnable action, Consumer<String> focused, float lift) {
        control(view, key, action, focused, lift, true);
    }

    public static void control(View view, String key, Runnable action, Consumer<String> focused, float lift, boolean raise) {
        view.setTag(key);
        view.setFocusable(true); view.setFocusableInTouchMode(true); view.setClickable(true);
        view.setDefaultFocusHighlightEnabled(false);
        // The app's own sounds (Sounds) replace the platform's click.
        view.setSoundEffectsEnabled(false);
        view.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { Sounds.input(); if (!v.isFocused()) v.requestFocus(); }
            return false;
        });
        view.setOnClickListener(v -> { focused.accept(key); Sounds.click(); action.run(); });
        view.setOnFocusChangeListener((v, has) -> { if (has) { focused.accept(key); Sounds.focus(v); } animate(v, has, lift, raise); });
        animate(view, view.isFocused(), lift, raise);
    }

    /** tvOS focus: the focused control grows and floats above its neighbours. */
    public static void animate(View view, boolean focused, float lift) { animate(view, focused, lift, true); }

    /** Some controls have enough layout clearance to grow without an elevation shadow. */
    public static void animate(View view, boolean focused, float lift, boolean raise) {
        float scale = focused ? lift : 1f;
        view.animate().cancel();
        view.animate().scaleX(scale).scaleY(scale).translationZ(focused && raise ? dp(view.getContext(), 10) : 0)
                .setDuration(focused ? 200 : 160).setInterpolator(EASE).start();
    }

    /** Clears motion left on a recycled view. */
    public static void settle(View view) { view.animate().cancel(); view.setScaleX(1f); view.setScaleY(1f); view.setTranslationZ(0); }

    public static ImageView icon(Context context, int drawable, int sizeDp, @ColorRes int tint) {
        ImageView icon = new ImageView(context);
        icon.setImageResource(drawable); icon.setImageTintList(context.getColorStateList(tint));
        icon.setDuplicateParentStateEnabled(true); icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp)));
        return icon;
    }

    /** A platter button in one of the {@link Item.Action} styles. Its content turns dark on the white focus platter. */
    public static View button(Context context, Item.Action action, Consumer<String> focused) {
        // Glass: on Android 12+ the platter blurs the artwork behind it, as the tab bar does.
        FrameLayout button = GlassView.surface(context);
        button.setContentDescription(action.label);
        LinearLayout content = new LinearLayout(context);
        content.setGravity(Gravity.CENTER);
        content.setDuplicateParentStateEnabled(true);
        int height = dp(context, action.style == Item.Action.Style.CHIP ? 36 : 44);
        switch (action.style) {
            case ICON: {
                button.setBackgroundResource(R.drawable.tv_button_round);
                content.addView(icon(context, action.icon, 22, R.color.tv_on_glass));
                button.setLayoutParams(new LinearLayout.LayoutParams(height, height));
                button.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                break;
            }
            default: {
                boolean chip = action.style == Item.Action.Style.CHIP;
                content.setOrientation(LinearLayout.HORIZONTAL);
                button.setBackgroundResource(chip ? R.drawable.tv_chip : R.drawable.tv_button);
                int pad = dp(context, chip ? 16 : action.style == Item.Action.Style.PRIMARY ? 26 : 20);
                content.setPadding(pad, 0, pad, 0);
                content.setMinimumWidth(dp(context, action.style == Item.Action.Style.PRIMARY ? 148 : chip ? 44 : 96));
                if (action.icon != 0) {
                    ImageView icon = icon(context, action.icon, chip ? 18 : 20, R.color.tv_on_glass);
                    ((LinearLayout.LayoutParams) icon.getLayoutParams()).rightMargin = dp(context, 8);
                    content.addView(icon);
                }
                TextView label = oneLine(heading(context, action.shown(), chip ? 14.5f : 16, chip ? R.color.tv_chip_text : R.color.tv_on_glass));
                label.setDuplicateParentStateEnabled(true);
                content.addView(label);
                // A short value after the label: a chip's current filter, or the episode a Play button starts.
                if (!action.detail.isEmpty()) {
                    TextView value = oneLine(text(context, action.detail, chip ? 14.5f : 16, chip ? R.color.tv_chip_text_secondary : R.color.tv_on_glass_secondary));
                    value.setDuplicateParentStateEnabled(true);
                    value.setPadding(dp(context, 6), 0, 0, 0);
                    content.addView(value);
                }
                button.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, height));
                button.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
                break;
            }
        }
        button.setActivated(action.selected);
        Runnable press = action.style == Item.Action.Style.CHIP ? () -> { Sounds.at(Sounds.Sound.CHIP, button); action.action.run(); } : action.action;
        control(button, action.key, press, focused, LIFT_BUTTON);
        return button;
    }

    // Alerts, after tvOS: a centred title and message over blurred content, and full-width stacked buttons.

    /** A tvOS alert's body: title, optional message and an empty button stack ({@link Alert#buttons}). */
    public static final class Alert {
        public final Dialog dialog;
        public final LinearLayout surface, buttons;
        Alert(Dialog dialog, LinearLayout surface, LinearLayout buttons) { this.dialog = dialog; this.surface = surface; this.buttons = buttons; }

        /** Adds a full-width button; the first one added takes focus when the alert opens. */
        public View button(String key, String label, Runnable action) {
            return button(key, label, action, ignored -> {});
        }

        public View button(String key, String label, Runnable action, Consumer<String> onFocused) {
            Context context = dialog.getContext();
            TextView button = heading(context, label, 16, R.color.tv_on_glass);
            button.setGravity(Gravity.CENTER); button.setContentDescription(label); button.setSingleLine(true);
            button.setBackgroundResource(R.drawable.tv_dialog_button);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 46));
            if (buttons.getChildCount() > 0) params.topMargin = dp(context, 8);
            buttons.addView(button, params);
            control(button, key, action, onFocused != null ? onFocused : ignored -> {}, 1.03f, false);
            return button;
        }
        /**
         * The way out (Cancel, Close): set apart from the choices by a gap and a rule, outlined rather than filled, its
         * label grey until focused. Add it last.
         */
        public View cancelButton(String key, String label, Runnable action) {
            Context context = dialog.getContext();
            View rule = new View(context); rule.setBackgroundColor(context.getColor(R.color.tv_hairline));
            rule.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1)));
            int inset = dp(context, 24); ruleParams.leftMargin = ruleParams.rightMargin = inset; ruleParams.topMargin = dp(context, 16);
            if (buttons.getChildCount() > 0) buttons.addView(rule, ruleParams);
            TextView button = heading(context, label, 16, R.color.tv_on_glass_secondary);
            button.setGravity(Gravity.CENTER); button.setContentDescription(label); button.setSingleLine(true);
            button.setBackgroundResource(R.drawable.tv_dialog_cancel);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 46));
            if (buttons.getChildCount() > 0) params.topMargin = dp(context, 16);
            buttons.addView(button, params);
            control(button, key, () -> { Sounds.play(Sounds.Sound.CANCEL); action.run(); }, ignored -> {}, 1.03f, false);
            return button;
        }
        /**
         * Shows the alert over a blurred copy of the screen behind it, on every Android version: one small screenshot,
         * blurred once off the main thread, rather than a live window blur (Android 12+ only, and often disabled).
         */
        public void show() {
            Context context = dialog.getContext();
            // Arrows inside the alert are the viewer's (focus ticks follow them); Back cancels with Cancel's sound.
            dialog.setOnKeyListener((d, code, event) -> {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && Sounds.arrow(code)) Sounds.input();
                if (code == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_UP) Sounds.play(Sounds.Sound.CANCEL);
                return false;
            });
            Sounds.play(Sounds.Sound.ALERT);
            FrameLayout root = new FrameLayout(context);
            ImageView behind = new ImageView(context);
            behind.setScaleType(ImageView.ScaleType.FIT_XY); behind.setAlpha(0f);
            behind.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(behind, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View tint = new View(context); tint.setBackgroundColor(context.getColor(R.color.tv_alert_tint));
            root.addView(tint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // A press outside the alert dismisses it, as a tap outside a dialog window would.
            root.setOnClickListener(v -> dialog.cancel());
            root.setFocusable(false);
            surface.setBackgroundResource(R.drawable.tv_dialog);
            // Clickable only to absorb presses on its padding; never a focus stop of its own.
            surface.setElevation(dp(context, 24)); surface.setClickable(true); surface.setFocusable(false);
            int width = Math.min(dp(context, 500), context.getResources().getDisplayMetrics().widthPixels - dp(context, 64));
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            params.topMargin = params.bottomMargin = dp(context, 32);
            root.addView(surface, params);
            dialog.setContentView(root);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                window.setWindowAnimations(android.R.style.Animation_Dialog);
            }
            blurBehind(context, behind);
            dialog.show();
            if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            View first = buttons.getChildCount() > 0 ? buttons.getChildAt(0) : null;
            if (first != null && surface.findFocus() == null) first.requestFocus();
        }
    }

    public static Alert alert(Context context, String title, CharSequence message) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout surface = new LinearLayout(context);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = dp(context, 30);
        surface.setPadding(side, dp(context, 32), side, dp(context, 24));
        TextView heading = heading(context, title, 21, R.color.tv_text);
        heading.setGravity(Gravity.CENTER);
        surface.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (message != null && message.length() > 0) {
            TextView body = text(context, message.toString(), 15, R.color.tv_text_secondary);
            body.setGravity(Gravity.CENTER); body.setLineSpacing(dp(context, 3), 1f);
            // A long message (diagnostics, an add-on's description) shrinks to scroll rather than pushing the buttons off
            // screen; the weight only takes effect when the alert would be taller than the screen.
            ScrollView scroll = new ScrollView(context);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(body);
            scroll.post(() -> scroll.setFocusable(scroll.canScrollVertically(1)));
            LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            bodyParams.topMargin = dp(context, 12);
            surface.addView(clipped(scroll, 0), bodyParams);
        }
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.setClipChildren(false); buttons.setClipToPadding(false);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsParams.topMargin = dp(context, 24);
        surface.addView(buttons, buttonsParams);
        surface.setClipChildren(false); surface.setClipToPadding(false);
        dialog.setCanceledOnTouchOutside(true);
        return new Alert(dialog, surface, buttons);
    }

    public static Alert alert(Context context, String title, @Nullable View bodyView) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout surface = new LinearLayout(context);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = dp(context, 30);
        surface.setPadding(side, dp(context, 32), side, dp(context, 24));
        TextView heading = heading(context, title, 21, R.color.tv_text);
        heading.setGravity(Gravity.CENTER);
        surface.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (bodyView != null) {
            ViewGroup.LayoutParams existing = bodyView.getLayoutParams();
            int height = existing != null && existing.height > 0 ? existing.height : ViewGroup.LayoutParams.WRAP_CONTENT;
            LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
            bodyParams.topMargin = dp(context, 14);
            surface.addView(bodyView, bodyParams);
        }
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.setClipChildren(false); buttons.setClipToPadding(false);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsParams.topMargin = dp(context, 20);
        surface.addView(buttons, buttonsParams);
        surface.setClipChildren(false); surface.setClipToPadding(false);
        dialog.setCanceledOnTouchOutside(true);
        return new Alert(dialog, surface, buttons);
    }

    /** A tvOS action sheet: each option is a full-width button, then Cancel set apart. Long lists scroll above Cancel. */
    public static Alert choices(Context context, String title, String[] options, Consumer<Integer> accept, Runnable cancel) {
        return choices(context, title, null, options, -1, accept, null, cancel);
    }

    public static Alert choices(Context context, String title, @Nullable View body, String[] options, int initialSelection,
                                Consumer<Integer> accept, @Nullable Consumer<Integer> onPreview, Runnable cancel) {
        Alert alert = alert(context, title, body);
        LinearLayout list = alert.buttons;
        if (options.length > 6 || (body != null && options.length > 4)) {
            int lift = dp(context, 10);
            ScrollView scroll = new ScrollView(context);
            // Fade the composite at the viewport edge, leaving clearance for focused rounded borders.
            scroll.setVerticalScrollBarEnabled(false); scroll.setPadding(0, dp(context, 8), 0, dp(context, 8));
            list = new LinearLayout(context);
            list.setOrientation(LinearLayout.VERTICAL); list.setClipChildren(false); list.setClipToPadding(false);
            scroll.addView(list);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, body != null ? 4 * 54 : 6 * 54));
            params.topMargin = dp(context, 14); params.leftMargin = -lift; params.rightMargin = -lift;
            alert.surface.addView(faded(scroll, lift, 18), alert.surface.indexOfChild(alert.buttons), params);
            ((LinearLayout.LayoutParams) alert.buttons.getLayoutParams()).topMargin = dp(context, 12);
        }
        Alert rows = new Alert(alert.dialog, alert.surface, list);
        View[] optionViews = new View[options.length];
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            optionViews[i] = rows.button("choice:" + i, options[i], () -> {
                alert.dialog.dismiss();
                accept.accept(index);
            }, key -> {
                if (onPreview != null) onPreview.accept(index);
                // ScrollView reveals the layout bounds only; include the focused platter's extra height too.
                View option = optionViews[index];
                if (option != null) option.post(() -> {
                    int clearance = dp(context, 8);
                    option.requestRectangleOnScreen(new android.graphics.Rect(0, -clearance,
                            option.getWidth(), option.getHeight() + clearance), false);
                });
            });
        }
        if (initialSelection >= 0 && initialSelection < options.length) {
            if (onPreview != null) onPreview.accept(initialSelection);
            View chosen = optionViews[initialSelection];
            if (chosen != null) alert.dialog.setOnShowListener(d -> chosen.requestFocus());
        } else if (optionViews.length > 0 && optionViews[0] != null) {
            alert.dialog.setOnShowListener(d -> optionViews[0].requestFocus());
        }
        alert.cancelButton("choice:cancel", "Cancel", alert.dialog::cancel);
        alert.dialog.setOnCancelListener(ignored -> { if (cancel != null) cancel.run(); });
        return rows;
    }

    /**
     * Clips a scrolling view to its own box. Its parents let focused controls grow past their bounds, which also lets
     * content scrolled out of view draw over its neighbours; a clipping frame stops that. {@code bleed} pixels on each
     * side stay inside the clip for a focused item's growth; give the frame matching negative margins.
     */
    public static FrameLayout clipped(ViewGroup scrolling, int bleed) {
        FrameLayout frame = new FrameLayout(scrolling.getContext());
        scrolling.setClipChildren(false); scrolling.setClipToPadding(false);
        scrolling.setPadding(scrolling.getPaddingLeft() + bleed, scrolling.getPaddingTop(), scrolling.getPaddingRight() + bleed, scrolling.getPaddingBottom());
        frame.addView(scrolling, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    /** Alpha fades on the clipped composite, rather than Android's padding-relative fading-edge bands. */
    public static FrameLayout faded(ViewGroup scrolling, int bleed, int lengthDp) {
        FrameLayout frame = new ScrollFade(scrolling, dp(scrolling.getContext(), lengthDp));
        scrolling.setVerticalFadingEdgeEnabled(false);
        scrolling.setClipChildren(false); scrolling.setClipToPadding(false);
        scrolling.setPadding(scrolling.getPaddingLeft() + bleed, scrolling.getPaddingTop(),
                scrolling.getPaddingRight() + bleed, scrolling.getPaddingBottom());
        frame.addView(scrolling, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrolling.setOnScrollChangeListener((view, x, y, oldX, oldY) -> frame.invalidate());
        if (scrolling instanceof androidx.recyclerview.widget.RecyclerView)
            ((androidx.recyclerview.widget.RecyclerView) scrolling).addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                @Override public void onScrolled(androidx.recyclerview.widget.RecyclerView view, int dx, int dy) { frame.invalidate(); }
            });
        return frame;
    }

    private static final class ScrollFade extends FrameLayout {
        private final ViewGroup scrolling;
        private final int length;
        private final android.graphics.Paint mask = new android.graphics.Paint();
        ScrollFade(ViewGroup scrolling, int length) {
            super(scrolling.getContext()); this.scrolling = scrolling; this.length = length;
            mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        }
        @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
            int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            super.dispatchDraw(canvas);
            float top = scrolling.canScrollVertically(-1) ? length : 0;
            float bottom = scrolling.canScrollVertically(1) ? length : 0;
            View focus = findFocus();
            if (focus != null && focus != scrolling) {
                android.graphics.Rect rect = new android.graphics.Rect(); focus.getDrawingRect(rect);
                offsetDescendantRectToMyCoords(focus, rect);
                // Leave the complete focus lift opaque. Other rows still fade at the viewport boundary.
                int clearance = dp(getContext(), 10);
                top = Math.min(top, Math.max(0, rect.top - clearance));
                bottom = Math.min(bottom, Math.max(0, getHeight() - rect.bottom - clearance));
            }
            if (top > 0) {
                mask.setShader(new android.graphics.LinearGradient(0, 0, 0, top, 0x00000000, 0xFF000000, android.graphics.Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, getWidth(), top, mask);
            }
            if (bottom > 0) {
                mask.setShader(new android.graphics.LinearGradient(0, getHeight() - bottom, 0, getHeight(), 0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
                canvas.drawRect(0, getHeight() - bottom, getWidth(), getHeight(), mask);
            }
            canvas.restoreToCount(layer);
        }
    }

    private static final java.util.concurrent.ExecutorService BLUR = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "alert-blur"); thread.setDaemon(true); return thread;
    });
    /**
     * Copies the activity's window at an eighth of its size, blurs that on a worker and fades it in behind the
     * alert. Video surfaces aren't part of the copy and stay dark, as under a dimmed alert.
     */
    private static void blurBehind(Context context, ImageView target) {
        // A dialog's context is its activity wrapped in the dialog theme.
        while (!(context instanceof android.app.Activity) && context instanceof android.content.ContextWrapper) context = ((android.content.ContextWrapper) context).getBaseContext();
        if (!(context instanceof android.app.Activity)) return;
        Window window = ((android.app.Activity) context).getWindow();
        View decor = window.getDecorView();
        if (decor.getWidth() == 0 || decor.getHeight() == 0) return;
        android.graphics.Bitmap small = android.graphics.Bitmap.createBitmap(Math.max(1, decor.getWidth() / 8), Math.max(1, decor.getHeight() / 8),
                android.graphics.Bitmap.Config.ARGB_8888);
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        try {
            android.view.PixelCopy.request(window, small, result -> {
                if (result != android.view.PixelCopy.SUCCESS) return;
                BLUR.execute(() -> {
                    android.graphics.Bitmap blurred = Artwork.blur(small);
                    main.post(() -> { target.setImageBitmap(blurred); target.animate().alpha(1f).setDuration(220).start(); });
                });
            }, main);
        } catch (IllegalArgumentException noSurface) { /* the tint alone still separates the alert */ }
    }

    public static ColorStateList colors(Context context, @ColorRes int color) { return context.getColorStateList(color); }
}
