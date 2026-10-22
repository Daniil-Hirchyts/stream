package tv.daniil.stream.addon;

import androidx.annotation.Nullable;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tv.daniil.torrent.TorrentEngine;

/** An advertised candidate, including unsupported candidates so Sources can explain them. */
public final class MediaSource {
    public enum Kind { HTTP, TORRENT, MAGNET, UNSUPPORTED }
    /**
     * Long-running open trackers, announced to when a source names no trackers of its own (many add-ons send only an
     * info hash). DHT alone finds peers too, but a cold DHT often needs longer than a viewer waits.
     */
    public static final String[] PUBLIC_TRACKERS = {
            "udp://tracker.opentrackr.org:1337/announce", "udp://open.demonii.com:1337/announce", "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce", "udp://exodus.desync.com:6969/announce", "udp://explodie.org:6969/announce",
            "http://tracker.opentrackr.org:1337/announce"};
    public final String provider, name, description, reason;
    public final Kind kind;
    @Nullable public final String url, infoHash, filename, videoHash, bingeGroup;
    /** -1 means absent; zero is an explicit first file. Invalid values make the candidate unsupported. */
    public final int fileIndex;
    public final long videoSize;
    public final List<String> discovery;
    public final Map<String, String> requestHeaders;
    public final List<SubtitleSource> subtitles;

    private MediaSource(String provider, JSONObject o) {
        this.provider = provider;
        name = Json.string(o, "name", "Source");
        description = Json.string(o, "description", Json.string(o, "title", ""));
        url = Json.string(o, "url");
        infoHash = Json.string(o, "infoHash");
        long index = Json.index(o, "fileIdx");
        fileIndex = index >= 0 && index <= Integer.MAX_VALUE ? (int) index : -1;
        JSONObject hints = o.optJSONObject("behaviorHints");
        if (hints == null) hints = new JSONObject();
        filename = Json.string(hints, "filename");
        videoHash = Json.string(hints, "videoHash");
        videoSize = Math.max(0, Json.number(hints, "videoSize", 0));
        bingeGroup = Json.string(hints, "bingeGroup");
        discovery = Json.strings(o, "sources");
        subtitles = SubtitleSource.list(provider, o);
        String unsupported = "";
        Kind selected = Kind.UNSUPPORTED;
        if (o.has("externalUrl")) unsupported = "Opens an external browser";
        else if (o.has("ytId")) unsupported = "Requires a YouTube player";
        else if (o.has("nzbUrl")) unsupported = "Usenet is unsupported";
        else if (o.has("rarUrls") || o.has("zipUrls") || o.has("7zipUrls") || o.has("tarUrls") || o.has("tgzUrls")) unsupported = "Archives are unsupported";
        else if (o.has("drm") || o.has("drmInfo") || hints.has("drm")) unsupported = "Requires DRM";
        else if (index == -2 || index > Integer.MAX_VALUE) unsupported = "Invalid torrent file index";
        else if (infoHash != null) {
            if (infoHash.matches("(?i)[0-9a-f]{40}|[0-9a-f]{64}")) selected = Kind.TORRENT;
            else unsupported = "Invalid torrent hash";
        } else if (url != null && url.regionMatches(true, 0, "magnet:?", 0, 8)) {
            if (validMagnet(url)) selected = Kind.MAGNET;
            else unsupported = "Invalid magnet link";
        } else if (Json.webUrl(url) != null) selected = Kind.HTTP;
        else unsupported = "Unsupported source address";
        kind = selected;
        reason = unsupported;
        Map<String, String> headers = new LinkedHashMap<>();
        JSONObject proxy = hints.optJSONObject("proxyHeaders");
        JSONObject request = proxy == null ? null : proxy.optJSONObject("request");
        if (kind == Kind.HTTP && request != null) {
            Iterator<String> keys = request.keys();
            while (keys.hasNext()) {
                String key = keys.next(), value = Json.string(request, key);
                if (value != null && key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") && !value.contains("\r") && !value.contains("\n")
                        && !key.matches("(?i)Host|Connection|Content-Length|Transfer-Encoding|Proxy-.*")) headers.put(key, value);
            }
        }
        requestHeaders = Collections.unmodifiableMap(headers);
    }

    public static List<MediaSource> list(String provider, JSONObject response) {
        List<MediaSource> out = new ArrayList<>();
        for (JSONObject o : Json.objects(response, "streams")) {
            if (o.has("url") || o.has("infoHash") || o.has("externalUrl") || o.has("ytId") || o.has("nzbUrl")
                    || o.has("rarUrls") || o.has("zipUrls") || o.has("7zipUrls") || o.has("tarUrls") || o.has("tgzUrls")) out.add(new MediaSource(provider, o));
        }
        return Collections.unmodifiableList(out);
    }
    public boolean playable() { return kind != Kind.UNSUPPORTED; }

    public static boolean validMagnet(String address) {
        try {
            URI uri = URI.create(address);
            if (!"magnet".equalsIgnoreCase(uri.getScheme()) || uri.getRawFragment() != null) return false;
            String query = uri.getRawSchemeSpecificPart();
            if (!query.startsWith("?")) return false;
            for (String pair : query.substring(1).split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2 && parts[0].equals("xt")) {
                    String hash = URLDecoder.decode(parts[1], "UTF-8");
                    if (hash.matches("(?i)urn:btih:([0-9a-f]{40}|[a-z2-7]{32})|urn:btmh:1220[0-9a-f]{64}")) return true;
                }
            }
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException ignored) { /* invalid source */ }
        return false;
    }

    /** Converts only the explicitly selected torrent; never creates a native session while browsing. */
    public TorrentEngine.Source torrentSource() {
        TorrentEngine.Source source;
        if (kind == Kind.TORRENT) source = TorrentEngine.Source.infoHash(infoHash);
        else if (kind == Kind.MAGNET) source = TorrentEngine.Source.magnet(url);
        else throw new IllegalStateException("This source is not a torrent");
        List<String> trackers = new ArrayList<>();
        for (String hint : discovery) if (hint.startsWith("tracker:")) {
            String address = hint.substring(8);
            try {
                URI uri = URI.create(address);
                if (uri.getHost() != null && uri.getRawUserInfo() == null && ("udp".equalsIgnoreCase(uri.getScheme())
                        || "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) trackers.add(address);
            } catch (IllegalArgumentException ignored) { /* optional malformed tracker */ }
        }
        // dht:<hash> hints are retained as discovery metadata. Production DHT is configured on the engine;
        // deterministic tests use localOnly() and explicit peers, which never bootstrap public DHT.
        return source.withTrackers(trackers.toArray(new String[0]));
    }
}
