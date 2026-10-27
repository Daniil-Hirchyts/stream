package androidx.media3.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathEffect;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.LineBackgroundSpan;
import android.view.View;
import android.widget.FrameLayout;
import androidx.annotation.OptIn;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.UnstableApi;
import java.text.Bidi;
import java.util.ArrayList;
import java.util.List;
import tv.stream.playback.SubtitleStyle;
import tv.stream.app.ui.SubtitleFonts;

/** Stream's fonts and Box styling; semantic emphasis survives provider-style sanitization. */
@OptIn(markerClass = UnstableApi.class)
public final class StreamSubtitleOutput extends FrameLayout implements SubtitleView.Output {
    private final CanvasSubtitleOutput canvas;
    private float textSize;
    private int textSizeType;
    private boolean boxed;
    private int fontIndex = -1;
    private List<Cue> inputCues = java.util.Collections.emptyList();
    private CaptionStyleCompat inputStyle;
    private float inputBottomGap;

    private StreamSubtitleOutput(SubtitleView view) {
        super(view.getContext());
        canvas = new CanvasSubtitleOutput(getContext());
        addView(canvas, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public static void install(SubtitleView view) {
        // SubtitleView is final and exposes no output factory in pinned Media3 1.11.1. Keep this
        // one integration point (also retained by R8) app-local; the receiver keeps its renderer.
        try {
            java.lang.reflect.Method replace = SubtitleView.class.getDeclaredMethod("setView", View.class);
            replace.setAccessible(true);
            replace.invoke(view, new StreamSubtitleOutput(view));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Media3 subtitle output integration changed", e);
        }
    }

    /** The shared player still owns size/colour/position; these font choices belong only to Stream. */
    public static void font(SubtitleView view, int index) {
        if (!(view.getChildAt(0) instanceof StreamSubtitleOutput)) throw new IllegalStateException("Subtitle output is not installed");
        StreamSubtitleOutput output = (StreamSubtitleOutput) view.getChildAt(0);
        output.fontIndex = index >= 0 && index < SubtitleFonts.VALUES.length ? index : SubtitleFonts.NOTO;
        if (output.inputStyle != null) output.update(output.inputCues, output.inputStyle, output.textSize, output.textSizeType, output.inputBottomGap);
    }

    /** Preserve cue italics; update() removes provider font/size/colour overrides before painting. */
    public static void configure(SubtitleView view) {
        view.setApplyEmbeddedStyles(true); view.setApplyEmbeddedFontSizes(false);
    }

    @Override public void update(List<Cue> cues, CaptionStyleCompat style, float size, int sizeType, float bottomGap) {
        inputCues = cues; inputStyle = style; inputBottomGap = bottomGap;
        int chosen = fontIndex;
        if (chosen < 0) {
            chosen = SubtitleFonts.NOTO;
            for (int i = 1; i < SubtitleStyle.fontsCount(); i++) if (SubtitleStyle.typefaceFor(i).equals(style.typeface)) { chosen = i; break; }
        }
        style = new CaptionStyleCompat(style.foregroundColor, style.backgroundColor, style.windowColor,
                style.edgeType, style.edgeColor, SubtitleFonts.typeface(getContext(), chosen));
        List<Cue> clean = new ArrayList<>(cues.size());
        for (Cue cue : cues) clean.add(cue.text == null ? cue : cue.buildUpon()
                .setText(SubtitleFonts.text(getContext(), chosen, cue.text)).clearWindowColor()
                .setTextSize(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).build());
        cues = clean;
        boxed = Color.alpha(style.backgroundColor) > 0 && style.edgeType == CaptionStyleCompat.EDGE_TYPE_NONE;
        textSize = size; textSizeType = sizeType; updatePadding();
        if (boxed) {
            List<Cue> styled = new ArrayList<>(cues.size());
            for (Cue cue : cues) {
                if (cue.text == null || cue.text.length() == 0) { styled.add(cue); continue; }
                SpannableString text = new SpannableString(cue.text);
                text.setSpan(new Box(style.backgroundColor, cue.textAlignment), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.add(cue.buildUpon().setText(text).build());
            }
            cues = styled;
            style = new CaptionStyleCompat(style.foregroundColor, Color.TRANSPARENT, style.windowColor,
                    style.edgeType, style.edgeColor, style.typeface);
        }
        canvas.update(cues, style, size, sizeType, bottomGap);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight); updatePadding();
    }

    private void updatePadding() {
        float pixels = SubtitleViewUtils.resolveTextSize(textSizeType, textSize, getHeight(), getHeight());
        int inset = boxed ? Math.max(0, (int) Math.ceil(pixels * 0.35f)) : 0;
        // Leave room for the box even when a cue wraps across the full viewport.
        if (canvas.getPaddingLeft() != inset) canvas.setPadding(inset, 0, inset, 0);
    }

    private static final class Box implements LineBackgroundSpan {
        private final int color;
        private final Layout.Alignment alignment;
        private final Path shape = new Path(), row = new Path();
        private final Rect clip = new Rect();
        private final RectF line = new RectF();
        private int previousLine = -1;
        Box(int color, Layout.Alignment alignment) { this.color = color; this.alignment = alignment; }

        @Override public void drawBackground(Canvas target, Paint paint, int left, int right, int top, int baseline,
                int bottom, CharSequence text, int start, int end, int lineNumber) {
            if (lineNumber <= previousLine) { shape.reset(); line.setEmpty(); }
            previousLine = lineNumber;
            boolean last = end == text.length();
            while (end > start && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) end--;
            float padding = paint.getTextSize() * 0.3f, radius = paint.getTextSize() * 0.24f;
            if (end > start) {
                float width = Layout.getDesiredWidth(text, start, end, new android.text.TextPaint(paint));
                boolean rtl = !new Bidi(text.subSequence(start, end).toString(), Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight();
                float x = (left + right - width) / 2f;
                if (alignment == Layout.Alignment.ALIGN_NORMAL) x = rtl ? right - width : left;
                else if (alignment == Layout.Alignment.ALIGN_OPPOSITE) x = rtl ? left : right - width;
                line.set(x - padding, top, x + width + padding, bottom);
            } else if (!line.isEmpty()) {
                // An intentional empty row still belongs to the same caption background.
                line.top = top; line.bottom = bottom;
            }
            if (!line.isEmpty()) {
                row.reset(); row.addRect(line, Path.Direction.CW);
                shape.op(row, Path.Op.UNION);
            }
            // Layout draws every line background before any glyphs. Fill the combined contour once,
            // after collecting its rows: no seams or darker overlaps, and no full-width rectangle.
            target.getClipBounds(clip);
            // Layout may omit the last rows when a long cue extends outside the viewport.
            if ((!last && bottom < clip.bottom) || shape.isEmpty()) return;
            int savedColor = paint.getColor(); Paint.Style savedStyle = paint.getStyle();
            PathEffect savedEffect = paint.getPathEffect();
            paint.setColor(color); paint.setStyle(Paint.Style.FILL);
            // Rounds the outside corners and the inward steps where adjacent line widths differ.
            paint.setPathEffect(new CornerPathEffect(radius));
            target.drawPath(shape, paint);
            paint.setColor(savedColor); paint.setStyle(savedStyle); paint.setPathEffect(savedEffect);
            shape.reset(); line.setEmpty(); previousLine = -1;
        }
    }
}
