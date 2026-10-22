package tv.daniil.playback;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * How subtitles look, saved on the TV. Each setting is a short list of tested choices; the keys are also the
 * values the Chrome extension sends, so both sides stay in step. The font is chosen on the TV only.
 */
@OptIn(markerClass = UnstableApi.class)
public final class SubtitleStyle {
    public enum Setting {
        SIZE("size", R.string.subtitle_size, 1, new String[] {"small", "medium", "large", "xlarge"},
                new int[] {R.string.size_small, R.string.size_medium, R.string.size_large, R.string.size_xlarge}),
        FONT("font", R.string.subtitle_font, 0, new String[] {"standard", "bold", "condensed", "serif"},
                new int[] {R.string.font_standard, R.string.font_bold, R.string.font_condensed, R.string.font_serif}),
        COLOR("color", R.string.subtitle_color, 0, new String[] {"white", "yellow", "cyan"},
                new int[] {R.string.color_white, R.string.color_yellow, R.string.color_cyan}),
        EDGE("edge", R.string.subtitle_edge, 2, new String[] {"outline", "shadow", "box"},
                new int[] {R.string.edge_outline, R.string.edge_shadow, R.string.edge_box}),
        POSITION("position", R.string.subtitle_position, 1, new String[] {"low", "standard", "raised"},
                new int[] {R.string.position_low, R.string.position_standard, R.string.position_raised});

        public final String key;
        final int title;
        final int fallback;
        final String[] values;
        final int[] labels;

        Setting(String key, int title, int fallback, String[] values, int[] labels) {
            this.key = key;
            this.title = title;
            this.fallback = fallback;
            this.values = values;
            this.labels = labels;
        }
    }

    // Text height as a fraction of the video height; Media3's default is 0.0533.
    private static final float[] SIZES = {0.042f, 0.0533f, 0.066f, 0.082f};
    private static final int[] COLORS = {Color.WHITE, 0xFFFFE15A, 0xFF8BE9FF};
    // Gap below the last line, as a fraction of the video height.
    private static final float[] BOTTOM_GAP = {0.04f, 0.08f, 0.16f};
    private static final int BOX = 0xB3000000;
    // System families every Android TV has: medium sans (the default), bold sans for bright or busy pictures,
    // condensed for long lines, and serif for a classic film look.
    private static final Typeface[] FONTS = {
            Typeface.create("sans-serif-medium", Typeface.NORMAL), Typeface.create("sans-serif", Typeface.BOLD),
            Typeface.create("sans-serif-condensed", Typeface.BOLD), Typeface.create("serif", Typeface.NORMAL)};

    private final SharedPreferences prefs;
    private final int[] choice = new int[Setting.values().length];

    SubtitleStyle(SharedPreferences prefs) {
        this.prefs = prefs;
        for (Setting setting : Setting.values()) {
            int index = indexOf(setting, prefs.getString("subtitle_" + setting.key, null));
            choice[setting.ordinal()] = index < 0 ? setting.fallback : index;
        }
    }

    public int get(Setting setting) {
        return choice[setting.ordinal()];
    }

    /** Returns whether the value changed. */
    public boolean set(Setting setting, int index) {
        if (index < 0 || index >= setting.values.length || index == get(setting)) return false;
        choice[setting.ordinal()] = index;
        prefs.edit().putString("subtitle_" + setting.key, setting.values[index]).apply();
        return true;
    }

    /** Applies a value sent by the extension, such as {@code size=large}; unknown values are ignored. */
    public boolean set(Setting setting, String value) {
        return set(setting, indexOf(setting, value));
    }

    void applyTo(SubtitleView view) {
        int edge = get(Setting.EDGE);
        view.setStyle(new CaptionStyleCompat(COLORS[get(Setting.COLOR)],
                edge == 2 ? BOX : Color.TRANSPARENT, Color.TRANSPARENT,
                edge == 0 ? CaptionStyleCompat.EDGE_TYPE_OUTLINE
                        : edge == 1 ? CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW : CaptionStyleCompat.EDGE_TYPE_NONE,
                Color.BLACK, FONTS[get(Setting.FONT)]));
        // Site-supplied colours and sizes would override the viewer's choice, so only cue positions are kept.
        view.setApplyEmbeddedStyles(false);
        view.setFractionalTextSize(SIZES[get(Setting.SIZE)], true);
        // WebVTT cues are anchored to the bottom edge by line number, which ignores Media3's bottom padding
        // fraction. The player pads the view by bottomGap() instead, so every format moves together.
        view.setBottomPaddingFraction(0f);
    }

    float bottomGap() {
        return BOTTOM_GAP[get(Setting.POSITION)];
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        for (Setting setting : Setting.values()) json.put(setting.key, setting.values[get(setting)]);
        return json;
    }

    private static int indexOf(Setting setting, String value) {
        for (int i = 0; i < setting.values.length; i++) if (setting.values[i].equals(value)) return i;
        return -1;
    }
}
