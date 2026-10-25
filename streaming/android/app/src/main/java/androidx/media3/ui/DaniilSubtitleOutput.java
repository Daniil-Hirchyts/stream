package androidx.media3.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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

/** Daniil TV's Box styling, applied after Media3 removes embedded provider styles. */
@OptIn(markerClass = UnstableApi.class)
public final class DaniilSubtitleOutput extends FrameLayout implements SubtitleView.Output {
    private final CanvasSubtitleOutput canvas;
    private float textSize;
    private int textSizeType;
    private boolean boxed;

    private DaniilSubtitleOutput(SubtitleView view) {
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
            replace.invoke(view, new DaniilSubtitleOutput(view));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Media3 subtitle output integration changed", e);
        }
    }

    @Override public void update(List<Cue> cues, CaptionStyleCompat style, float size, int sizeType, float bottomGap) {
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
        Box(int color, Layout.Alignment alignment) { this.color = color; this.alignment = alignment; }

        @Override public void drawBackground(Canvas target, Paint paint, int left, int right, int top, int baseline,
                int bottom, CharSequence text, int start, int end, int lineNumber) {
            while (end > start && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) end--;
            if (end <= start) return;
            float width = Layout.getDesiredWidth(text, start, end, new android.text.TextPaint(paint));
            boolean rtl = !new Bidi(text.subSequence(start, end).toString(), Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight();
            float x = (left + right - width) / 2f;
            if (alignment == Layout.Alignment.ALIGN_NORMAL) x = rtl ? right - width : left;
            else if (alignment == Layout.Alignment.ALIGN_OPPOSITE) x = rtl ? left : right - width;
            float padding = paint.getTextSize() * 0.3f, radius = paint.getTextSize() * 0.16f;
            int savedColor = paint.getColor(); Paint.Style savedStyle = paint.getStyle();
            paint.setColor(color); paint.setStyle(Paint.Style.FILL);
            target.drawRoundRect(x - padding, top, x + width + padding, bottom, radius, radius, paint);
            paint.setColor(savedColor); paint.setStyle(savedStyle);
        }
    }
}
