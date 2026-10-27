package tv.stream.app;

import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import tv.stream.app.addon.Cancellation;

/** Bounded format discovery for signed/extensionless URLs; the first loader reuses the opened response. */
@OptIn(markerClass = UnstableApi.class)
final class HttpMediaResolver implements AutoCloseable, DataSource.Factory {
    final Uri uri;
    final String mime;
    private final DataSource.Factory factory;
    private final AtomicReference<DataSource> pending;
    private final byte[] prefix;
    private final long length;
    private HttpMediaResolver(Uri uri, String mime, DataSource.Factory factory, DataSource opened, byte[] prefix, long length) {
        this.uri = uri; this.mime = mime; this.factory = factory; pending = new AtomicReference<>(opened); this.prefix = prefix; this.length = length;
    }
    static HttpMediaResolver resolve(String address, Map<String, String> headers, Cancellation scope) throws IOException {
        Uri uri = Uri.parse(address);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(java.util.Locale.ROOT);
        Map<String, String> properties = new java.util.HashMap<>(headers);
        String agent = "Stream/0.1";
        for (String key : new java.util.ArrayList<>(properties.keySet())) if (key.equalsIgnoreCase("User-Agent")) agent = properties.remove(key);
        DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory().setUserAgent(agent)
                .setDefaultRequestProperties(properties).setConnectTimeoutMs(15000).setReadTimeoutMs(30000);
        if (path.matches(".*\\.(mp4|m4v|mkv|webm|avi|ts|m2ts|mov|mpg|mpeg|m3u8|mpd)$"))
            return new HttpMediaResolver(uri, path.endsWith(".m3u8") ? MimeTypes.APPLICATION_M3U8 : path.endsWith(".mpd") ? MimeTypes.APPLICATION_MPD : null,
                    factory, null, new byte[0], C.LENGTH_UNSET);
        HttpProbe opened = new HttpProbe(uri, scope);
        boolean success = false;
        try {
            opened.connect(properties, agent);
            long length = opened.length();
            String mime = null;
            for (Map.Entry<String, List<String>> header : opened.getResponseHeaders().entrySet()) if ("Content-Type".equalsIgnoreCase(header.getKey())) {
                for (String value : header.getValue()) {
                    String type = value.toLowerCase(java.util.Locale.ROOT);
                    if (type.contains("mpegurl")) mime = MimeTypes.APPLICATION_M3U8;
                    else if (type.contains("dash+xml")) mime = MimeTypes.APPLICATION_MPD;
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (mime == null) {
                byte[] buffer = new byte[4096];
                while (bytes.size() < 32768) {
                    int count = opened.sniff(buffer, Math.min(buffer.length, 32768 - bytes.size()));
                    if (count < 0) break;
                    if (count == 0) continue;
                    bytes.write(buffer, 0, count);
                    String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8).replace("\ufeff", "").trim();
                    if (text.startsWith("#EXTM3U")) { mime = MimeTypes.APPLICATION_M3U8; break; }
                    if (text.matches("(?s)(?:<\\?xml.*?\\?>\\s*)?<MPD(?:\\s|>).*")) { mime = MimeTypes.APPLICATION_MPD; break; }
                    // Binary containers and ordinary bodies need no further probing.
                    if (!text.startsWith("<") && !text.startsWith("#") || bytes.size() >= 4096 && !text.startsWith("<?xml")) break;
                }
            }
            opened.finishProbe();
            Uri resolved = opened.getUri();
            HttpMediaResolver result = new HttpMediaResolver(resolved == null ? uri : resolved, mime, factory, opened, bytes.toByteArray(), length);
            success = true; return result;
        } finally {
            if (!success) try { opened.close(); } catch (IOException ignored) { /* Preserve the probe failure/status. */ }
        }
    }
    @Override public DataSource createDataSource() { return new Reused(); }
    @Override public void close() { DataSource opened = pending.getAndSet(null); if (opened != null) try { opened.close(); } catch (IOException ignored) {} }
    private final class Reused implements DataSource {
        DataSource source;
        int offset;
        byte[] buffered = new byte[0];
        final java.util.ArrayList<TransferListener> listeners = new java.util.ArrayList<>();
        @Override public void addTransferListener(TransferListener listener) { if (!listeners.contains(listener)) listeners.add(listener); if (source != null) source.addTransferListener(listener); }
        @Override public long open(DataSpec spec) throws IOException {
            offset = 0; buffered = new byte[0];
            source = spec.position == 0 && spec.length == C.LENGTH_UNSET && spec.httpMethod == DataSpec.HTTP_METHOD_GET
                    && spec.httpBody == null && uri.equals(spec.uri) ? pending.getAndSet(null) : null;
            if (source != null) { buffered = prefix; for (TransferListener listener : listeners) source.addTransferListener(listener); source.open(spec); return length; }
            HttpMediaResolver.this.close(); source = factory.createDataSource();
            for (TransferListener listener : listeners) source.addTransferListener(listener);
            return source.open(spec);
        }
        @Override public int read(byte[] target, int start, int count) throws IOException {
            if (count == 0) return 0;
            if (offset < buffered.length) { int copied = Math.min(count, buffered.length - offset); System.arraycopy(buffered, offset, target, start, copied); offset += copied; return copied; }
            return source.read(target, start, count);
        }
        @Override public Uri getUri() { return source == null ? null : source.getUri(); }
        @Override public Map<String, List<String>> getResponseHeaders() { return source == null ? java.util.Collections.emptyMap() : source.getResponseHeaders(); }
        @Override public void close() throws IOException { DataSource previous = source; source = null; buffered = new byte[0]; offset = 0; if (previous != null) previous.close(); }
    }
}
