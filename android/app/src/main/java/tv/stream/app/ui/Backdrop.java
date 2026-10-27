package tv.stream.app.ui;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import androidx.annotation.Nullable;
import java.util.Objects;
import java.util.function.Consumer;
import tv.stream.app.addon.Title;

/**
 * Full-screen artwork behind the browse screens. Changes wait briefly so scrolling past cards doesn't
 * download every backdrop, then cross-fade between two image layers.
 */
public final class Backdrop {
    private static final long SETTLE_MS = 280, FADE_MS = 420;
    private final Artwork artwork;
    private final View scrim;
    /** The softened (lower-half blurred) copy of the current artwork, cross-faded above the sharp layers. */
    private final ImageView soft;
    /** {@code top} is drawn above {@code bottom}; the newest image alternates between them. */
    private final ImageView top, bottom;
    private boolean topCurrent;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private String shown, wanted;
    private int generation;

    public Backdrop(Artwork artwork, ImageView top, ImageView bottom, ImageView soft, View scrim) {
        this.artwork = artwork; this.top = top; this.bottom = bottom; this.soft = soft; this.scrim = scrim;
    }

    @Nullable public static String art(@Nullable Title title) {
        if (title == null) return null;
        return title.background != null ? title.background : title.poster;
    }

    /** Shows the title's artwork, sharp or blurred, or the plain canvas for null. */
    public void show(@Nullable Title title, boolean immediately, boolean blurred) {
        String url = art(title), key = url == null ? null : blurred ? url + "#blurred" : url;
        if (Objects.equals(key, wanted)) return;
        wanted = key;
        int mine = ++generation;
        main.removeCallbacksAndMessages(null);
        if (url == null) { clear(); return; }
        Consumer<Bitmap> done = bitmap -> { if (mine == generation) swap(key, bitmap); };
        main.postDelayed(() -> {
            if (blurred) { artwork.blurred(url, done); soften(null); return; }
            artwork.fetch(url, Artwork.BACKDROP, false, done);
            artwork.softened(url, bitmap -> { if (mine == generation) soften(bitmap); });
        }, immediately ? 0 : SETTLE_MS);
    }

    private void swap(String url, Bitmap bitmap) {
        if (url.equals(shown)) return;
        boolean wasVisible = shown != null;
        shown = url;
        top.animate().cancel(); bottom.animate().cancel();
        topCurrent = !topCurrent || !wasVisible;
        if (topCurrent) {
            // Fade the new image in above the old one.
            top.setImageBitmap(bitmap); top.setAlpha(0f); top.animate().alpha(1f).setDuration(FADE_MS).start();
            if (!wasVisible) bottom.setAlpha(0f);
        } else {
            // Put the new image underneath, then fade the old one away.
            bottom.setImageBitmap(bitmap); bottom.setAlpha(1f); top.animate().alpha(0f).setDuration(FADE_MS).start();
        }
        scrim.animate().cancel(); scrim.animate().alpha(1f).setDuration(FADE_MS).start();
    }

    /** Cross-fades the softened layer to {@code bitmap}, or away for none. */
    private void soften(@Nullable Bitmap bitmap) {
        android.graphics.drawable.Drawable before = soft.getDrawable() instanceof android.graphics.drawable.TransitionDrawable
                ? ((android.graphics.drawable.TransitionDrawable) soft.getDrawable()).getDrawable(1) : soft.getDrawable();
        if (before == null) before = new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT);
        android.graphics.drawable.Drawable after = bitmap == null ? new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                : new android.graphics.drawable.BitmapDrawable(soft.getResources(), bitmap);
        android.graphics.drawable.TransitionDrawable fade = new android.graphics.drawable.TransitionDrawable(new android.graphics.drawable.Drawable[]{before, after});
        fade.setCrossFadeEnabled(true);
        soft.setImageDrawable(fade);
        fade.startTransition((int) FADE_MS);
    }

    private void clear() {
        shown = null;
        soften(null);
        for (View layer : new View[]{top, bottom, scrim}) { layer.animate().cancel(); layer.animate().alpha(0f).setDuration(FADE_MS).start(); }
    }

    public void release() { main.removeCallbacksAndMessages(null); generation++; }
}
