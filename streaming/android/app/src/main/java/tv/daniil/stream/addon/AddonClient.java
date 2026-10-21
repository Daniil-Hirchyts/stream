package tv.daniil.stream.addon;

import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLException;

/** Blocking worker-thread transport. Platform TLS/hostname verification remains untouched. */
public final class AddonClient implements AutoCloseable {
    public static final int MAX_JSON_BYTES = 2 << 20;
    public static final long REQUEST_MS = 10_000, DISCOVERY_MS = 15_000;
    private final Semaphore requests = new Semaphore(4, true);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    public static long deadline() { return SystemClock.elapsedRealtime() + DISCOVERY_MS; }

    public JSONObject json(String address, Cancellation cancellation, long aggregateDeadline) throws AddonException {
        byte[] bytes = fetch(address, MAX_JSON_BYTES, cancellation, aggregateDeadline);
        try { return new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)); }
        catch (JSONException | StackOverflowError e) {
            throw new AddonException(AddonException.Kind.MALFORMED, "The add-on returned invalid JSON");
        }
    }

    public byte[] fetch(String address, int limit, Cancellation cancellation, long aggregateDeadline) throws AddonException {
        AddonUrl.web(address);
        long end = Math.min(aggregateDeadline, SystemClock.elapsedRealtime() + REQUEST_MS);
        boolean acquired = false;
        AtomicBoolean timedOut = new AtomicBoolean();
        try {
            while (!acquired) {
                cancellation.check();
                long left = end - SystemClock.elapsedRealtime();
                if (left <= 0) throw new SocketTimeoutException();
                acquired = requests.tryAcquire(Math.min(100, left), TimeUnit.MILLISECONDS);
            }
            String current = address;
            for (int redirects = 0; redirects <= 5; redirects++) {
                cancellation.check();
                long left = end - SystemClock.elapsedRealtime();
                if (left <= 0) throw new SocketTimeoutException();
                HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout((int) left);
                connection.setReadTimeout((int) left);
                connection.setRequestProperty("Accept", "application/json, application/x-bittorrent");
                connection.setRequestProperty("Accept-Encoding", "gzip");
                Runnable abort = connection::disconnect;
                cancellation.add(abort);
                ScheduledFuture<?> timeout = timer.schedule(() -> { timedOut.set(true); connection.disconnect(); }, left, TimeUnit.MILLISECONDS);
                try {
                    int status = connection.getResponseCode();
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) throw new AddonException(AddonException.Kind.HTTP, "Redirect without an address", status, null);
                        String target = new URL(new URL(current), location).toExternalForm();
                        java.net.URI targetUri = AddonUrl.web(target);
                        if ("https".equalsIgnoreCase(AddonUrl.web(current).getScheme())
                                && !"https".equalsIgnoreCase(targetUri.getScheme())) {
                            throw new AddonException(AddonException.Kind.INVALID_URL, "Refused an insecure redirect");
                        }
                        current = target;
                        continue;
                    }
                    if (status < 200 || status >= 300) throw new AddonException(AddonException.Kind.HTTP, "Add-on HTTP " + status, status, null);
                    if (connection.getContentLengthLong() > limit) throw tooLarge();
                    try (InputStream raw = connection.getInputStream();
                         InputStream in = "gzip".equalsIgnoreCase(connection.getContentEncoding()) ? new GZIPInputStream(raw) : raw;
                         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192];
                        for (int n; (n = in.read(buffer)) != -1;) {
                            cancellation.check();
                            if (SystemClock.elapsedRealtime() >= end || timedOut.get()) throw new SocketTimeoutException();
                            if (out.size() + n > limit) throw tooLarge();
                            out.write(buffer, 0, n);
                        }
                        cancellation.check();
                        if (timedOut.get()) throw new SocketTimeoutException();
                        return out.toByteArray();
                    }
                } finally {
                    timeout.cancel(false);
                    cancellation.remove(abort);
                    connection.disconnect();
                }
            }
            throw new AddonException(AddonException.Kind.HTTP, "Too many add-on redirects");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AddonException(AddonException.Kind.CANCELLED, "Request cancelled");
        } catch (IOException e) {
            cancellation.check();
            if (e instanceof AddonException) throw (AddonException) e;
            if (e instanceof SSLException) throw new AddonException(AddonException.Kind.CERTIFICATE, "The server certificate could not be verified");
            if (e instanceof SocketTimeoutException || timedOut.get()) throw new AddonException(AddonException.Kind.TIMEOUT, "The add-on timed out");
            // Do not expose exception messages/causes: they may contain credential-bearing URLs.
            throw new AddonException(AddonException.Kind.NETWORK, "The add-on could not be reached");
        } finally { if (acquired) requests.release(); }
    }

    private static AddonException tooLarge() { return new AddonException(AddonException.Kind.MALFORMED, "The add-on response is too large"); }
    @Override public void close() { timer.shutdownNow(); }
}
