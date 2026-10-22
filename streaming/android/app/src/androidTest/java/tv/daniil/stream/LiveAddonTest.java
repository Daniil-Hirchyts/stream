package tv.daniil.stream;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import tv.daniil.stream.addon.*;

/**
 * Optional internet audit on the verified emulator: the current store and earlier Nuvio list, real manifests and supported
 * resources for the public-domain 1968 Night of the Living Dead. No accounts or configured secrets are supplied.
 * Provider outages/empty results are evidence, not fabricated successful playback. Installation uses the actual
 * review button in an isolated database. Required suites remain local-only.
 */
@RunWith(AndroidJUnit4.class)
public final class LiveAddonTest extends AppHarness {
    private static final String FILM = "tt0063350";
    private final JSONArray results = new JSONArray();
    private final long startedAt = System.currentTimeMillis();
    private int listed;
    private JSONArray earlierList;
    private JSONObject directoryFailures = new JSONObject();

    @Before public void internet() throws Exception {
        context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE).edit().putBoolean("localOnly", false)
                .putBoolean("publicNetworkOnly", true).remove("peer").remove("cacheBytes").remove("addonDirectory").commit();
        app.resetServices(); services = app.services();
        save(false);
    }

    @Test public void currentStoreManifestsResourcesAndInstallationReviews() throws Exception {
        AddonRepository.Result<AddonListing> inventory;
        try (Cancellation scope = services.addons.newRequest()) { inventory = services.addons.store(scope); }
        listed = inventory.items.size();
        assertTrue("Built-in and curated providers remain discoverable", listed >= TorrentAddons.listings().size() + 1);
        for (java.util.Map.Entry<String, AddonException.Kind> failure : inventory.failures.entrySet())
            directoryFailures.put(failure.getKey(), failure.getValue().name());
        List<AddonListing> listings = new ArrayList<>(inventory.items);
        try (java.io.InputStream input = instrumentation.getContext().getAssets().open("nuvio-addon-audit.json")) {
            earlierList = new JSONArray(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        for (int i = 0; i < earlierList.length(); i++) {
            JSONObject entry = earlierList.getJSONObject(i);
            String url = entry.optString("manifest");
            if (url.isEmpty()) continue;
            int match = -1;
            for (int j = 0; j < listings.size(); j++) if (listings.get(j).url.equals(url)) { match = j; break; }
            if (match < 0) {
                JSONObject manifest = new JSONObject().put("id", "audit.nuvio." + i).put("name", entry.getString("name"))
                        .put("version", "0.0.0").put("types", new JSONArray().put("movie"))
                        .put("resources", new JSONArray().put("stream"));
                JSONObject response = new JSONObject().put("addons", new JSONArray().put(new JSONObject()
                        .put("transportUrl", url).put("manifest", manifest)));
                List<AddonListing> added = AddonListing.parse("Earlier Nuvio list", "https://nuvio.tv/manifest.json", response);
                assertEquals("Public audit endpoint must be installable as an address", 1, added.size());
                match = listings.size(); listings.add(added.get(0));
            }
            entry.put("auditIndex", match);
        }
        ExecutorService workers = Executors.newFixedThreadPool(4);
        List<Future<JSONObject>> audits = new ArrayList<>();
        try (AddonClient client = new AddonClient()) {
            for (AddonListing listing : listings) audits.add(workers.submit(() -> audit(listing, client)));
            for (Future<JSONObject> audit : audits) {
                JSONObject result = audit.get(); results.put(result); save(false);
                System.out.println("ADDON " + result.getString("name") + " " + result.optString("manifest") + " " + result.optJSONObject("stream"));
            }
        } finally { workers.shutdownNow(); }

        launch();
        waitUntil("empty isolated home", 10_000, () -> visible("Make yourself at home"));
        for (int i = 0; i < listings.size(); i++) {
            AddonListing listing = listings.get(i);
            JSONObject result = results.getJSONObject(i);
            // Every current listing is opened through production navigation, including unavailable manifests.
            Route review = new Route("Add-on review"); review.value = listing.url;
            browse.onActivity(activity -> activity.push(review));
            waitUntil("review action for " + listing.manifest.name, 20_000,
                    () -> tagged("install-addon") || tagged("configured") || tagged("retry-review"));
            if (tagged("install-addon")) {
                tap("Install", i % 2 == 0);
                waitUntil("accepted manifest installed", 10_000, () -> !services.store.addons().isEmpty());
                assertEquals(1, services.store.addons().size());
                assertEquals(listing.url, services.store.addons().get(0).address.manifest);
                result.put("review", "installed");
                waitUntil("review refresh after installation", 20_000,
                        () -> tagged("manage") || tagged("retry-review") || tagged("configured"));
                result.put("reviewRefresh", tagged("manage") ? "ready" : "provider unavailable");
                // Management itself is covered with fixtures; removal here prevents cross-provider traffic.
                services.addons.remove(services.store.addons().get(0).id);
            } else result.put("review", tagged("retry-review") ? "unavailable" : "needs setup");
            assertTrue("The isolated store must be empty between providers", services.store.addons().isEmpty());
            browse.onActivity(MainActivity::back);
            waitUntil("return to empty home", 10_000, () -> visible("Make yourself at home"));
            save(false);
        }
        assertEquals(listings.size(), results.length()); save(true);
    }

    private JSONObject audit(AddonListing listing, AddonClient client) throws Exception {
        JSONObject result = new JSONObject().put("name", listing.manifest.name).put("host", AddonUrl.redacted(listing.url))
                .put("collection", listing.collection);
        long start = SystemClock.elapsedRealtime();
        try (Cancellation scope = services.addons.newRequest()) {
            AddonManifest manifest = services.addons.preview(listing.url, scope);
            result.put("manifest", "valid").put("configurationRequired", manifest.configurationRequired)
                    .put("catalogues", manifest.catalogs.size()).put("types", new JSONArray(manifest.types));
            if (manifest.configurationRequired || manifest.adult) return result;
            AddonUrl address = new AddonUrl(listing.url);
            JSONArray catalogues = new JSONArray();
            for (AddonManifest.Catalog catalogue : manifest.catalogs) {
                if (catalogues.length() >= 2) break;
                if (!catalogue.listable() && !catalogue.searchable()) continue;
                JSONObject sample = new JSONObject().put("type", catalogue.type).put("search", !catalogue.listable());
                try {
                    java.util.Map<String, String> extra = catalogue.listable() ? Collections.emptyMap()
                            : Collections.singletonMap("search", "Night of the Living Dead");
                    JSONObject response = client.json(address.resource(AddonManifest.CATALOG, catalogue.type, catalogue.id, extra),
                            scope, AddonClient.deadline());
                    sample.put("titles", Title.catalog("audit", response).size());
                } catch (AddonException e) { sample.put("error", failure(e)); }
                catalogues.put(sample);
            }
            result.put("catalogueSamples", catalogues);
            if (manifest.handles(AddonManifest.STREAM, "movie", FILM)) {
                try {
                    JSONObject response = get(client, address, AddonManifest.STREAM, FILM, scope);
                    List<MediaSource> sources = MediaSource.list("audit", response);
                    int http = 0, torrent = 0, unsupported = 0;
                    for (MediaSource source : sources) {
                        if (source.kind == MediaSource.Kind.HTTP) http++;
                        else if (source.kind == MediaSource.Kind.TORRENT) torrent++;
                        else unsupported++;
                    }
                    result.put("stream", new JSONObject().put("http", http).put("torrent", torrent).put("unsupported", unsupported));
                } catch (AddonException e) { result.put("stream", failure(e)); }
            }
            if (manifest.handles(AddonManifest.META, "movie", FILM)) {
                try {
                    Title title = Title.parse("audit", get(client, address, AddonManifest.META, FILM, scope).optJSONObject("meta"));
                    result.put("metadata", title == null ? "empty" : "valid");
                } catch (AddonException e) { result.put("metadata", failure(e)); }
            }
            if (manifest.handles(AddonManifest.SUBTITLES, "movie", FILM)) {
                try { result.put("subtitles", SubtitleSource.list("audit", get(client, address, AddonManifest.SUBTITLES, FILM, scope)).size()); }
                catch (AddonException e) { result.put("subtitles", failure(e)); }
            }
        } catch (AddonException e) { result.put("manifest", failure(e)); }
        finally { result.put("elapsedMs", SystemClock.elapsedRealtime() - start); }
        return result;
    }

    private JSONObject get(AddonClient client, AddonUrl address, String resource, String id, Cancellation scope) throws AddonException {
        return client.json(address.resource(resource, "movie", id, Collections.emptyMap()), scope, AddonClient.deadline());
    }
    private String failure(AddonException e) { return e.kind.name() + (e.httpStatus == 0 ? "" : " " + e.httpStatus); }
    private boolean tagged(String key) {
        AtomicBoolean found = new AtomicBoolean();
        browse.onActivity(activity -> {
            android.view.View view = activity.findViewById(R.id.page_rows).findViewWithTag(key);
            found.set(view != null && view.isShown());
        });
        return found.get();
    }
    private void save(boolean complete) throws Exception {
        JSONObject report = new JSONObject().put("complete", complete).put("startedAt", startedAt).put("listed", listed)
                .put("film", FILM).put("directoryFailures", directoryFailures).put("addons", results)
                .put("earlierNuvioList", earlierList == null ? new JSONArray() : earlierList);
        Files.write(new File(context.getFilesDir(), "validation-addons.json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
