package tv.daniil.playback;

import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Fetches external subtitle files before playback starts. Media3 stops the whole video when a side-loaded file
 * fails, so a missing, expired or oversized file is skipped here instead. Files saved in a legacy single-byte
 * encoding, common for Cyrillic SRT, are converted to UTF-8.
 */
public final class SubtitleDownloader {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final long DEADLINE_MS = 8000;
    private static final Pattern SRT = Pattern.compile("^\\s*\\d+\\s*\\R\\s*\\d{1,2}:\\d{2}:\\d{2},\\d{1,3}\\s*-->");
    private static final Pattern DOTTED_CUES = Pattern.compile("\\d{2}:\\d{2}\\.\\d{3}\\s*-->");

    /** One subtitle file; empty strings mean unknown. The content decides the format when the type is wrong. */
    public static final class Source {
        public final String url, mime, language, label;

        public Source(String url, String mime, String language, String label) {
            this.url = url;
            this.mime = mime;
            this.language = language;
            this.label = label;
        }

        Source(JSONObject json) {
            this(json.optString("url"), json.optString("mime"), json.optString("language"), json.optString("label"));
        }
    }

    private static final class Text {
        final String mime, body;

        Text(String mime, String body) {
            this.mime = mime;
            this.body = body;
        }
    }

    private SubtitleDownloader() {}

    /** Reads {@code [{"url", "mime", "language", "label"}…]}, the format the Chrome extension sends. */
    public static List<Source> parse(@Nullable String json) throws JSONException {
        List<Source> sources = new ArrayList<>();
        if (json == null || json.isEmpty()) return sources;
        JSONArray items = new JSONArray(json);
        for (int i = 0; i < items.length(); i++) sources.add(new Source(items.getJSONObject(i)));
        return sources;
    }

    /** Blocks for at most eight seconds and returns the files that arrived, in their original order. */
    static List<MediaItem.SubtitleConfiguration> download(File directory, List<Source> sources,
            Map<String, String> headers) {
        File[] old = directory.listFiles();
        if (old != null) for (File file : old) file.delete();
        directory.mkdirs();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, sources.size()));
        java.util.concurrent.atomic.AtomicBoolean accepting = new java.util.concurrent.atomic.AtomicBoolean(true);
        List<Future<MediaItem.SubtitleConfiguration>> pending = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            int index = i;
            pending.add(pool.submit(() -> fetch(directory, index, sources.get(index), headers, accepting)));
        }
        pool.shutdown();
        long deadline = SystemClock.elapsedRealtime() + DEADLINE_MS;
        List<MediaItem.SubtitleConfiguration> result = new ArrayList<>();
        for (Future<MediaItem.SubtitleConfiguration> future : pending) {
            try {
                MediaItem.SubtitleConfiguration subtitle =
                        future.get(Math.max(0, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
                if (subtitle != null) result.add(subtitle);
            } catch (Exception skipped) {
                future.cancel(true);
            }
        }
        pool.shutdownNow();
        // A timed-out HTTP worker can finish later. It must never recreate a retired session's directory.
        synchronized (directory) { accepting.set(false); }
        return result;
    }

    @Nullable private static MediaItem.SubtitleConfiguration fetch(File directory, int index, Source source,
            Map<String, String> headers, java.util.concurrent.atomic.AtomicBoolean accepting) throws IOException {
        byte[] bytes = get(source.url, headers);
        if (bytes == null) return null;
        Text text = identify(decode(bytes), source.mime);
        if (text == null) return null;
        File file = new File(directory, "subtitle-" + index + extension(text.mime));
        synchronized (directory) {
            if (!accepting.get()) return null;
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(text.body.getBytes(StandardCharsets.UTF_8));
            }
        }
        return new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                .setId(SubtitleTracks.SIDELOADED + index)
                .setMimeType(text.mime)
                .setLanguage(source.language.isEmpty() ? null : source.language)
                .setLabel(source.label.isEmpty() ? null : source.label)
                .build();
    }

    @Nullable private static byte[] get(String address, Map<String, String> headers) throws IOException {
        URL url = new URL(address);
        // HttpURLConnection won't follow a redirect between http and https on its own.
        for (int redirects = 0; redirects < 5; redirects++) {
            if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) return null;
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(6000);
                connection.setInstanceFollowRedirects(false);
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
                int status = connection.getResponseCode();
                String location = connection.getHeaderField("Location");
                if (status >= 300 && status < 400 && location != null) {
                    url = new URL(url, location);
                    continue;
                }
                if (status < 200 || status >= 300 || connection.getContentLengthLong() > MAX_BYTES) return null;
                try (InputStream in = connection.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[16384];
                    for (int read; (read = in.read(buffer)) != -1;) {
                        out.write(buffer, 0, read);
                        if (out.size() > MAX_BYTES) return null;
                    }
                    return out.toByteArray();
                }
            } finally {
                connection.disconnect();
            }
        }
        return null;
    }

    private static String decode(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, Charset.forName(looksCyrillic(bytes) ? "windows-1251" : "windows-1252"));
        }
    }

    /** Nearly every letter of windows-1251 Cyrillic is a high byte; Western text only uses them for accents. */
    private static boolean looksCyrillic(byte[] bytes) {
        int high = 0, letters = 0;
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (c >= 0xC0) high++;
            if (c >= 0xC0 || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) letters++;
        }
        return letters > 0 && high * 3 > letters;
    }

    /** Recognises the format from the content, since sites often serve subtitles as text/plain. */
    @Nullable private static Text identify(String text, String declared) {
        String body = text.startsWith("\uFEFF") ? text.substring(1) : text;
        String head = body.substring(0, Math.min(body.length(), 4000)).trim();
        if (head.startsWith("WEBVTT")) return new Text(MimeTypes.TEXT_VTT, body);
        if (head.startsWith("[Script Info]") || head.contains("[Events]")) return new Text(MimeTypes.TEXT_SSA, body);
        if (head.startsWith("<") && head.contains("<tt")) return new Text(MimeTypes.APPLICATION_TTML, body);
        if (SRT.matcher(head).find()) return new Text(MimeTypes.APPLICATION_SUBRIP, body);
        // WebVTT cues without the WEBVTT header line, which Media3 requires.
        if (DOTTED_CUES.matcher(head).find()) return new Text(MimeTypes.TEXT_VTT, "WEBVTT\n\n" + body);
        if (head.contains("-->") && !declared.isEmpty() && !head.startsWith("<")) return new Text(declared, body);
        return null;
    }

    private static String extension(String mime) {
        switch (mime) {
            case MimeTypes.APPLICATION_SUBRIP: return ".srt";
            case MimeTypes.TEXT_SSA: return ".ass";
            case MimeTypes.APPLICATION_TTML: return ".ttml";
            default: return ".vtt";
        }
    }
}
