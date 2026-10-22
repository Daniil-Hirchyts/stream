package tv.daniil.stream.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
        view.setTag(key);
        view.setFocusable(true); view.setFocusableInTouchMode(true); view.setClickable(true);
        view.setOnTouchListener((v, event) -> { if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !v.isFocused()) v.requestFocus(); return false; });
        view.setOnClickListener(v -> { focused.accept(key); action.run(); });
        view.setOnFocusChangeListener((v, has) -> { if (has) focused.accept(key); animate(v, has, lift); });
        animate(view, view.isFocused(), lift);
    }

    /** tvOS focus: the focused control grows and floats above its neighbours. */
    public static void animate(View view, boolean focused, float lift) {
        float scale = focused ? lift : 1f;
        view.animate().cancel();
        view.animate().scaleX(scale).scaleY(scale).translationZ(focused ? dp(view.getContext(), 14) : 0)
                .setDuration(focused ? 180 : 140).setInterpolator(EASE).start();
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
        LinearLayout button = new LinearLayout(context);
        button.setGravity(Gravity.CENTER); button.setContentDescription(action.label);
        int height = dp(context, action.style == Item.Action.Style.CHIP ? 44 : 52);
        switch (action.style) {
            case ICON: {
                button.setBackgroundResource(R.drawable.tv_button_round);
                button.addView(icon(context, action.icon, 26, R.color.tv_on_glass));
                button.setLayoutParams(new LinearLayout.LayoutParams(height, height));
                break;
            }
            case TILE: {
                button.setOrientation(LinearLayout.VERTICAL); button.setGravity(Gravity.START | Gravity.TOP);
                button.setBackgroundResource(R.drawable.tv_tile_button);
                int pad = dp(context, 20); button.setPadding(pad, pad, pad, pad);
                if (action.icon != 0) {
                    ImageView icon = icon(context, action.icon, 32, R.color.tv_on_glass);
                    ((LinearLayout.LayoutParams) icon.getLayoutParams()).bottomMargin = dp(context, 14);
                    button.addView(icon);
                }
                TextView label = oneLine(heading(context, action.label, 19, R.color.tv_on_glass)); label.setDuplicateParentStateEnabled(true);
                button.addView(label);
                if (!action.detail.isEmpty()) {
                    TextView detail = lines(text(context, action.detail, 14, R.color.tv_on_glass_secondary), 3);
                    detail.setDuplicateParentStateEnabled(true); detail.setPadding(0, dp(context, 8), 0, 0);
                    button.addView(detail);
                }
                button.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 250), dp(context, 156)));
                break;
            }
            default: {
                button.setOrientation(LinearLayout.HORIZONTAL);
                button.setBackgroundResource(action.style == Item.Action.Style.CHIP ? R.drawable.tv_chip : R.drawable.tv_button);
                int pad = dp(context, action.style == Item.Action.Style.PRIMARY ? 30 : 22);
                button.setPadding(pad, 0, pad, 0);
                button.setMinimumWidth(dp(context, action.style == Item.Action.Style.PRIMARY ? 168 : 52));
                if (action.icon != 0) {
                    ImageView icon = icon(context, action.icon, 22, R.color.tv_on_glass);
                    ((LinearLayout.LayoutParams) icon.getLayoutParams()).rightMargin = dp(context, 10);
                    button.addView(icon);
                }
                TextView label = oneLine(heading(context, action.label, action.style == Item.Action.Style.CHIP ? 16 : 18, R.color.tv_on_glass));
                label.setDuplicateParentStateEnabled(true);
                button.addView(label);
                if (action.style == Item.Action.Style.CHIP && !action.detail.isEmpty()) {
                    TextView value = oneLine(text(context, action.detail, 16, R.color.tv_on_glass_secondary));
                    value.setDuplicateParentStateEnabled(true); value.setPadding(dp(context, 8), 0, 0, 0);
                    button.addView(value);
                }
                button.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, height));
                break;
            }
        }
        button.setActivated(action.selected);
        control(button, action.key, action.action, focused, action.style == Item.Action.Style.TILE ? 1.06f : 1.1f);
        return button;
    }

    public static ColorStateList colors(Context context, @ColorRes int color) { return context.getColorStateList(color); }
}
