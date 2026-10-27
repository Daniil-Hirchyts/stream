package tv.daniil.stream.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;
import android.text.style.StyleSpan;
import androidx.annotation.Nullable;
import tv.daniil.playback.SubtitleStyle;
import tv.daniil.stream.R;

/** App-owned font choices; eight legacy keys/indices remain valid and app-only choices are appended. */
public final class SubtitleFonts {
    public static final int NOTO = 0, ATKINSON = 8, SYSTEM = 9;
    public static final String[] VALUES = {"standard", "bold", "condensed", "serif", "monospace", "casual", "cursive", "smallcaps", "atkinson", "system"};
    private static final int[] LABELS = {R.string.subtitle_font_noto, tv.daniil.playback.R.string.font_bold,
            tv.daniil.playback.R.string.font_condensed, tv.daniil.playback.R.string.font_serif, tv.daniil.playback.R.string.font_monospace,
            tv.daniil.playback.R.string.font_casual, tv.daniil.playback.R.string.font_cursive, tv.daniil.playback.R.string.font_smallcaps,
            R.string.subtitle_font_atkinson, R.string.subtitle_font_system};
    @Nullable private static Typeface noto, atkinson;
    private static float cyrillicScale;
    private SubtitleFonts() {}

    public static int selected(SharedPreferences preferences) {
        String value = preferences.getString("subtitle_font", "standard");
        for (int i = 0; i < VALUES.length; i++) if (VALUES[i].equals(value)) return i;
        return NOTO;
    }
    public static String[] labels(Context context) {
        String[] labels = new String[LABELS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = context.getString(LABELS[i]);
        return labels;
    }
    public static synchronized Typeface typeface(Context context, int index) {
        if (index == ATKINSON) {
            if (atkinson == null) atkinson = context.getResources().getFont(R.font.subtitle_atkinson);
            return atkinson;
        }
        if (index == SYSTEM) return SubtitleStyle.typefaceFor(0);
        if (index > 0 && index < SubtitleStyle.fontsCount()) return SubtitleStyle.typefaceFor(index);
        if (noto == null) noto = context.getResources().getFont(R.font.subtitle_noto);
        return noto;
    }

    /** Keep semantic emphasis while provider font, size and colour overrides stay under viewer control. */
    public static SpannableString text(Context context, int index, CharSequence source) {
        SpannableString text = new SpannableString(source.toString());
        if (source instanceof Spanned) {
            Spanned styled = (Spanned) source;
            for (StyleSpan span : styled.getSpans(0, source.length(), StyleSpan.class)) {
                int start = styled.getSpanStart(span), end = styled.getSpanEnd(span);
                if (start >= 0 && end > start && end <= text.length())
                    text.setSpan(new StyleSpan(span.getStyle()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (index == ATKINSON) {
            int start = -1;
            for (int offset = 0; offset <= text.length();) {
                int point = offset < text.length() ? Character.codePointAt(text, offset) : -1;
                boolean cyrillic = point >= 0 && Character.UnicodeScript.of(point) == Character.UnicodeScript.CYRILLIC;
                boolean mark = point >= 0 && (Character.getType(point) == Character.NON_SPACING_MARK || Character.getType(point) == Character.COMBINING_SPACING_MARK);
                if (cyrillic && start < 0) start = offset;
                if (start >= 0 && !cyrillic && !mark) {
                    text.setSpan(new CyrillicFont(typeface(context, NOTO), cyrillicScale(context)), start, offset, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); start = -1;
                }
                if (point < 0) break;
                offset += Character.charCount(point);
            }
        }
        return text;
    }
    private static synchronized float cyrillicScale(Context context) {
        if (cyrillicScale == 0f) {
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG); paint.setTextSize(1000f);
            Rect bounds = new Rect();
            paint.setTypeface(typeface(context, NOTO)); paint.getTextBounds("x", 0, 1, bounds); int notoHeight = bounds.height();
            paint.setTypeface(typeface(context, ATKINSON)); paint.getTextBounds("x", 0, 1, bounds);
            cyrillicScale = notoHeight > 0 && bounds.height() > 0 ? bounds.height() / (float) notoHeight : 1f;
        }
        return cyrillicScale;
    }
    private static final class CyrillicFont extends MetricAffectingSpan {
        private final Typeface family;
        private final float scale;
        CyrillicFont(Typeface family, float scale) { this.family = family; this.scale = scale; }
        @Override public void updateDrawState(TextPaint paint) { apply(paint); }
        @Override public void updateMeasureState(TextPaint paint) { apply(paint); }
        private void apply(TextPaint paint) {
            Typeface current = paint.getTypeface();
            paint.setTypeface(Typeface.create(family, current == null ? Typeface.NORMAL : current.getStyle()));
            // Noto has a larger lowercase height; match Atkinson instead of enlarging Cyrillic words mid-cue.
            paint.setTextSize(paint.getTextSize() * scale);
        }
    }
}
