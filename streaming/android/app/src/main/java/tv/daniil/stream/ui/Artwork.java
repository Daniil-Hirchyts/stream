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
import tv.daniil.stream.addon.AddonClient;
import tv.daniil.stream.addon.Cancellation;

/** Optional, bounded image work. A failed poster never blocks a catalogue or playback. */
public final class Artwork implements AutoCloseable {
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
        Bitmap known = cache.get(url);
        if (known != null) { image.setImageBitmap(known); return; }
        Cancellation request = cancellation;
        workers.execute(() -> {
            try {
                byte[] bytes = client.fetch(url, 2 << 20, request, AddonClient.deadline());
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0) return;
                options.inSampleSize = 1;
                while ((long) options.outWidth * options.outHeight / options.inSampleSize / options.inSampleSize > 512L * 512) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (bitmap == null || request.isCancelled()) return;
                cache.put(url, bitmap);
                main.post(() -> { if (!request.isCancelled() && url.equals(image.getTag())) image.setImageBitmap(bitmap); });
            } catch (Exception ignored) { /* artwork is optional */ }
        });
    }
    public void cancel() { cancellation.close(); workers.getQueue().clear(); cancellation = new Cancellation(); }
    @Override public void close() { cancellation.close(); workers.shutdownNow(); client.close(); cache.evictAll(); main.removeCallbacksAndMessages(null); }
}
