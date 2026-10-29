package tv.stream.app

import org.junit.Assert.*

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.stream.app.addon.*

/**
 * Optional internet audit on the verified emulator: the current store and earlier Nuvio list, real manifests and supported
 * resources for the public-domain 1968 Night of the Living Dead. No accounts or configured secrets are supplied.
 * Provider outages/empty results are evidence, not fabricated successful playback. Installation uses the actual
 * review button in an isolated database. Required suites remain local-only.
 */
@RunWith(AndroidJUnit4::class)
class LiveAddonTest : AppHarness() {
    private val results = JSONArray()
    private val startedAt = System.currentTimeMillis()
    private var listed = 0
    private var earlierList: JSONArray? = null
    private var directoryFailures = JSONObject()

    @Before @Throws(Exception::class) fun internet() {
        context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE).edit().putBoolean("localOnly", false)
                .putBoolean("publicNetworkOnly", true).remove("peer").remove("cacheBytes").remove("addonDirectory").commit()
        app.resetServices(); services = app.services()
        save(false)
    }

    @Test @Throws(Exception::class) fun currentStoreManifestsResourcesAndInstallationReviews() {
        val inventory: AddonRepository.Result<AddonListing> = services.addons.newRequest().use { scope -> services.addons.store(scope) }
        listed = inventory.items.size
        assertTrue("Built-in and curated providers remain discoverable", listed >= TorrentAddons.listings().size + 1)
        for (failure in inventory.failures.entries)
            directoryFailures.put(failure.key, failure.value.name)
        val listings: MutableList<AddonListing> = ArrayList(inventory.items)
        instrumentation.context.assets.open("nuvio-addon-audit.json").use { input ->
            earlierList = JSONArray(String(input.readAllBytes(), StandardCharsets.UTF_8))
        }
        for (i in 0 until earlierList!!.length()) {
            val entry = earlierList!!.getJSONObject(i)
            val url = entry.optString("manifest")
            if (url.isEmpty()) continue
            var match = -1
            for (j in listings.indices) if (listings[j].url == url) { match = j; break }
            if (match < 0) {
                val manifest = JSONObject().put("id", "audit.nuvio.$i").put("name", entry.getString("name"))
                        .put("version", "0.0.0").put("types", JSONArray().put("movie"))
                        .put("resources", JSONArray().put("stream"))
                val response = JSONObject().put("addons", JSONArray().put(JSONObject()
                        .put("transportUrl", url).put("manifest", manifest)))
                val added = AddonListing.parse("Earlier Nuvio list", "https://nuvio.tv/manifest.json", response)
                assertEquals("Public audit endpoint must be installable as an address", 1, added.size.toLong())
                match = listings.size; listings.add(added[0])
            }
            entry.put("auditIndex", match)
        }
        val workers: ExecutorService = Executors.newFixedThreadPool(4)
        val audits: MutableList<Future<JSONObject>> = ArrayList()
        try {
            AddonClient().use { client ->
                for (listing in listings) audits.add(workers.submit(Callable { audit(listing, client) }))
                for (audit in audits) {
                    val result = audit.get(); results.put(result); save(false)
                    println("ADDON " + result.getString("name") + " " + result.optString("manifest") + " " + result.optJSONObject("stream"))
                }
            }
        } finally { workers.shutdownNow() }

        launch()
        waitUntil("empty isolated home", 10_000) { visible("Make yourself at home") }
        for (i in listings.indices) {
            val listing = listings[i]
            val result = results.getJSONObject(i)
            // Every current listing is opened through production navigation, including unavailable manifests.
            val review = Route("Add-on review"); review.value = listing.url
            browse!!.onActivity { activity -> activity.push(review) }
            waitUntil("review action for " + listing.manifest.name, 20_000
            ) { tagged("install-addon") || tagged("configured") || tagged("retry-review") }
            if (tagged("install-addon")) {
                tap("Install", i % 2 == 0)
                waitUntil("accepted manifest installed", 10_000) { !services.store.addons().isEmpty() }
                assertEquals(1, services.store.addons().size.toLong())
                assertEquals(listing.url, services.store.addons()[0].address.manifest)
                result.put("review", "installed")
                waitUntil("review refresh after installation", 20_000
                ) { tagged("manage") || tagged("retry-review") || tagged("configured") }
                result.put("reviewRefresh", if (tagged("manage")) "ready" else "provider unavailable")
                // Management itself is covered with fixtures; removal here prevents cross-provider traffic.
                services.addons.remove(services.store.addons()[0].id)
            } else result.put("review", if (tagged("retry-review")) "unavailable" else "needs setup")
            assertTrue("The isolated store must be empty between providers", services.store.addons().isEmpty())
            browse!!.onActivity { activity -> activity.back() }
            waitUntil("return to empty home", 10_000) { visible("Make yourself at home") }
            save(false)
        }
        assertEquals(listings.size.toLong(), results.length().toLong()); save(true)
    }

    @Throws(Exception::class)
    private fun audit(listing: AddonListing, client: AddonClient): JSONObject {
        val result = JSONObject().put("name", listing.manifest.name).put("host", AddonUrl.redacted(listing.url))
                .put("collection", listing.collection)
        val start = SystemClock.elapsedRealtime()
        try {
            services.addons.newRequest().use { scope ->
                val manifest = services.addons.preview(listing.url, scope)
                result.put("manifest", "valid").put("configurationRequired", manifest.configurationRequired)
                        .put("catalogues", manifest.catalogs.size).put("types", JSONArray(manifest.types))
                if (manifest.configurationRequired || manifest.adult) return result
                val address = AddonUrl(listing.url)
                val catalogues = JSONArray()
                for (catalogue in manifest.catalogs) {
                    if (catalogues.length() >= 2) break
                    if (!catalogue.listable() && !catalogue.searchable()) continue
                    val sample = JSONObject().put("type", catalogue.type).put("search", !catalogue.listable())
                    try {
                        val extra: Map<String, String> = if (catalogue.listable()) Collections.emptyMap()
                                else Collections.singletonMap("search", "Night of the Living Dead")
                        val response = client.json(address.resource(AddonManifest.CATALOG, catalogue.type, catalogue.id, extra),
                                scope, AddonClient.deadline())
                        sample.put("titles", Title.catalog("audit", response).size)
                    } catch (e: AddonException) { sample.put("error", failure(e)) }
                    catalogues.put(sample)
                }
                result.put("catalogueSamples", catalogues)
                if (manifest.handles(AddonManifest.STREAM, "movie", FILM)) {
                    try {
                        val response = get(client, address, AddonManifest.STREAM, FILM, scope)
                        val sources = MediaSource.list("audit", response)
                        var http = 0; var torrent = 0; var unsupported = 0
                        for (source in sources) {
                            if (source.kind == MediaSource.Kind.HTTP) http++
                            else if (source.kind == MediaSource.Kind.TORRENT) torrent++
                            else unsupported++
                        }
                        result.put("stream", JSONObject().put("http", http).put("torrent", torrent).put("unsupported", unsupported))
                    } catch (e: AddonException) { result.put("stream", failure(e)) }
                }
                if (manifest.handles(AddonManifest.META, "movie", FILM)) {
                    try {
                        val title = Title.parse("audit", get(client, address, AddonManifest.META, FILM, scope).optJSONObject("meta"))!!
                        result.put("metadata", if (title == null) "empty" else "valid")
                    } catch (e: AddonException) { result.put("metadata", failure(e)) }
                }
                if (manifest.handles(AddonManifest.SUBTITLES, "movie", FILM)) {
                    try { result.put("subtitles", SubtitleSource.list("audit", get(client, address, AddonManifest.SUBTITLES, FILM, scope)).size) }
                    catch (e: AddonException) { result.put("subtitles", failure(e)) }
                }
            }
        } catch (e: AddonException) { result.put("manifest", failure(e)) }
        finally { result.put("elapsedMs", SystemClock.elapsedRealtime() - start) }
        return result
    }

    @Throws(AddonException::class)
    private fun get(client: AddonClient, address: AddonUrl, resource: String, id: String, scope: Cancellation): JSONObject {
        return client.json(address.resource(resource, "movie", id, Collections.emptyMap()), scope, AddonClient.deadline())
    }
    private fun failure(e: AddonException): String { return e.kind.name + (if (e.httpStatus == 0) "" else " " + e.httpStatus) }
    private fun tagged(key: String): Boolean {
        val found = AtomicBoolean()
        browse!!.onActivity { activity ->
            val view: android.view.View? = activity.findViewById<android.view.View>(R.id.page_rows).findViewWithTag(key)
            found.set(view != null && view.isShown)
        }
        return found.get()
    }
    @Throws(Exception::class)
    private fun save(complete: Boolean) {
        val report = JSONObject().put("complete", complete).put("startedAt", startedAt).put("listed", listed)
                .put("film", FILM).put("directoryFailures", directoryFailures).put("addons", results)
                .put("earlierNuvioList", if (earlierList == null) JSONArray() else earlierList)
        Files.write(File(context.filesDir, "validation-addons.json").toPath(), report.toString(2).toByteArray(StandardCharsets.UTF_8))
    }

    companion object {
        private const val FILM = "tt0063350"
    }
}
