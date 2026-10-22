package tv.daniil.stream.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.app.Dialog;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.app.AlertDialog;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.ColorRes;
import java.util.function.Consumer;
import tv.daniil.stream.R;

/** Shared view building: type, focus motion and the click/touch/remote wiring every control uses. */
public final class Tv {
    private Tv() {}
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2f);

    public static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    public static TextView text(Context context, String value, float sp, @ColorRes int color) {
        TextView view = new TextView(context);
        view.setText(value); view.setTextSize(sp); view.setTextColor(context.getColorStateList(color));
        view.setIncludeFontPadding(false);
        return view;
    }
    public static TextView heading(Context context, String value, float sp, @ColorRes int color) {
        TextView view = text(context, value, sp, color);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }
    public static TextView bold(Context context, String value, float sp) {
        TextView view = text(context, value, sp, R.color.tv_text);
        view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        view.setLetterSpacing(-0.01f);
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
        view.setOnTouchListener((v, event) -> { if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !v.isFocused()) v.requestFocus(); return false; });
        view.setOnClickListener(v -> { focused.accept(key); action.run(); });
        view.setOnFocusChangeListener((v, has) -> { if (has) focused.accept(key); animate(v, has, lift, raise); });
        animate(view, view.isFocused(), lift, raise);
    }

    /** tvOS focus: the focused control grows and floats above its neighbours. */
    public static void animate(View view, boolean focused, float lift) {
        animate(view, focused, lift, true);
    }

    /** Some controls have enough layout clearance to grow without an elevation shadow. */
    public static void animate(View view, boolean focused, float lift, boolean raise) {
        float scale = focused ? lift : 1f;
        view.animate().cancel();
        view.animate().scaleX(scale).scaleY(scale).translationZ(focused && raise ? dp(view.getContext(), 9) : 0)
                .setDuration(focused ? 180 : 140).setInterpolator(EASE).start();
    }

    /** Applies Android's translucent window blur when the platform supports it. */
    public static void styleDialog(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setGravity(Gravity.CENTER);
        window.setDimAmount(0.16f);
        Context context = dialog.getContext();
        int availableWidth = context.getResources().getDisplayMetrics().widthPixels - dp(context, 64);
        int height = FrameLayout.LayoutParams.WRAP_CONTENT;
        if (dialog instanceof AlertDialog) {
            View options = dialog.findViewById(android.R.id.list);
            if (options != null) {
                int availableHeight = context.getResources().getDisplayMetrics().heightPixels - dp(context, 96);
                height = Math.min(dp(context, 480), availableHeight);
                if (options instanceof android.widget.ListView) {
                    android.widget.ListView list = (android.widget.ListView) options;
                    list.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                    list.setDividerHeight(0);
                    list.setSelector(R.drawable.tv_row);
                }
            }
        }
        window.setLayout(Math.min(dp(context, 640), availableWidth), height);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setBackgroundBlurRadius(dp(dialog.getContext(), 8));
        }
        if (dialog instanceof AlertDialog) styleAlertDialog((AlertDialog) dialog);
    }

    /** A centered, scrollable glass picker shared by settings and playback choices. */
    public static Dialog glassChoices(Context context, String title, String[] options, Consumer<Integer> accept, Runnable cancel) {
        Dialog dialog = new Dialog(context);
        LinearLayout surface = new LinearLayout(context);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setBackgroundResource(R.drawable.tv_dialog);
        int horizontal = dp(context, 32);
        surface.setPadding(horizontal, dp(context, 26), horizontal, dp(context, 24));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = heading(context, title, 25, R.color.tv_text);
        heading.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(heading);
        ImageView close = icon(context, R.drawable.ic_tv_close, 22, R.color.tv_text_secondary);
        close.setBackgroundResource(R.drawable.tv_button_round);
        close.setContentDescription("Cancel");
        close.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48));
        closeParams.setMarginStart(dp(context, 18));
        header.addView(close, closeParams);
        surface.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 48)));
        control(close, "choice:cancel", dialog::cancel, ignored -> {}, 1.04f, false);

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            TextView option = text(context, options[i], 18, R.color.tv_text);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 10));
            option.setMinHeight(dp(context, 52));
            option.setBackgroundResource(R.drawable.tv_row);
            LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            optionParams.bottomMargin = dp(context, 6);
            rows.addView(option, optionParams);
            control(option, "choice:" + i, () -> { dialog.dismiss(); accept.accept(index); }, ignored -> {}, 1.025f, false);
        }
        scroll.addView(rows);
        int rowsHeight = options.length * dp(context, 58);
        int listHeight = Math.min(dp(context, 300), rowsHeight);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight);
        scrollParams.topMargin = dp(context, 18);
        surface.addView(scroll, scrollParams);

        dialog.setContentView(surface);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnCancelListener(ignored -> { if (cancel != null) cancel.run(); });
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        return dialog;
    }

    private static void styleAlertDialog(AlertDialog dialog) {
        Context context = dialog.getContext();
        TextView title = dialog.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextSize(25);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setTextSize(17);
            message.setLineSpacing(dp(context, 3), 1f);
        }
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        styleDialogButton(positive, true, context);
        styleDialogButton(negative, false, context);
        styleDialogButton(neutral, false, context);

        if (positive != null && positive.getParent() instanceof LinearLayout) {
            LinearLayout actions = (LinearLayout) positive.getParent();
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            if (negative != null && negative.getParent() == actions) {
                int positiveIndex = actions.indexOfChild(positive);
                int negativeIndex = actions.indexOfChild(negative);
                if (positiveIndex >= 0 && negativeIndex >= 0 && positiveIndex < negativeIndex) {
                    actions.removeView(positive);
                    actions.addView(positive, Math.min(negativeIndex, actions.getChildCount()));
                }
            }
        }
    }

    private static void styleDialogButton(Button button, boolean primary, Context context) {
        if (button == null) return;
        button.setAllCaps(false);
        button.setTextSize(17);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(context.getColor(primary ? R.color.tv_on_focus : R.color.tv_text));
        button.setBackgroundResource(primary ? R.drawable.tv_dialog_primary : R.drawable.tv_dialog_button);
        button.setMinimumHeight(dp(context, 50));
        ViewGroup.LayoutParams raw = button.getLayoutParams();
        if (raw instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) raw;
            params.setMargins(dp(context, 6), params.topMargin, dp(context, 6), params.bottomMargin);
            button.setLayoutParams(params);
        }
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

    /** A rounded or circular button in one of the {@link Item.Action} styles. */
    public static View button(Context context, Item.Action action, Consumer<String> focused) {
        GlassView button = new GlassView(context);
        button.setContentDescription(action.label);
        LinearLayout content = new LinearLayout(context);
        content.setGravity(Gravity.CENTER);
        content.setDuplicateParentStateEnabled(true);
        int height = dp(context, action.style == Item.Action.Style.CHIP ? 44 : 52);
        switch (action.style) {
            case ICON: {
                button.setBackgroundResource(R.drawable.tv_button_round);
                content.addView(icon(context, action.icon, 26, R.color.tv_on_glass));
                button.setLayoutParams(new LinearLayout.LayoutParams(height, height));
                button.addView(content, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                break;
            }
            case TILE: {
                content.setOrientation(LinearLayout.VERTICAL);
                content.setGravity(Gravity.START | Gravity.TOP);
                button.setBackgroundResource(R.drawable.tv_tile_button);
                int pad = dp(context, 20);
                content.setPadding(pad, pad, pad, pad);
                if (action.icon != 0) {
                    ImageView icon = icon(context, action.icon, 32, R.color.tv_on_glass);
                    ((LinearLayout.LayoutParams) icon.getLayoutParams()).bottomMargin = dp(context, 14);
                    content.addView(icon);
                }
                TextView label = oneLine(heading(context, action.label, 19, R.color.tv_on_glass));
                label.setDuplicateParentStateEnabled(true);
                content.addView(label);
                if (!action.detail.isEmpty()) {
                    TextView detail = lines(text(context, action.detail, 14, R.color.tv_on_glass_secondary), 3);
                    detail.setDuplicateParentStateEnabled(true);
                    detail.setPadding(0, dp(context, 8), 0, 0);
                    content.addView(detail);
                }
                button.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 250), dp(context, 156)));
                button.addView(content, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                break;
            }
            default: {
                content.setOrientation(LinearLayout.HORIZONTAL);
                button.setBackgroundResource(action.style == Item.Action.Style.CHIP ? R.drawable.tv_chip : R.drawable.tv_button);
                int pad = dp(context, action.style == Item.Action.Style.PRIMARY ? 30 : 22);
                content.setPadding(pad, 0, pad, 0);
                content.setMinimumWidth(dp(context, action.style == Item.Action.Style.PRIMARY ? 168 : 52));
                if (action.icon != 0) {
                    ImageView icon = icon(context, action.icon, 22, R.color.tv_on_glass);
                    ((LinearLayout.LayoutParams) icon.getLayoutParams()).rightMargin = dp(context, 10);
                    content.addView(icon);
                }
                TextView label = oneLine(heading(context, action.label, action.style == Item.Action.Style.CHIP ? 16 : 18, R.color.tv_on_glass));
                label.setDuplicateParentStateEnabled(true);
                content.addView(label);
                if (action.style == Item.Action.Style.CHIP && !action.detail.isEmpty()) {
                    TextView value = oneLine(text(context, action.detail, 16, R.color.tv_on_glass_secondary));
                    value.setDuplicateParentStateEnabled(true);
                    value.setPadding(dp(context, 8), 0, 0, 0);
                    content.addView(value);
                }
                button.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, height));
                button.addView(content, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
                break;
            }
        }
        button.setActivated(action.selected);
        control(button, action.key, action.action, focused, action.style == Item.Action.Style.TILE ? 1.04f : 1.06f);
        return button;
    }

    public static ColorStateList colors(Context context, @ColorRes int color) { return context.getColorStateList(color); }
}
