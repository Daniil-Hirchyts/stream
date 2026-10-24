package tv.daniil.stream;

import android.content.Context;
import android.os.Build;
import androidx.media3.common.PlaybackException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import tv.daniil.stream.addon.AddonException;
import tv.daniil.torrent.TorrentException;

/** A frozen, offline report. Never contains playable/configured addresses, headers or private file paths. */
final class DiagnosticReport {
    // Small symbols are easier to photograph from a sofa. Every byte is retained across numbered pages.
    static final int PAGE_BYTES = 850;
    private static final Pattern ADDRESS = Pattern.compile("(?i)\\b(?:[a-z][a-z0-9+.-]*://|magnet:)[^\\s<>\"']+");
    private static final Pattern PRIVATE_PATH = Pattern.compile("(?i)(?<![\\w.])(?:/[\\w.%+~-]+){2,}[^\\s<>\"']*|[A-Z]:\\\\[^\\s<>\"']+");
    private static final Pattern SECRET = Pattern.compile("(?i)\\b(?:authorization|cookie|set-cookie)[\"']?\\s*[:=]\\s*[^\\r\\n]+|\\bBearer\\s+\\S+|\\b[\\w-]*(?:token|secret|password|api[_-]?key)[\\w-]*[\"']?\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)");
    final String id, text;
    final List<String> pages;

    private DiagnosticReport(String id, String text) {
        this.id = id; this.text = text;
        List<String> chunks = split(text);
        List<String> numbered = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) numbered.add("Daniil TV error " + id + " — " + (i + 1) + "/" + chunks.size()
                + "\nJoin the numbered parts for the complete JSON report.\n" + chunks.get(i));
        pages = Collections.unmodifiableList(numbered);
    }

    static DiagnosticReport capture(Context context, String stage, String heading, String message, String code,
                                    Throwable cause, JSONObject state) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        try {
            JSONObject report = new JSONObject().put("report", id).put("schema", 1).put("timeUtc", Instant.now().toString())
                    .put("app", context.getPackageName()).put("version", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
                    .put("device", Build.MANUFACTURER + " " + Build.MODEL).put("androidApi", Build.VERSION.SDK_INT)
                    .put("abis", String.join(",", Build.SUPPORTED_ABIS)).put("stage", stage)
                    .put("error", new JSONObject().put("code", code).put("title", heading).put("message", message))
                    .put("causes", exception(cause)).put("state", state == null ? new JSONObject() : state);
            // Redact recursively, before serialization; escaped JSON must not defeat address/path redaction.
            return new DiagnosticReport(id, ((JSONObject) scrub(report)).toString(2));
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    static JSONArray exception(Throwable cause) {
        JSONArray causes = new JSONArray();
        java.util.Set<Throwable> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (cause != null && seen.add(cause) && causes.length() < 12) {
            try {
                JSONObject item = new JSONObject().put("type", cause.getClass().getName()).put("message", safe(cause.getMessage()));
                if (cause instanceof AddonException) item.put("kind", ((AddonException) cause).kind.name()).put("httpStatus", ((AddonException) cause).httpStatus);
                if (cause instanceof TorrentException) item.put("torrentKind", ((TorrentException) cause).kind);
                if (cause instanceof PlaybackException) item.put("playerCode", ((PlaybackException) cause).getErrorCodeName());
                JSONArray stack = new JSONArray();
                StackTraceElement[] frames = cause.getStackTrace();
                for (int i = 0; i < Math.min(frames.length, 8); i++) stack.put(safe(frames[i].toString()));
                item.put("stack", stack);
                if (frames.length > 8) item.put("omittedFrames", frames.length - 8);
                causes.put(item);
            } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
            cause = cause.getCause();
        }
        if (cause != null) causes.put("Additional or cyclic causes omitted");
        return causes;
    }

    static String safe(String value) {
        if (value == null) return "";
        return SECRET.matcher(PRIVATE_PATH.matcher(ADDRESS.matcher(value).replaceAll("[address redacted]")).replaceAll("[path redacted]"))
                .replaceAll("[credential redacted]");
    }

    private static Object scrub(Object value) throws org.json.JSONException {
        if (value instanceof JSONObject) {
            JSONObject clean = new JSONObject();
            java.util.Iterator<String> keys = ((JSONObject) value).keys();
            while (keys.hasNext()) { String key = keys.next(); clean.put(key, scrub(((JSONObject) value).get(key))); }
            return clean;
        }
        if (value instanceof JSONArray) {
            JSONArray clean = new JSONArray();
            for (int i = 0; i < ((JSONArray) value).length(); i++) clean.put(scrub(((JSONArray) value).get(i)));
            return clean;
        }
        return value instanceof String ? safe((String) value) : value;
    }

    private static List<String> split(String text) {
        List<String> result = new ArrayList<>();
        int start = 0, bytes = 0;
        for (int at = 0; at < text.length();) {
            int end = at + Character.charCount(text.codePointAt(at));
            int size = text.substring(at, end).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > PAGE_BYTES) { result.add(text.substring(start, at)); start = at; bytes = 0; }
            bytes += size; at = end;
        }
        result.add(text.substring(start));
        return result;
    }
}
