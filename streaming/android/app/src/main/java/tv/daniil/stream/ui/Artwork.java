package tv.daniil.stream.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import tv.daniil.stream.addon.AddonClient;
import tv.daniil.stream.addon.Cancellation;

/** Optional, bounded image work (8 MiB decoded). A failed image never blocks a catalogue or playback. */
public final class Artwork implements AutoCloseable {
    /** Posters and thumbnails. */
    public static final int THUMBNAIL = 512;
    /** Full-screen backdrops: soft at TV distance, and a quarter of the memory of 1080p. */
    public static final int BACKDROP = 1280;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 << 20) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final AddonClient client = new AddonClient();
    private Cancellation cancellation = new Cancellation();
    private final Handler main = new Handler(Looper.getMainLooper());

    public void load(ImageView image, String url) {
        image.setImageDrawable(null); image.setTag(url);
        if (url == null) return;
        fetch(url, THUMBNAIL, false, bitmap -> { if (url.equals(image.getTag())) image.setImageBitmap(bitmap); });
    }

    /**
     * Decodes {@code url} no larger than {@code maxSide} pixels and calls {@code done} on the main thread.
     * Opaque artwork uses RGB_565; logos keep their transparency.
     */
    public void fetch(String url, int maxSide, boolean transparent, Consumer<Bitmap> done) {
        String key = url + "@" + maxSide;
        Bitmap known = cache.get(key);
        if (known != null) { done.accept(known); return; }
        Cancellation request = cancellation;
        workers.execute(() -> {
            try {
                byte[] bytes = client.fetch(url, 4 << 20, request, AddonClient.deadline());
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0) return;
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > maxSide * 2) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                options.inPreferredConfig = transparent ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
                Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (decoded == null || request.isCancelled()) return;
                Bitmap bitmap = fit(decoded, maxSide);
                cache.put(key, bitmap);
                main.post(() -> { if (!request.isCancelled()) done.accept(bitmap); });
            } catch (Exception ignored) { /* artwork is optional */ }
        });
    }

    private static Bitmap fit(Bitmap source, int maxSide) {
        int side = Math.max(source.getWidth(), source.getHeight());
        if (side <= maxSide) return source;
        float scale = maxSide / (float) side;
        Bitmap scaled = Bitmap.createScaledBitmap(source, Math.round(source.getWidth() * scale), Math.round(source.getHeight() * scale), true);
        if (scaled != source) source.recycle();
        return scaled;
    }

    public void cancel() { cancellation.close(); workers.getQueue().clear(); cancellation = new Cancellation(); }
    @Override public void close() { cancellation.close(); workers.shutdownNow(); client.close(); cache.evictAll(); main.removeCallbacksAndMessages(null); }
}
