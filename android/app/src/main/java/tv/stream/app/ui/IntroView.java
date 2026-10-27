package tv.stream.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import tv.stream.app.R;

/**
 * The start screen, drawn while the app really loads: drifting light behind a tilted wall of poster placeholders
 * that fill with the catalogues' own artwork as it arrives, the play mark tracing itself and the name rising
 * into place. {@link #finish} zooms through the mark and fades the whole screen away over the page that is already
 * in place beneath it.
 */
public final class IntroView extends View {
    private static final String NAME = "STREAM";
    /** The launcher mark's play symbol, in its 108-unit viewport. */
    private static final String MARK = "M40.50,42.00 L40.50,66.00 Q40.50,72.50 46.15,69.28 L67.35,57.22 Q73.00,54.00 67.35,50.78 L46.15,38.72 Q40.50,35.50 40.50,42.00 Z";
    private static final long TRACE_MS = 900, FILL_MS = 520, LETTER_MS = 420, LETTER_STAGGER = 55, EXIT_MS = 700;
    private static final int ROWS = 3, POSTERS_PER_ROW = 9;

    private final float dp;
    private final long started = SystemClock.uptimeMillis();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG), poster = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint name = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint[] lights = new Paint[3];
    private final Shader halo = new RadialGradient(56.75f, 54f, 46f, new int[]{0x55FFFFFF, 0x1AEBEBF5, 0x00000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
    private final Shader glass = new LinearGradient(0, 35.5f, 0, 72.5f, 0xB2FFFFFF, 0x5CFFFFFF, Shader.TileMode.CLAMP);
    private final Shader sheen = new LinearGradient(-9f, 0, 9f, 0,
            new int[]{0x00FFFFFF, 0x99FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
    private Shader pool;
    private final Matrix matrix = new Matrix();
    private final Path mark = new Path(), traced = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final RectF rect = new RectF();
    private final RectF markBounds = new RectF();
    private final List<Bitmap> posters = new ArrayList<>();
    private final List<Long> posterArrived = new ArrayList<>();
    private final List<BitmapShader> posterShaders = new ArrayList<>();
    private long exiting = -1;
    private Runnable gone;

    public IntroView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(context.getColor(R.color.tv_background));
        setClickable(true); setFocusable(false);
        setContentDescription("Loading " + NAME);
        int[] colours = {0x332C2C2E, 0x26202024, 0x1FEBEBF5};
        for (int i = 0; i < lights.length; i++) {
            lights[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            lights[i].setShader(new RadialGradient(0, 0, 1, colours[i], Color.TRANSPARENT, Shader.TileMode.CLAMP));
        }
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeJoin(Paint.Join.ROUND); stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setColor(Color.WHITE);
        name.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_display_semibold));
        name.setColor(context.getColor(R.color.tv_text)); name.setLetterSpacing(0.12f);
        name.setTextAlign(Paint.Align.CENTER);
        android.graphics.Path parsed = androidx.core.graphics.PathParser.createPathFromPathData(MARK);
        mark.set(parsed); mark.close();
        mark.computeBounds(markBounds, true);
        measure.setPath(mark, true);
    }

    /** Artwork from the catalogues being loaded: it takes the next place on the poster wall. */
    public void poster(Bitmap bitmap) {
        if (posters.size() >= ROWS * POSTERS_PER_ROW) return;
        // Cached posters arrive together; they still cascade onto the wall one after another.
        long now = SystemClock.uptimeMillis(), previous = posterArrived.isEmpty() ? 0 : posterArrived.get(posterArrived.size() - 1);
        posters.add(bitmap); posterArrived.add(Math.max(now, previous + 45));
        posterShaders.add(new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
    }
    /** How long the opening animation needs before the screen may leave, so it never cuts off mid-gesture. */
    /** How long the wall needs to show the posters it has been given. */
    public long postersMs() { return posterArrived.isEmpty() ? 0 : Math.max(0, posterArrived.get(posterArrived.size() - 1) + 700 - SystemClock.uptimeMillis()); }
    public static long minimumMs() { return TRACE_MS + FILL_MS + LETTER_STAGGER * NAME.length() + LETTER_MS + 500; }
    public boolean finishing() { return exiting >= 0; }
    /** Zooms through the mark and fades out; {@code done} runs once the screen is invisible. */
    public void finish(Runnable done) {
        if (exiting >= 0) return;
        gone = done;
        exiting = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        pool = new RadialGradient(width / 2f, height / 2f, Math.max(1, Math.max(width, height)) * 0.42f,
                new int[]{0xF20A0A0D, 0xB30A0A0D, 0x000A0A0D}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float t = (now - started) / 1000f;
        float exit = exiting < 0 || now < exiting ? 0f : Math.min(1f, (now - exiting) / (float) EXIT_MS);
        float out = ease(exit);
        int width = getWidth(), height = getHeight();
        float cx = width / 2f, cy = height / 2f;

        drawLights(canvas, t, width, height, 1f - out);
        drawWall(canvas, now, t, width, height, out);
        // A dark pool behind the mark and name keeps them legible over the posters.
        glow.setShader(pool);
        glow.setAlpha(Math.round(255 * (1f - out)));
        canvas.drawRect(0, 0, width, height, glow);

        float size = 132 * dp;
        float markY = cy - 40 * dp;
        drawMark(canvas, now, t, cx, markY, size * (1f + 0.9f * out), 1f - Math.min(1f, out * 1.6f));
        drawName(canvas, now, cx, markY + size * 0.5f + 34 * dp, 1f - Math.min(1f, out * 2f));

        setAlpha(1f - ease(Math.max(0f, (exit - 0.35f) / 0.65f)));
        if (exit >= 1f) {
            Runnable done = gone; gone = null;
            if (done != null) post(done);
            return;
        }
        postInvalidateOnAnimation();
    }

    /** Three soft neutral lights wandering slowly on Lissajous paths. */
    private void drawLights(Canvas canvas, float t, int width, int height, float alpha) {
        float radius = Math.max(width, height) * 0.55f;
        float[][] paths = {{0.30f, 0.35f, 0.21f, 0.17f}, {0.72f, 0.62f, 0.15f, 0.23f}, {0.55f, 0.25f, 0.27f, 0.12f}};
        for (int i = 0; i < lights.length; i++) {
            float[] p = paths[i];
            float x = width * (p[0] + 0.12f * (float) Math.sin(t * p[2] + i * 2.1f));
            float y = height * (p[1] + 0.10f * (float) Math.cos(t * p[3] + i * 1.3f));
            float r = radius * (0.85f + 0.15f * (float) Math.sin(t * 0.4f + i));
            matrix.setScale(r, r); matrix.postTranslate(x, y);
            lights[i].getShader().setLocalMatrix(matrix);
            lights[i].setAlpha(Math.round(255 * alpha));
            canvas.drawCircle(x, y, r, lights[i]);
        }
    }

    /**
     * A tilted wall of poster cards drifting sideways, row by row in alternating directions. Empty cards shimmer
     * as placeholders; each arriving poster fades into the next place.
     */
    private void drawWall(Canvas canvas, long now, float t, int width, int height, float out) {
        float cardW = 118 * dp, cardH = cardW * 1.5f, gap = 18 * dp, radius = 10 * dp;
        float rowW = POSTERS_PER_ROW * (cardW + gap);
        canvas.save();
        canvas.translate(width / 2f, height / 2f);
        canvas.rotate(-9f);
        canvas.scale(1.08f + 0.25f * out, 1.08f + 0.25f * out);
        float wallTop = -(ROWS * (cardH + gap)) / 2f;
        for (int row = 0; row < ROWS; row++) {
            float direction = row % 2 == 0 ? -1f : 1f;
            float drift = ((t * (14 + 6 * row) * dp) % rowW) * direction;
            float y = wallTop + row * (cardH + gap);
            // Two copies of the row cover the screen however far it has drifted.
            for (int copy = -2; copy <= 1; copy++) for (int col = 0; col < POSTERS_PER_ROW; col++) {
                float x = copy * rowW + col * (cardW + gap) + drift;
                if (x > width || x + cardW < -width) continue;
                rect.set(x, y, x + cardW, y + cardH);
                // Fill places so posters spread over the rows rather than completing one row first.
                int slot = col * ROWS + row;
                float shimmer = 0.5f + 0.5f * (float) Math.sin(t * 2.2f - col * 0.6f - row * 0.9f);
                fill.setShader(null);
                fill.setColor(Color.WHITE);
                fill.setAlpha(Math.round(255 * (0.035f + 0.035f * shimmer)));
                canvas.drawRoundRect(rect, radius, radius, fill);
                if (slot < posters.size() && now >= posterArrived.get(slot)) {
                    float arrived = Math.min(1f, (now - posterArrived.get(slot)) / 600f);
                    Bitmap bitmap = posters.get(slot);
                    float scale = Math.max(cardW / bitmap.getWidth(), cardH / bitmap.getHeight());
                    matrix.setScale(scale, scale);
                    matrix.postTranslate(x + (cardW - bitmap.getWidth() * scale) / 2f, y + (cardH - bitmap.getHeight() * scale) / 2f);
                    BitmapShader shader = posterShaders.get(slot);
                    shader.setLocalMatrix(matrix);
                    poster.setShader(shader);
                    poster.setAlpha(Math.round(255 * 0.42f * ease(arrived)));
                    canvas.drawRoundRect(rect, radius, radius, poster);
                }
            }
        }
        canvas.restore();
    }

    /** Glass fills the play mark, then a broad light sweeps through its entire interior. */
    private void drawMark(Canvas canvas, long now, float t, float cx, float cy, float size, float alpha) {
        if (alpha <= 0f) return;
        long age = now - started;
        float trace = ease(Math.min(1f, age / (float) TRACE_MS));
        float filled = ease(Math.max(0f, Math.min(1f, (age - TRACE_MS * 0.6f) / FILL_MS)));
        float breathe = filled * 0.03f * (float) Math.sin(t * 2.4f);
        float scale = size / 108f * (1f + breathe);
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(scale, scale);
        canvas.translate(-56.75f, -54f);

        // A halo that blooms as the glass fills.
        glow.setShader(halo);
        glow.setAlpha(Math.round(255 * alpha * filled * (0.8f + 0.2f * (float) Math.sin(t * 2.4f))));
        canvas.drawCircle(56.75f, 54f, 46f, glow);

        fill.setShader(glass);
        fill.setAlpha(Math.round(255 * alpha * filled));
        canvas.drawPath(mark, fill);

        if (age >= TRACE_MS && filled > 0f) {
            float sweep = ((age - TRACE_MS) % 3200) / 1100f;
            if (sweep <= 1f) {
                // The diagonal band's projection includes the whole path plus its width at both ends.
                // Move the shader, rather than skewing the drawing after calculating its travel.
                float from = markBounds.left + 0.35f * markBounds.top - 12f;
                float to = markBounds.right + 0.35f * markBounds.bottom + 12f;
                float x = from + (to - from) * ease(sweep);
                matrix.setSkew(-0.35f, 0); matrix.postTranslate(x, 0);
                sheen.setLocalMatrix(matrix);
                fill.setShader(sheen); fill.setAlpha(Math.round(255 * alpha * filled));
                canvas.drawPath(mark, fill);
            }
        }

        traced.reset();
        measure.getSegment(0, measure.getLength() * trace, traced, true);
        stroke.setStrokeWidth(1.6f);
        stroke.setAlpha(Math.round(255 * alpha * (1f - 0.55f * filled)));
        canvas.drawPath(traced, stroke);
        canvas.restore();
    }

    /** Shape and draw the entire word together so tracking and kerning stay consistent. */
    private void drawName(Canvas canvas, long now, float cx, float baseline, float alpha) {
        if (alpha <= 0f) return;
        name.setTextSize(28 * dp);
        long start = started + (long) (TRACE_MS * 0.7f);
        float shown = ease(Math.max(0f, Math.min(1f, (now - start) / (float) LETTER_MS)));
        name.setAlpha(Math.round(255 * alpha * shown));
        canvas.drawText(NAME, cx, baseline + (1f - shown) * 12 * dp, name);
    }

    private static float ease(float x) { float inv = 1f - x; return 1f - inv * inv * inv; }
}
