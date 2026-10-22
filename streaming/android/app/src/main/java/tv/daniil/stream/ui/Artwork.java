package tv.daniil.stream.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import tv.daniil.stream.addon.AddonClient;
import tv.daniil.stream.addon.Cancellation;

/**
 * Optional, bounded image work: 8 MiB of decoded bitmaps in memory, and the downloaded bytes in a bounded disk cache so
 * scrolling back or reopening a page never downloads artwork again. A failed image never blocks a catalogue or playback.
 */
public final class Artwork implements AutoCloseable {
    /** Posters and thumbnails, decoded near their on-screen size. */
    public static final int THUMBNAIL = 440;
    /** Logos keep a little more detail: they are drawn wider than posters. */
    public static final int LOGO = 512;
    /** Full-screen backdrops: soft at TV distance, and a quarter of the memory of 1080p. */
    public static final int BACKDROP = 1280;
    /** Blurred backdrops are rendered tiny and scaled up: the scaling itself is most of the blur. */
    private static final int BLURRED = 96;
    private static final long DISK_LIMIT = 40L << 20, DISK_TRIM = 30L << 20;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 << 20) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final AddonClient client = new AddonClient();
    @Nullable private final File disk;
    private long diskBytes = -1;
    private Cancellation cancellation = new Cancellation();
    private final Handler main = new Handler(Looper.getMainLooper());

    public Artwork() { this(null); }
    /** @param disk this app's private cache directory for downloaded artwork, or null for memory only */
    public Artwork(@Nullable File disk) { this.disk = disk; }

    public void load(ImageView image, String url) {
        image.setImageDrawable(null); image.setTag(url);
        if (url == null) return;
        fetch(url, THUMBNAIL, false, bitmap -> { if (url.equals(image.getTag())) image.setImageBitmap(bitmap); });
    }

    /**
     * Decodes {@code url} no larger than {@code maxSide} pixels and calls {@code done} on the main thread.
     * Opaque artwork uses RGB_565; logos keep their transparency and lose transparent margins, so they align with text.
     */
    public void fetch(String url, int maxSide, boolean transparent, Consumer<Bitmap> done) { fetch(url, maxSide, transparent, false, done); }

    /** A heavily blurred version, for artwork behind lists (Sources). Computed once per image on a worker. */
    public void blurred(String url, Consumer<Bitmap> done) { fetch(url, BLURRED, false, true, done); }

    private void fetch(String url, int maxSide, boolean transparent, boolean blur, Consumer<Bitmap> done) {
        String key = url + "@" + maxSide + (blur ? "@blur" : "");
        Bitmap known = cache.get(key);
        if (known != null) { done.accept(known); return; }
        Cancellation request = cancellation;
        workers.execute(() -> {
            try {
                byte[] bytes = bytes(url, request);
                if (bytes == null || request.isCancelled()) return;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0) return;
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > maxSide * 2) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                options.inPreferredConfig = transparent || blur ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
                Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (decoded == null || request.isCancelled()) return;
                Bitmap bitmap = fit(decoded, maxSide);
                if (transparent) bitmap = trim(bitmap);
                if (blur) bitmap = blur(bitmap);
                Bitmap ready = bitmap;
                cache.put(key, ready);
                main.post(() -> { if (!request.isCancelled()) done.accept(ready); });
            } catch (Exception ignored) { /* artwork is optional */ }
        });
    }

    /** The image's bytes from the disk cache, else downloaded and saved there. */
    @Nullable private byte[] bytes(String url, Cancellation request) throws Exception {
        File file = disk == null ? null : new File(disk, name(url));
        if (file != null && file.isFile()) {
            try { byte[] saved = Files.readAllBytes(file.toPath()); file.setLastModified(System.currentTimeMillis()); return saved; }
            catch (Exception damaged) { file.delete(); }
        }
        byte[] bytes = client.fetch(url, 4 << 20, request, AddonClient.deadline());
        if (file != null && bytes.length > 0) store(file, bytes);
        return bytes;
    }

    private synchronized void store(File file, byte[] bytes) {
        try {
            if (!disk.isDirectory() && !disk.mkdirs()) return;
            File partial = new File(disk, file.getName() + ".part");
            try (FileOutputStream out = new FileOutputStream(partial)) { out.write(bytes); }
            if (!partial.renameTo(file)) { partial.delete(); return; }
            if (diskBytes < 0) { diskBytes = 0; File[] all = disk.listFiles(); if (all != null) for (File f : all) diskBytes += f.length(); }
            else diskBytes += bytes.length;
            if (diskBytes <= DISK_LIMIT) return;
            // Least recently shown first, down to the trim size.
            File[] all = disk.listFiles();
            if (all == null) return;
            Arrays.sort(all, Comparator.comparingLong(File::lastModified));
            for (File old : all) { if (diskBytes <= DISK_TRIM) break; long size = old.length(); if (old.delete()) diskBytes -= size; }
        } catch (Exception ignored) { /* the cache is optional */ }
    }

    private static String name(String url) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 16; i++) out.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
        return out.toString();
    }

    private static Bitmap fit(Bitmap source, int maxSide) {
        int side = Math.max(source.getWidth(), source.getHeight());
        if (side <= maxSide) return source;
        float scale = maxSide / (float) side;
        Bitmap scaled = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)), Math.max(1, Math.round(source.getHeight() * scale)), true);
        if (scaled != source) source.recycle();
        return scaled;
    }

    /** Crops fully transparent margins (logo files often centre a small mark in a wide canvas). */
    static Bitmap trim(Bitmap source) {
        int width = source.getWidth(), height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        int left = width, right = -1, top = height, bottom = -1;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if ((pixels[y * width + x] >>> 24) > 12) { left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y); }
        }
        if (right < 0 || (left == 0 && top == 0 && right == width - 1 && bottom == height - 1)) return source;
        Bitmap cropped = Bitmap.createBitmap(source, left, top, right - left + 1, bottom - top + 1);
        if (cropped != source) source.recycle();
        return cropped;
    }

    /** Three box-blur passes over a tiny bitmap approximate a wide Gaussian blur once it is scaled to the screen. */
    static Bitmap blur(Bitmap source) {
        int width = source.getWidth(), height = source.getHeight(), radius = Math.max(2, Math.min(width, height) / 12);
        int[] pixels = new int[width * height], buffer = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int pass = 0; pass < 3; pass++) { box(pixels, buffer, width, height, radius, true); box(buffer, pixels, width, height, radius, false); }
        Bitmap out = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        source.recycle();
        return out;
    }
    private static void box(int[] in, int[] out, int width, int height, int radius, boolean horizontal) {
        int lines = horizontal ? height : width, length = horizontal ? width : height;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < length; i++) {
                int r = 0, g = 0, b = 0, n = 0;
                for (int k = Math.max(0, i - radius); k <= Math.min(length - 1, i + radius); k++) {
                    int p = in[horizontal ? line * width + k : k * width + line];
                    r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; n++;
                }
                out[horizontal ? line * width + i : i * width + line] = 0xFF000000 | (r / n) << 16 | (g / n) << 8 | (b / n);
            }
        }
    }

    public void cancel() { cancellation.close(); workers.getQueue().clear(); cancellation = new Cancellation(); }
    @Override public void close() { cancellation.close(); workers.shutdownNow(); client.close(); cache.evictAll(); main.removeCallbacksAndMessages(null); }
}
