package tv.stream.app.addon;

import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One add-on offered by the store: an {@code addon_catalog} entry or a built-in add-on. Its manifest is the listing's
 * copy, for browsing only; installation always reviews the add-on's own current manifest first.
 */
public final class AddonListing {
    public final String url;
    public final AddonManifest manifest;
    /** The list it came from, such as "Official" or "Community". */
    public final String collection;

    AddonListing(String url, AddonManifest manifest, String collection) {
        this.url = url; this.manifest = manifest; this.collection = collection;
    }

    public boolean sources() { return manifest.declares(AddonManifest.STREAM); }
    public boolean torrents() { return sources() && (manifest.p2p || TorrentAddons.find(url) != null); }
    public boolean catalogues() { return manifest.declares(AddonManifest.CATALOG) && !manifest.catalogs.isEmpty() || manifest.declares(AddonManifest.META); }
    public boolean subtitles() { return manifest.declares(AddonManifest.SUBTITLES); }
    public boolean builtin() { return url.startsWith(AddonUrl.BUILTIN); }

    /**
     * The entries of an {@code addon_catalog} response. Skips what this app can't install: other transports, legacy
     * non-manifest addresses, add-ons on this device or a private network (they need a companion server) unless the
     * list itself comes from that same host, adult add-ons and unreadable manifests. Within one list a manifest ID
     * appears once, at its shortest address, so a configured copy that embeds someone's settings isn't offered in
     * place of the plain add-on.
     */
    public static List<AddonListing> parse(String collection, String listAddress, JSONObject response) throws AddonException {
        if (response.optJSONArray("addons") == null) throw new AddonException(AddonException.Kind.MALFORMED, "Missing add-on entries");
        Map<String, AddonListing> found = new LinkedHashMap<>();
        for (JSONObject o : Json.objects(response, "addons")) {
            String transport = Json.string(o, "transportName", "http"), url = Json.string(o, "transportUrl");
            if (!transport.equals("http") || url == null || url.startsWith(AddonUrl.BUILTIN) || !installable(url, listAddress)) continue;
            JSONObject raw = o.optJSONObject("manifest");
            if (raw == null) continue;
            AddonManifest manifest;
            try { manifest = AddonManifest.parse(raw); } catch (AddonException malformed) { continue; }
            if (manifest.adult) continue;
            AddonListing previous = found.get(manifest.id);
            if (previous == null || url.length() < previous.url.length()) found.put(manifest.id, new AddonListing(url, manifest, collection));
        }
        return Collections.unmodifiableList(new ArrayList<>(found.values()));
    }

    private static boolean installable(String url, String listAddress) {
        try {
            new AddonUrl(url);
            String host = host(url);
            return !local(host) || !listAddress.startsWith(AddonUrl.BUILTIN) && host.equals(host(listAddress));
        } catch (AddonException | IllegalArgumentException | NullPointerException e) { return false; }
    }
    private static String host(String url) { return URI.create(url).getHost().toLowerCase(Locale.ROOT); }
    private static boolean local(String host) {
        return host.equals("localhost") || host.endsWith(".local") || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")
                || host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*") || host.equals("0.0.0.0") || host.startsWith("[") || host.startsWith("169.254.");
    }
}
