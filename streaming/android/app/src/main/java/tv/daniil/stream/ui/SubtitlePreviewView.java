package tv.daniil.stream.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.OptIn;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;

import java.util.Collections;
import tv.daniil.playback.SubtitleStyle;
import tv.daniil.stream.R;

/**
 * A TV cinema preview frame that renders sample subtitles matching the user's styling choices in real time
 * over a realistic cinematic video background.
 */
@OptIn(markerClass = UnstableApi.class)
public final class SubtitlePreviewView extends FrameLayout {
    private final SubtitleView subtitleView;
    private boolean compact = false;
    private int sizeIndex = 1;
    private int fontIndex = 0;
    private int colorIndex = 0;
    private int edgeIndex = 2;
    private int positionIndex = 1;

    public SubtitlePreviewView(Context context) {
        super(context);
        setMinimumWidth(Tv.dp(context, 280));
        setFocusable(false);
        setFocusableInTouchMode(false);

        // Cinema rounded platter
        int radius = Tv.dp(context, 10);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius);
        bg.setColor(0xFF101014);
        setBackground(bg);
        setClipToOutline(true);

        // Realistic cinematic video scene backdrop
        ImageView backdrop = new ImageView(context);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setImageResource(R.drawable.tv_subtitle_backdrop);
        backdrop.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(backdrop, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Subtle film scrim + hairline border on top of the backdrop
        View scrim = new View(context);
        GradientDrawable overlay = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0x33000000});
        overlay.setCornerRadius(radius);
        overlay.setStroke(Tv.dp(context, 1), 0x24FFFFFF);
        scrim.setBackground(overlay);
        scrim.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(scrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        subtitleView = new SubtitleView(context);
        androidx.media3.ui.DaniilSubtitleOutput.install(subtitleView);
        subtitleView.setCues(Collections.singletonList(
                new Cue.Builder().setText(context.getString(tv.daniil.playback.R.string.subtitle_sample)).build()));
        addView(subtitleView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        apply();
    }

    public void setCompact(boolean compact) {
        if (this.compact != compact) {
            this.compact = compact;
            apply();
            requestLayout();
        }
    }

    public boolean isCompact() {
        return compact;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int targetHeight = compact ? Tv.dp(getContext(), 58) : Tv.dp(getContext(), 92);
        if (heightMode != MeasureSpec.EXACTLY) {
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public void updateFrom(SharedPreferences prefs) {
        SubtitleStyle style = new SubtitleStyle(prefs);
        this.sizeIndex = style.get(SubtitleStyle.Setting.SIZE);
        this.fontIndex = style.get(SubtitleStyle.Setting.FONT);
        this.colorIndex = style.get(SubtitleStyle.Setting.COLOR);
        this.edgeIndex = style.get(SubtitleStyle.Setting.EDGE);
        this.positionIndex = style.get(SubtitleStyle.Setting.POSITION);
        apply();
    }

    public void update(int size, int font, int color, int edge, int position) {
        this.sizeIndex = size;
        this.fontIndex = font;
        this.colorIndex = color;
        this.edgeIndex = edge;
        this.positionIndex = position;
        apply();
    }

    public void setSize(int size) { this.sizeIndex = size; apply(); }
    public void setFont(int font) { this.fontIndex = font; apply(); }
    public void setColor(int color) { this.colorIndex = color; apply(); }
    public void setEdge(int edge) { this.edgeIndex = edge; apply(); }
    public void preview(SubtitleStyle.Setting setting, int index) {
        switch (setting) {
            case SIZE: setSize(index); break;
            case FONT: setFont(index); break;
            case COLOR: setColor(index); break;
            case EDGE: setEdge(index); break;
            case POSITION: setPosition(index); break;
        }
    }
    public void setPosition(int position) { this.positionIndex = position; apply(); }

    private void apply() {
        int color = SubtitleStyle.colorFor(colorIndex);
        Typeface font = SubtitleStyle.typefaceFor(fontIndex);
        int edgeType = edgeIndex == 0 ? CaptionStyleCompat.EDGE_TYPE_OUTLINE
                : edgeIndex == 1 ? CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW : CaptionStyleCompat.EDGE_TYPE_NONE;
        int box = edgeIndex == 2 ? SubtitleStyle.boxColor() : Color.TRANSPARENT;
        subtitleView.setStyle(new CaptionStyleCompat(color, box, Color.TRANSPARENT, edgeType, Color.BLACK, font));
        subtitleView.setApplyEmbeddedStyles(false);

        float sp;
        int gapDp;
        if (compact) {
            sp = sizeIndex == 0 ? 13f : sizeIndex == 1 ? 15.5f : sizeIndex == 2 ? 18f : 21f;
            gapDp = positionIndex == 0 ? 4 : positionIndex == 1 ? 10 : 18;
        } else {
            sp = sizeIndex == 0 ? 14f : sizeIndex == 1 ? 17f : sizeIndex == 2 ? 20.5f : 25f;
            gapDp = positionIndex == 0 ? 6 : positionIndex == 1 ? 14 : 26;
        }

        subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        subtitleView.setBottomPaddingFraction(0f);
        subtitleView.setPadding(0, 0, 0, Tv.dp(getContext(), gapDp));
    }
}
