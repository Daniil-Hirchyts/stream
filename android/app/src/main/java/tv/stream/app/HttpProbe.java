package tv.stream.app;

import android.net.Uri;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import tv.stream.app.addon.Cancellation;

/** The probe owns its connection until handoff; Media3 owns reads/close afterward. TLS uses platform defaults. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class HttpProbe extends BaseDataSource {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "media-probe-timeout"); thread.setDaemon(true); return thread;
    });
    private volatile HttpURLConnection connection;
    private InputStream input;
    private Uri uri;
    private Map<String, List<String>> headers = Collections.emptyMap();
    private long remaining = C.LENGTH_UNSET;
    private boolean transferred;
    private int prefetched;
    final long until = SystemClock.elapsedRealtime() + 2000;
    private final AtomicBoolean probing = new AtomicBoolean(true);
    private final Runnable abort = () -> { HttpURLConnection active = connection; if (active != null) active.disconnect(); };
    private final java.util.concurrent.ScheduledFuture<?> deadline;
    private final Cancellation scope;

    HttpProbe(Uri uri, Cancellation scope) {
        super(true); this.uri = uri; this.scope = scope;
        scope.add(abort);
        deadline = TIMER.schedule(() -> { if (probing.compareAndSet(true, false)) abort.run(); }, 2, TimeUnit.SECONDS);
    }
    void connect(Map<String, String> properties, String userAgent) throws IOException {
        URL url = new URL(uri.toString());
        DataSpec spec = new DataSpec.Builder().setUri(uri).build();
        for (int redirects = 0; redirects <= 5; redirects++) {
            scope.check(); checkDeadline();
            HttpURLConnection active = (HttpURLConnection) url.openConnection(); connection = active;
            int left = (int) Math.max(1, until - SystemClock.elapsedRealtime());
            active.setConnectTimeout(left);
            // Android's HttpURLConnection captures this when it connects; changing it at handoff
            // does not update the existing socket. The cancellable deadline bounds discovery.
            active.setReadTimeout(30000); active.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> property : properties.entrySet()) active.setRequestProperty(property.getKey(), property.getValue());
            active.setRequestProperty("User-Agent", userAgent); active.setRequestProperty("Accept-Encoding", "identity");
            int status = active.getResponseCode();
            scope.check(); checkDeadline();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = active.getHeaderField("Location"); active.disconnect();
                if (location == null) throw new IOException("Media redirect has no destination");
                URL next = new URL(url, location);
                // Matches the regular Media3 source's default cross-protocol redirect policy.
                if (!url.getProtocol().equalsIgnoreCase(next.getProtocol())) throw new IOException("Media redirect changed protocol");
                url = next; continue;
            }
            if (status < 200 || status > 299) throw new HttpDataSource.InvalidResponseCodeException(
                    status, active.getResponseMessage(), null, active.getHeaderFields(), spec, new byte[0]);
            uri = Uri.parse(url.toString());
            Map<String, List<String>> response = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, List<String>> header : active.getHeaderFields().entrySet())
                if (header.getKey() != null && header.getValue() != null) response.put(header.getKey(), Collections.unmodifiableList(new java.util.ArrayList<>(header.getValue())));
            headers = Collections.unmodifiableMap(response);
            remaining = active.getContentLengthLong(); input = active.getInputStream(); return;
        }
        throw new IOException("Too many media redirects");
    }
    void finishProbe() throws IOException {
        scope.check(); checkDeadline();
        if (!probing.compareAndSet(true, false)) throw new java.net.SocketTimeoutException("Media format probe timed out");
        // Keep the existing socket and its normal read timeout; only discovery loses its deadline.
        deadline.cancel(false); scope.remove(abort);
    }
    private void checkDeadline() throws java.net.SocketTimeoutException {
        if (!probing.get() || SystemClock.elapsedRealtime() >= until) throw new java.net.SocketTimeoutException("Media format probe timed out");
    }
    int sniff(byte[] target, int count) throws IOException { checkDeadline(); int read = readInput(target, 0, count); if (read > 0) prefetched += read; return read; }
    long length() { return remaining; }
    @Override public long open(DataSpec spec) {
        transferInitializing(spec); transferred = true; transferStarted(spec); if (prefetched > 0) bytesTransferred(prefetched); return remaining;
    }
    @Override public int read(byte[] target, int offset, int count) throws IOException {
        if (count == 0) return 0;
        int read = readInput(target, offset, count); if (read > 0) bytesTransferred(read); return read;
    }
    private int readInput(byte[] target, int offset, int count) throws IOException {
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        int read = input.read(target, offset, (int) (remaining < 0 ? count : Math.min(count, remaining)));
        if (read < 0 && remaining > 0) throw new EOFException("Media response ended early");
        if (read > 0 && remaining >= 0) remaining -= read;
        return read;
    }
    @Override public Uri getUri() { return uri; }
    @Override public Map<String, List<String>> getResponseHeaders() { return headers; }
    @Override public void close() throws IOException {
        probing.set(false); deadline.cancel(false); scope.remove(abort); abort.run();
        try { if (input != null) input.close(); }
        finally { input = null; connection = null; if (transferred) { transferred = false; transferEnded(); } }
    }
}
