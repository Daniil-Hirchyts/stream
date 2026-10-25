package tv.daniil.stream.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import tv.daniil.stream.R;

/** Two independent labels crossfade, so a focus change never replaces visible text in place. */
final class FocusCaption extends FrameLayout {
    private final TextView[] labels = new TextView[2];
    private int active = -1, version;
    private String target = "";

    FocusCaption(Context context) {
        super(context); setTag("action-caption"); setClipChildren(false); setClipToPadding(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setLayoutParams(new android.widget.LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Tv.dp(context, 28)));
        for (int i = 0; i < labels.length; i++) {
            TextView label = Tv.oneLine(Tv.text(context, "", 12.5f, R.color.tv_text_secondary));
            label.setGravity(Gravity.CENTER); label.setAlpha(0f);
            labels[i] = label; addView(label, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
    }
    void show(String text, View anchor, HorizontalScrollView scroll) {
        if (text.equals(target)) return;
        target = text; int mine = ++version;
        if (active >= 0) fadeOut(labels[active]);
        active = (active + 1) % labels.length;
        TextView label = labels[active]; label.animate().cancel(); label.setText(text);
        int width = Math.max(Tv.dp(getContext(), 160), (int) Math.ceil(label.getPaint().measureText(text)) + Tv.dp(getContext(), 12));
        label.setLayoutParams(new LayoutParams(width, LayoutParams.WRAP_CONTENT));
        label.setAlpha(0f); label.setTranslationY(Tv.dp(getContext(), 4));
        post(() -> {
            if (mine != version) return;
            float x = anchor.getLeft() + anchor.getWidth() / 2f - scroll.getScrollX() - width / 2f;
            label.setTranslationX(Math.max(0, Math.min(x, Math.max(0, getWidth() - width))));
            label.animate().alpha(1f).translationY(0).setStartDelay(60).setDuration(180).start();
        });
    }
    void hide(String text) {
        if (!text.equals(target)) return;
        target = ""; version++;
        if (active >= 0) fadeOut(labels[active]);
    }
    private void fadeOut(TextView label) {
        label.animate().cancel(); label.animate().alpha(0f).translationY(-Tv.dp(getContext(), 3))
                .setStartDelay(0).setDuration(120).start();
    }
}
