package tv.daniil.stream.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import androidx.annotation.Nullable;

/** Layered, soft pools of colour; each category has its own hue, light position and direction. */
final class CategoryBackground extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int index;
    private Shader base, light, bloom;
    CategoryBackground(int index) { this.index = index; }
    private int color(float hue, float saturation, float brightness) {
        return Color.HSVToColor(new float[]{(hue % 360 + 360) % 360, saturation, brightness});
    }
    @Override protected void onBoundsChange(Rect bounds) {
        float w = bounds.width(), h = bounds.height();
        if (w <= 0 || h <= 0) return;
        float hue = (218 + index * 137.508f) % 360;
        boolean reverse = index % 2 == 1;
        base = new LinearGradient(0, reverse ? h : 0, w, reverse ? 0 : h,
                new int[]{color(hue, .55f, .43f), color(hue + 38, .62f, .19f)}, null, Shader.TileMode.CLAMP);
        light = new RadialGradient(w * (index % 3 == 0 ? .18f : .82f), h * .05f, w * .85f,
                new int[]{color(hue - 28, .48f, .67f), Color.TRANSPARENT}, null, Shader.TileMode.CLAMP);
        bloom = new RadialGradient(w * (index % 3 == 0 ? .95f : .1f), h * 1.1f, w * .68f,
                new int[]{color(hue + 65, .58f, .44f), Color.TRANSPARENT}, null, Shader.TileMode.CLAMP);
    }
    @Override public void draw(Canvas canvas) {
        if (base == null) return;
        paint.setShader(base); canvas.drawRect(getBounds(), paint);
        paint.setShader(light); canvas.drawRect(getBounds(), paint);
        paint.setShader(bloom); canvas.drawRect(getBounds(), paint);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(@Nullable ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
