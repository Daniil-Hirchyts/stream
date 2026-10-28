package tv.stream.app.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import tv.stream.app.R;

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
    /** Small round controls (back, search, page tools and arrows) grow more, so the change reads at their size. */
    public static final float LIFT_ICON = 1.12f;
    private static final ThreadLocal<View> ACTIVATING = new ThreadLocal<>();
    static View activatingControl() { return ACTIVATING.get(); }

    public static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    /**
     * Geist Sans: regular for body text, medium for headings and controls, semibold for emphasis and titles.
     */
    public enum Font {
        REGULAR(R.font.geist_regular), MEDIUM(R.font.geist_medium), SEMIBOLD(R.font.geist_semibold);
        final int resource;
        private Typeface typeface;
        Font(int resource) { this.resource = resource; }
        public Typeface get(Context context) {
            if (typeface == null) typeface = context.getResources().getFont(resource);
            return typeface;
        }
    }
    /** Repeated interface roles: size and weight stay consistent across content, lists and menus. */
    public enum Type {
        HERO(32, Font.SEMIBOLD, -.015f), TITLE(28, Font.SEMIBOLD, -.015f), SECTION(18, Font.SEMIBOLD, -.005f),
        CONTROL(15, Font.MEDIUM, 0), BODY(15, Font.REGULAR, 0), META(13, Font.MEDIUM, 0),
        CAPTION(13.5f, Font.MEDIUM, 0), EYEBROW(11.5f, Font.MEDIUM, .12f),
        MENU_TITLE(14, Font.MEDIUM, 0), VALUE(14, Font.REGULAR, 0);
        final float size, tracking;
        final Font font;
        Type(float size, Font font, float tracking) { this.size = size; this.font = font; this.tracking = tracking; }
    }
    public static TextView type(Context context, String value, Type role, @ColorRes int color) {
        TextView view = text(context, value, role.size, color);
        view.setTypeface(role.font.get(context)); view.setLetterSpacing(role.tracking);
        return view;
    }
    /** Large titles use tighter tracking; smaller emphasized text stays closer to its natural spacing. */
    private static final float TITLE_SP = 24;

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
        view.setTypeface(Font.SEMIBOLD.get(context));
        view.setLetterSpacing(sp >= TITLE_SP ? -0.015f : -0.005f);
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
        control(view, key, action, focused, () -> lift, raise);
    }

    /** {@code lift} is read at every change, for a control whose shape, and so its growth, changes. */
    public static void control(View view, String key, Runnable action, Consumer<String> focused, DoubleSupplier lift, boolean raise) {
        view.setTag(key);
        view.setFocusable(true); view.setFocusableInTouchMode(true); view.setClickable(true);
        view.setDefaultFocusHighlightEnabled(false);
        // The app's own sounds (Sounds) replace the platform's click.
        view.setSoundEffectsEnabled(false);
        view.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { Sounds.input(); if (!v.isFocused()) v.requestFocus(); }
            return false;
        });
        view.setOnClickListener(v -> {
            focused.accept(key); Sounds.click(); View previous = ACTIVATING.get(); ACTIVATING.set(v);
            try { action.run(); } finally { if (previous == null) ACTIVATING.remove(); else ACTIVATING.set(previous); }
        });
        view.setOnFocusChangeListener((v, has) -> { if (has) { focused.accept(key); Sounds.focus(v); } animate(v, has, (float) lift.getAsDouble(), raise); });
        animate(view, view.isFocused(), (float) lift.getAsDouble(), raise);
    }

    /**
     * A pointer over a {@link #control} grows it exactly as focus does (as the player's buttons do), and its
     * background's hovered state fades in a soft platter. It never moves focus, so the remote keeps its place.
     * Call it after {@link #control}, whose focus listener it extends.
     */
    public static void hover(View control, DoubleSupplier lift) {
        control.setOnHoverListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER) animate(view, true, (float) lift.getAsDouble(), false);
            else if (action == MotionEvent.ACTION_HOVER_EXIT) animate(view, view.isFocused(), (float) lift.getAsDouble(), false);
            return false; // The view still records its hovered state for the drawables.
        });
        View.OnFocusChangeListener focus = control.getOnFocusChangeListener();
        control.setOnFocusChangeListener((view, has) -> {
            if (focus != null) focus.onFocusChange(view, has);
            // Focus leaving a control the pointer is still over keeps its growth.
            if (!has && view.isHovered()) animate(view, true, (float) lift.getAsDouble(), false);
        });
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
        // Icon controls stay transparent at rest; other buttons may blur the artwork behind them.
        FrameLayout button = action.style == Item.Action.Style.ICON ? new FrameLayout(context) : GlassView.surface(context);
        bindButton(button, action, focused);
        return button;
    }

    /** Update a button's content and action without detaching its focusable surface. */
    public static void bindButton(FrameLayout button, Item.Action action, Consumer<String> focused) {
        Context context = button.getContext();
        ViewGroup.LayoutParams previousLayout = button.getLayoutParams();
        button.removeAllViews();
        button.setContentDescription(action.label);
        button.setTooltipText(action.style == Item.Action.Style.ICON ? action.label : null);
        LinearLayout content = new LinearLayout(context);
        content.setGravity(Gravity.CENTER);
        content.setDuplicateParentStateEnabled(true);
        int height = dp(context, action.style == Item.Action.Style.CHIP ? 36 : 44);
        switch (action.style) {
            case ICON: {
                button.setTooltipText(action.label);
                button.setBackgroundResource(R.drawable.tv_utility_button);
                content.addView(icon(context, action.icon, 20, R.color.tv_on_glass));
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
                    ImageView icon = icon(context, action.icon, chip ? 16 : 18, R.color.tv_on_glass);
                    ((LinearLayout.LayoutParams) icon.getLayoutParams()).rightMargin = dp(context, 8);
                    content.addView(icon);
                }
                TextView label = oneLine(heading(context, action.shown(), chip ? 14 : 15, chip ? R.color.tv_chip_text : R.color.tv_on_glass));
                if (action.style == Item.Action.Style.PRIMARY) { label.setTextSize(16); label.setTypeface(Font.SEMIBOLD.get(context)); }
                label.setDuplicateParentStateEnabled(true);
                content.addView(label);
                // A short value after the label: a chip's current filter, or the episode a Play button starts.
                if (!action.detail.isEmpty()) {
                    TextView value = oneLine(text(context, action.detail, chip ? 14 : 15, chip ? R.color.tv_chip_text_secondary : R.color.tv_on_glass_secondary));
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
        if (action.style == Item.Action.Style.ICON) hover(button, () -> LIFT_BUTTON);
        if (previousLayout != null) button.setLayoutParams(previousLayout);
    }

    // Context menus stay beside their opener. One surface contains the full navigation stack.
    public static final class Alert {
        public final Dialog dialog;
        public final LinearLayout surface, buttons;
        final String title;
        @Nullable View initialFocus;
        @Nullable Runnable prepare;
        @Nullable View customBody;
        View buttonHost;
        Alert(Dialog dialog, LinearLayout surface, LinearLayout buttons, String title) {
            this.dialog = dialog; this.surface = surface; this.buttons = buttons; this.title = title; this.buttonHost = buttons;
        }
        public Alert onAppear(Runnable prepare) { this.prepare = prepare; return this; }
        public Alert onClosing(Runnable action) { ((MorphDialog) dialog).beforeClose = action; return this; }
        public void reset() {
            initialFocus = null;
            if (buttonHost != buttons) {
                ((ViewGroup) buttons.getParent()).removeView(buttons);
                int index = surface.indexOfChild(buttonHost); ViewGroup.LayoutParams params = buttonHost.getLayoutParams();
                surface.removeView(buttonHost); surface.addView(buttons, index, params); buttonHost = buttons;
            }
            buttons.removeAllViews(); buttons.setVisibility(View.GONE);
            body(null);
        }
        public Alert body(@Nullable View body) {
            if (customBody != null) surface.removeView(customBody);
            customBody = body;
            if (body != null) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(dialog.getContext(), 10);
                surface.addView(body, surface.indexOfChild(buttonHost), params);
            }
            ((LinearLayout.LayoutParams) buttonHost.getLayoutParams()).topMargin = dp(dialog.getContext(), body == null ? 4 : 16);
            return this;
        }
        public void scrollChoices(int maxHeightDp) {
            if (buttonHost != buttons) return;
            Context context = dialog.getContext();
            ScrollView scroll = new ScrollView(context) {
                @Override protected void onMeasure(int width, int height) {
                    super.onMeasure(width, MeasureSpec.makeMeasureSpec(Math.min(dp(context, maxHeightDp), MeasureSpec.getSize(height)), MeasureSpec.AT_MOST));
                }
            };
            scroll.setVerticalScrollBarEnabled(false); scroll.setFocusable(false);
            scroll.setVerticalFadingEdgeEnabled(true); scroll.setFadingEdgeLength(dp(context, 12));
            int index = surface.indexOfChild(buttons); ViewGroup.LayoutParams params = buttons.getLayoutParams();
            surface.removeView(buttons); scroll.addView(buttons, new FrameLayout.LayoutParams(-1, -2));
            surface.addView(scroll, index, params); buttonHost = scroll;
        }
        public View choice(String key, String label, String detail, boolean selected, Runnable action) {
            return choice(key, label, detail, selected, action, ignored -> {});
        }
        public View choice(String key, String label, String detail, boolean selected, Runnable action, Consumer<String> focused) {
            View row = row(key, label, detail, 0, selected ? R.drawable.ic_tv_check : 0, action, focused);
            row.setSelected(selected); if (selected) initialFocus = row; return row;
        }
        public View sourceChoice(String key, String label, String detail, boolean selected, Runnable action) {
            View row = row(key, label, detail, 0, selected ? R.drawable.ic_tv_check : 0, action, ignored -> {}, false, true);
            row.setSelected(selected); if (selected) initialFocus = row; return row;
        }
        public void refresh() { ((MorphDialog) dialog).refreshCurrent(); }
        public View button(String key, String label, Runnable action) { return button(key, label, action, ignored -> {}); }
        public View button(String key, String label, Runnable action, Consumer<String> onFocused) {
            return row(key, label, "", menuIcon(label), 0, action, onFocused);
        }
        private View row(String key, String label, String detail, int glyph, int trailing, Runnable action, Consumer<String> onFocused) {
            return row(key, label, detail, glyph, trailing, action, onFocused, false, false);
        }
        private View row(String key, String label, String detail, int glyph, int trailing, Runnable action, Consumer<String> onFocused, boolean inline, boolean singleLabel) {
            Context context = dialog.getContext();
            LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(context, 44)); row.setPadding(dp(context, 12), dp(context, 11), dp(context, 12), dp(context, 11));
            row.setContentDescription(detail.isEmpty() ? label : label + ". " + detail);
            if (glyph != 0) {
                ImageView icon = icon(context, glyph, 18, R.color.tv_on_glass);
                ((LinearLayout.LayoutParams) icon.getLayoutParams()).rightMargin = dp(context, 12); row.addView(icon);
            }
            TextView text = lines(type(context, label, Type.CONTROL, R.color.tv_on_glass), singleLabel || inline ? 1 : 2);
            text.setDuplicateParentStateEnabled(true);
            LinearLayout column = new LinearLayout(context); column.setOrientation(LinearLayout.VERTICAL); column.setDuplicateParentStateEnabled(true);
            column.addView(text);
            if (!detail.isEmpty() && !inline) {
                TextView value = oneLine(text(context, detail, 12, R.color.tv_on_glass_secondary)); value.setDuplicateParentStateEnabled(true);
                LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2); spacing.topMargin = dp(context, 5); column.addView(value, spacing);
            }
            row.addView(column, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (!detail.isEmpty() && inline) {
                TextView value = oneLine(text(context, detail, 14, R.color.tv_on_glass_secondary));
                value.setDuplicateParentStateEnabled(true); value.setPadding(dp(context, 8), 0, 0, 0); row.addView(value);
            }
            if (trailing != 0) {
                ImageView mark = icon(context, trailing, 14, R.color.tv_on_glass);
                ((LinearLayout.LayoutParams) mark.getLayoutParams()).leftMargin = dp(context, 10); row.addView(mark);
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (buttons.getChildCount() > 0) params.topMargin = dp(context, 2);
            buttons.addView(row, params); buttons.setVisibility(View.VISIBLE);
            final int[] background = {0};
            Runnable shape = () -> {
                int wanted = !inline && (!detail.isEmpty() || text.getLineCount() > 1)
                        ? R.drawable.tv_dialog_button_multiline : R.drawable.tv_dialog_button;
                if (background[0] != wanted) { background[0] = wanted; row.setBackgroundResource(wanted); }
            };
            shape.run(); text.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> shape.run());
            control(row, key, action, onFocused != null ? onFocused : ignored -> {}, 1f, false);
            return row;
        }
        public View submenu(String key, String label, Consumer<Alert> contents) { return submenu(key, label, "", contents); }
        public View submenu(String key, String label, String detail, Consumer<Alert> contents) {
            View row = row(key, label, detail, menuIcon(label), R.drawable.ic_tv_chevron, () -> {
                Alert child = createAlert(dialog.getContext(), label, null, dialog, title);
                contents.accept(child); child.show();
            }, ignored -> {});
            row.setTag(R.id.popup_navigation, "submenu");
            return row;
        }
        public View submenuInline(String key, String label, String detail, Consumer<Alert> contents) {
            View row = row(key, label, detail, menuIcon(label), R.drawable.ic_tv_chevron, () -> {
                Alert child = createAlert(dialog.getContext(), label, null, dialog, title);
                contents.accept(child); child.show();
            }, ignored -> {}, true, true);
            row.setTag(R.id.popup_navigation, "submenu"); return row;
        }
        public void back() { ((MorphDialog) dialog).back(); }
        public void show() { ((MorphDialog) dialog).present(this); }
    }

    /** Icons only where the label names an action; value lists keep their single selected checkmark. */
    private static int menuIcon(String label) {
        String text = label.toLowerCase(java.util.Locale.ROOT);
        if (text.equals("play") || text.equals("resume") || text.equals("playback")) return R.drawable.ic_tv_play;
        if (text.equals("previous episode")) return R.drawable.ic_tv_previous;
        if (text.equals("next episode")) return R.drawable.ic_tv_next;
        if (text.equals("sources") || text.equals("choose source")) return R.drawable.ic_tv_list;
        if (text.equals("mark unwatched")) return R.drawable.ic_tv_unwatched;
        if (text.equals("mark watched")) return R.drawable.ic_tv_check;
        if (text.equals("keep for later")) return R.drawable.ic_tv_clock;
        if (text.contains("favourite")) return R.drawable.ic_tv_heart_outline;
        if (text.startsWith("remove") || text.startsWith("delete")) return R.drawable.ic_tv_delete;
        if (text.contains("retry") || text.startsWith("refresh")) return R.drawable.ic_tv_refresh;
        if (text.contains("cache") || text.startsWith("download")) return R.drawable.ic_tv_download;
        if (text.startsWith("rename")) return R.drawable.ic_tv_edit;
        if (text.equals("quality") || text.equals("speed") || text.equals("settings")) return R.drawable.ic_tv_settings;
        if (text.contains("subtitle")) return R.drawable.ic_tv_subtitles;
        if (text.equals("audio")) return R.drawable.ic_tv_audio;
        if (text.equals("continue")) return R.drawable.ic_tv_check;
        if (text.contains("report") || text.contains("diagnostics")) return R.drawable.ic_tv_info;
        return 0;
    }

    public static Alert alert(Context context, String title, CharSequence message) {
        Alert alert = createAlert(context, title, null, new MorphDialog(context, false), null);
        if (message != null && message.length() > 0) {
            ((LinearLayout.LayoutParams) alert.buttons.getLayoutParams()).topMargin = dp(context, 12);
            TextView body = text(context, message.toString(), 14, R.color.tv_text_secondary);
            body.setLineSpacing(dp(context, 3), 1f); body.setPadding(dp(context, 12), 0, dp(context, 12), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(context, 10);
            alert.surface.addView(body, alert.surface.indexOfChild(alert.buttons), params);
        }
        return alert;
    }
    public static Alert alert(Context context, String title, @Nullable View bodyView) {
        return createAlert(context, title, bodyView, new MorphDialog(context), null);
    }
    private static Alert createAlert(Context context, String title, @Nullable View body, Dialog dialog, @Nullable String parent) {
        LinearLayout surface = new LinearLayout(context); surface.setOrientation(LinearLayout.VERTICAL);
        surface.setPadding(dp(context, 8), dp(context, 20), dp(context, 8), dp(context, 8));
        LinearLayout header = new LinearLayout(context); header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(context, 12), 0, dp(context, 12), dp(context, 8));
        if (parent != null) {
            TextView breadcrumb = oneLine(text(context, parent, 11.5f, R.color.tv_text_tertiary));
            breadcrumb.setPadding(0, 0, 0, dp(context, 5)); header.addView(breadcrumb);
        }
        TextView heading = type(context, title, Type.MENU_TITLE, R.color.tv_text_secondary);
        heading.setTag("popup-title"); heading.setMaxLines(2); heading.setEllipsize(TextUtils.TruncateAt.END); heading.setLineSpacing(0, 1.12f);
        header.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        surface.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (body != null) {
            ViewGroup.LayoutParams existing = body.getLayoutParams();
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    existing != null && existing.height > 0 ? existing.height : ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(context, 8); surface.addView(body, params);
        }
        LinearLayout buttons = new LinearLayout(context); buttons.setOrientation(LinearLayout.VERTICAL); buttons.setVisibility(View.GONE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, body == null ? 4 : 12); surface.addView(buttons, params);
        return new Alert(dialog, surface, buttons, title);
    }

    public static Alert choices(Context context, String title, String[] options, Consumer<Integer> accept, Runnable cancel) {
        return choices(context, title, null, options, -1, accept, null, cancel);
    }
    public static Alert choices(Context context, String title, @Nullable View body, String[] options, int initialSelection,
                                Consumer<Integer> accept, @Nullable Consumer<Integer> onPreview, Runnable cancel) {
        Alert alert = alert(context, title, body);
        boolean actionIcons = !title.equals("Choose source") && !title.equals("Choose a video file") && initialSelection < 0;
        View[] optionViews = new View[options.length];
        // Long lists scroll independently so the heading and any preview stay visible.
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            optionViews[i] = alert.row("choice:" + i, options[i], "", actionIcons ? menuIcon(options[i]) : 0, i == initialSelection ? R.drawable.ic_tv_check : 0, () -> {
                alert.dialog.dismiss(); accept.accept(index);
            }, key -> {
                if (onPreview != null) onPreview.accept(index);
                View option = optionViews[index];
                if (option != null) option.post(() -> option.requestRectangleOnScreen(
                        new android.graphics.Rect(0, 0, option.getWidth(), option.getHeight()), false));
            });
        }
        if (initialSelection >= 0 && initialSelection < options.length) {
            if (onPreview != null) onPreview.accept(initialSelection);
            alert.initialFocus = optionViews[initialSelection];
        } else if (options.length > 0) alert.initialFocus = optionViews[0];
        if (options.length > (body == null ? 6 : 4)) alert.scrollChoices((body == null ? 6 : 4) * 46);
        alert.dialog.setOnCancelListener(ignored -> { if (cancel != null) cancel.run(); });
        return alert;
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
    public static FrameLayout faded(ViewGroup scrolling, int bleed, int lengthDp) { return faded(scrolling, bleed, lengthDp, lengthDp); }
    public static FrameLayout faded(ViewGroup scrolling, int bleed, int topDp, int bottomDp) {
        FrameLayout frame = new ScrollFade(scrolling, dp(scrolling.getContext(), topDp), dp(scrolling.getContext(), bottomDp));
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
        private final int topLength, bottomLength;
        private final android.graphics.Paint mask = new android.graphics.Paint();
        ScrollFade(ViewGroup scrolling, int topLength, int bottomLength) {
            super(scrolling.getContext()); this.scrolling = scrolling; this.topLength = topLength; this.bottomLength = bottomLength;
            mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        }
        @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
            int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            super.dispatchDraw(canvas);
            float top = scrolling.canScrollVertically(-1) ? topLength : 0;
            float bottom = scrolling.canScrollVertically(1) ? bottomLength : 0;
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

    public static ColorStateList colors(Context context, @ColorRes int color) { return context.getColorStateList(color); }
}
