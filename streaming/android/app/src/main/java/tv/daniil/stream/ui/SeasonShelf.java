package tv.daniil.stream.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import androidx.annotation.Nullable;

/** Only the episode shelf moves; the selected season and its focus stay attached. */
final class SeasonShelf extends FrameLayout {
    @Nullable private ValueAnimator motion;
    @Nullable private BitmapDrawable outgoing;

    SeasonShelf(Context context) {
        super(context); setTag("season-shelf"); setClipChildren(false); setClipToPadding(false);
    }

    void show(View content, int direction) {
        boolean animate = getChildCount() > 0 && isLaidOut() && getWidth() > 0 && getHeight() > 0
                && ValueAnimator.areAnimatorsEnabled();
        // Capture the current composite before cancelling, so a rapid reversal starts from what was on screen.
        Bitmap image = animate ? snapshot() : null;
        finishMotion(); removeAllViews(); addView(content);
        if (image == null) return;
        int width = getWidth(), height = getHeight();
        outgoing = new BitmapDrawable(getResources(), image);
        outgoing.setBounds(0, 0, width, height); getOverlay().add(outgoing);
        float travel = Tv.dp(getContext(), 24) * direction;
        content.setAlpha(0f); content.setTranslationX(travel);
        motion = ValueAnimator.ofFloat(0f, 1f);
        motion.setDuration(260); motion.setInterpolator(new PathInterpolator(.22f, 1f, .36f, 1f));
        motion.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            content.setAlpha(fraction); content.setTranslationX(travel * (1f - fraction));
            int x = Math.round(-travel * fraction);
            outgoing.setBounds(x, 0, x + width, height); outgoing.setAlpha(Math.round(255 * (1f - fraction)));
        });
        motion.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { clearImage(); content.setAlpha(1f); content.setTranslationX(0f); motion = null; }
        });
        motion.start();
    }

    @Nullable private Bitmap snapshot() {
        // Keep the transient image below about two MiB, including on 4K TVs.
        float scale = Math.min(1f, Math.min(960f / Math.max(getWidth(), getHeight()),
                (float) Math.sqrt((512f * 1024f) / ((double) getWidth() * getHeight()))));
        Bitmap image = Bitmap.createBitmap(Math.max(1, Math.round(getWidth() * scale)), Math.max(1, Math.round(getHeight() * scale)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image); canvas.scale(scale, scale); draw(canvas);
        return image;
    }
    private void clearImage() {
        if (outgoing == null) return;
        getOverlay().remove(outgoing); outgoing.getBitmap().recycle(); outgoing = null;
    }
    private void finishMotion() {
        if (motion != null) motion.cancel();
        clearImage();
    }
    @Override protected void onDetachedFromWindow() { finishMotion(); super.onDetachedFromWindow(); }
}
