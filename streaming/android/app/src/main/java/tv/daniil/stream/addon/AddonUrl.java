package tv.daniil.stream.addon;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** Builds resource paths without decoding or rebuilding an add-on's configured path. */
public final class AddonUrl {
    public static final String CINEMETA = "https://v3-cinemeta.strem.io/manifest.json";
    /** Add-ons answered inside the app ({@link BuiltinAddons}), addressed as {@code daniiltv://name/manifest.json}. */
    public static final String BUILTIN = "daniiltv://";
    public final String manifest;
    private final String base, query;

    public AddonUrl(String value) throws AddonException {
        if (value.startsWith(BUILTIN)) {
            if (!value.matches("daniiltv://[a-z0-9-]+/manifest\\.json")) throw new AddonException(AddonException.Kind.INVALID_URL, "Unknown built-in add-on");
            manifest = value;
            base = value.substring(0, value.length() - 14);
            query = "";
            return;
        }
        URI uri = web(value);
        String path = uri.getRawPath();
        if (path == null || !path.endsWith("/manifest.json") || uri.getRawFragment() != null) {
            throw new AddonException(AddonException.Kind.INVALID_URL, "Enter an HTTP(S) manifest.json address");
        }
        // Keep escapes, configuration tokens, and query parameters exactly as supplied.
        manifest = value;
        base = uri.getScheme() + "://" + uri.getRawAuthority() + path.substring(0, path.length() - 14);
        query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
    }

    public String resource(String resource, String type, String id, Map<String, String> extras) {
        if (!resource.matches("catalog|meta|stream|subtitles|addon_catalog")) throw new IllegalArgumentException("Unknown resource");
        StringBuilder out = new StringBuilder(base).append('/').append(resource).append('/')
                .append(encode(type)).append('/').append(encode(id));
        if (!extras.isEmpty()) {
            out.append('/');
            boolean first = true;
            for (Map.Entry<String, String> e : new TreeMap<>(extras).entrySet()) {
                if (!first) out.append('&');
                first = false;
                out.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
            }
        }
        return out.append(".json").append(query).toString();
    }

    public static URI web(String value) throws AddonException {
        try {
            URI uri = new URI(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() > 65535
                    || value.chars().anyMatch(c -> c <= 32 || c == 127)) throw new URISyntaxException("", "Invalid URL");
            return uri;
        } catch (URISyntaxException | NullPointerException e) {
            throw new AddonException(AddonException.Kind.INVALID_URL, "Invalid HTTP(S) address");
        }
    }

    public boolean builtin() { return manifest.startsWith(BUILTIN); }

    /** Keeps configured escapes and query parameters intact, just like resource addresses. */
    public String configuration() { return base + "/configure" + query; }

    /** What people paste: add-on configure pages hand out {@code stremio://host/…/manifest.json} install links. */
    public static String normalize(String input) {
        String value = input.trim();
        return value.regionMatches(true, 0, "stremio://", 0, 10) ? "https://" + value.substring(10) : value;
    }

    /** Never display configured paths, userinfo, queries, or fragments in diagnostic output. */
    public static String redacted(String value) {
        if (value.matches("daniiltv://[a-z0-9-]+/manifest\\.json")) return value;
        try {
            URI uri = web(value);
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + "/…";
        } catch (AddonException e) { return "[private address]"; }
    }

    public static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 255;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '-' || c == '_' || c == '.' || c == '~') out.append((char) c);
            else out.append('%').append("0123456789ABCDEF".charAt(c >> 4)).append("0123456789ABCDEF".charAt(c & 15));
        }
        // A resource ID is one segment, including when its entire value happens to be a dot segment.
        if (out.toString().equals(".") || out.toString().equals("..")) return out.toString().replace(".", "%2E");
        return out.toString();
    }

    @Override public String toString() { return redacted(manifest); }
}
