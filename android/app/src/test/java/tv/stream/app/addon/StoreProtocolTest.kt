package tv.stream.app.addon

import java.util.Arrays
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import tv.stream.torrent.TorrentEngine

/** Store listings, addon_catalog addresses, the built-in add-on and torrent discovery, without a device or network. */
class StoreProtocolTest {
    @Test @Throws(Exception::class) fun configurableEmptyResourcesRequireSetupUntilConfigured() {
        val root = manifest("setup", "Setup root", "").put("resources", JSONArray())
                .put("behaviorHints", JSONObject().put("configurable", true).put("configurationRequired", false))
        val setup = AddonManifest.parse(root)
        assertTrue(setup.configurationRequired); assertTrue(setup.resources.isEmpty())
        assertFalse(setup.handles(AddonManifest.STREAM, "movie", "tt0063350"))
        root.put("resources", JSONArray().put("stream"))
        assertFalse(AddonManifest.parse(root).configurationRequired)
    }

    @Test @Throws(Exception::class) fun explicitEmptyResourcesAreValidButMissingOrUnreadableDeclarationsAreNot() {
        val root = manifest("empty", "Empty", "").put("resources", JSONArray())
        assertTrue(AddonManifest.parse(root).resources.isEmpty())
        root.remove("resources")
        try { AddonManifest.parse(root); fail() } catch (e: AddonException) { assertEquals(AddonException.Kind.MALFORMED, e.kind) }
        root.put("resources", JSONArray().put(JSONObject.NULL).put(JSONObject().put("wrong", true)))
        try { AddonManifest.parse(root); fail() } catch (e: AddonException) { assertEquals(AddonException.Kind.MALFORMED, e.kind) }
    }

    @Test @Throws(Exception::class) fun manifestsPublishAddonListsAtProtocolAddresses() {
        val cinemeta = AddonManifest.parse(JSONObject("{\"id\":\"com.linvo.cinemeta\",\"name\":\"Cinemeta\",\"version\":\"3\",\"types\":[\"movie\",\"series\"],"
                + "\"resources\":[\"catalog\",\"meta\",\"addon_catalog\"],\"catalogs\":[],\"addonCatalogs\":[{\"type\":\"all\",\"id\":\"official\",\"name\":\"Official\"},"
                + "{\"type\":\"movie\",\"id\":\"community\",\"name\":\"Community\"},{\"name\":\"no id\"}]}"))
        assertTrue(cinemeta.declares(AddonManifest.ADDON_CATALOG))
        assertFalse(cinemeta.declares(AddonManifest.STREAM))
        assertEquals(2, cinemeta.addonCatalogs.size)
        assertEquals("Official", cinemeta.addonCatalogs.get(0).name)
        val address = AddonUrl(AddonUrl.CINEMETA)
        assertEquals("https://v3-cinemeta.strem.io/addon_catalog/all/official.json",
                address.resource(AddonManifest.ADDON_CATALOG, "all", "official", Collections.emptyMap()))
        assertEquals("https://example.com/cfg/manifest.json", AddonUrl.normalize("  stremio://example.com/cfg/manifest.json "))
        assertEquals("https://example.com/manifest.json", AddonUrl.normalize("https://example.com/manifest.json"))
        val configured = AddonUrl("https://example.com/secret%2Fconfig/manifest.json?token=abc")
        assertEquals("https://example.com/secret%2Fconfig/addon_catalog/all/community.json?token=abc",
                configured.resource(AddonManifest.ADDON_CATALOG, "all", "community", Collections.emptyMap()))
    }

    @Test @Throws(Exception::class) fun listingsSkipWhatCannotInstallAndKeepThePlainAddress() {
        val plain = manifest("org.example.source", "Example Source", "")
        val addons = JSONArray()
                .put(entry("https://example.com/eyJhY2Nlc3NfdG9rZW4iOiJzZWNyZXQifQ/manifest.json", plain))
                .put(entry("https://example.com/manifest.json", plain))
                .put(entry("https://opensubtitles.strem.io/stremio/v1", manifest("legacy", "Legacy", "")))
                .put(entry("http://127.0.0.1:11470/local-addon/manifest.json", manifest("local", "Local Files", "")))
                .put(entry("https://192.168.1.4/manifest.json", manifest("lan", "LAN", "")))
                .put(entry("https://adult.example.com/manifest.json", manifest("adult", "Adult", ",\"behaviorHints\":{\"adult\":true}")))
                .put(entry("https://broken.example.com/manifest.json", JSONObject("{\"id\":\"broken\"}")))
                .put(JSONObject().put("transportUrl", "ipfs://x/manifest.json").put("transportName", "ipfs").put("manifest", manifest("ipfs", "IPFS", "")))
                .put(entry("stream://internet-archive/manifest.json", manifest("spoofed", "Spoofed built-in", "")))
                .put(entry("https://setup.example.com/manifest.json", manifest("setup", "Needs setup", ",\"behaviorHints\":{\"configurable\":true,\"configurationRequired\":true}")))
                .put("not an entry")
        val listed: List<AddonListing> = AddonListing.parse("Community", "https://v3-cinemeta.strem.io/addon_catalog/all/community.json", JSONObject().put("addons", addons))
        assertEquals(2, listed.size)
        assertEquals("https://example.com/manifest.json", listed.get(0).url)
        assertTrue(listed.get(0).sources())
        assertFalse(listed.get(0).subtitles())
        assertEquals("Community", listed.get(0).collection)
        assertTrue(listed.get(1).manifest.configurationRequired)
        // A directory on this device may list add-ons on the same host (the emulator's fixture service does).
        val local = JSONObject().put("addons", JSONArray().put(entry("http://127.0.0.1:58133/addon/good/a/manifest.json", plain)))
        assertEquals(1, AddonListing.parse("Fixtures", "http://127.0.0.1:58133/addon/good/directory/addon_catalog/all/official.json", local).size)
        assertEquals(0, AddonListing.parse("Elsewhere", "http://127.0.0.2:58133/addon_catalog/all/official.json", local).size)
        try { AddonListing.parse("Bad", "https://example.com/addon_catalog/all/x.json", JSONObject()); fail() }
        catch (e: AddonException) { assertEquals(AddonException.Kind.MALFORMED, e.kind) }
    }

    @Test @Throws(Exception::class) fun builtInAddonAnswersItsManifestWithoutNetwork() {
        AddonClient().use { client ->
            val archive = AddonManifest.parse(client.json(BuiltinAddons.INTERNET_ARCHIVE, Cancellation(), AddonClient.deadline()))
            assertEquals("Internet Archive", archive.name)
            assertTrue(archive.handles(AddonManifest.STREAM, "movie", "tt0063350"))
            assertFalse(archive.handles(AddonManifest.STREAM, "series", "tt0052520:1:1"))
            assertFalse(archive.handles(AddonManifest.STREAM, "movie", "kitsu:1"))
            assertTrue(archive.catalogs.get(0).searchable() && archive.catalogs.get(0).listable())
            try { client.json("stream://internet-archive/meta/movie/tt0063350.json", Cancellation(), AddonClient.deadline()); fail() }
            catch (e: AddonException) { assertEquals(404, e.httpStatus) }
            try { client.json("stream://unknown/manifest.json", Cancellation(), AddonClient.deadline()); fail() }
            catch (e: AddonException) { assertEquals(AddonException.Kind.HTTP, e.kind) }
        }
        assertEquals(BuiltinAddons.INTERNET_ARCHIVE, AddonUrl(BuiltinAddons.INTERNET_ARCHIVE).toString())
        assertTrue(AddonUrl(BuiltinAddons.INTERNET_ARCHIVE).builtin())
        assertEquals("stream://internet-archive/stream/movie/tt0063350.json",
                AddonUrl(BuiltinAddons.INTERNET_ARCHIVE).resource(AddonManifest.STREAM, "movie", "tt0063350", Collections.emptyMap()))
        for (invalid in arrayOf("stream://internet-archive/other.json", "stream://Upper/manifest.json", "stream://a/b/manifest.json")) {
            try { AddonUrl(invalid); fail(invalid) } catch (e: AddonException) { assertEquals(AddonException.Kind.INVALID_URL, e.kind) }
        }
        assertEquals(1, BuiltinAddons.listings().size)
        assertTrue(BuiltinAddons.listings().get(0).builtin())
    }

    @Test fun archiveFieldsBecomeExactImdbIdsAndPlainText() {
        assertEquals("tt0063350", BuiltinAddons.imdb("https://www.imdb.com/title/tt0063350/?ref_=fn_tt_tt_1"))
        assertEquals("tt0044420", BuiltinAddons.imdb(JSONArray().put("https://www.imdb.com//title/tt0044420/")))
        assertEquals("tt0063350", BuiltinAddons.imdb("tt0063350"))
        assertNull(BuiltinAddons.imdb("https://www.imdb.com/name/nm0001681/"))
        assertNull(BuiltinAddons.imdb(42))
        assertEquals("Title: Night\nSummary: A & B", BuiltinAddons.plain("Title: Night<br />Summary: A &amp; B<p></p>"))
        assertTrue(BuiltinAddons.plain(java.lang.String.join(" ", Collections.nCopies(400, "word"))).endsWith("…"))
    }

    @Test @Throws(Exception::class) fun torrentsWithoutTrackersAnnounceToPublicTrackersOnlyWhenAsked() {
        val bare = MediaSource.list("p", JSONObject("{\"streams\":[{\"name\":\"1080p\",\"infoHash\":\"11ea02584fa6351956f35671962ab46354d99060\",\"fileIdx\":0}]}")).get(0)
        assertEquals(MediaSource.Kind.TORRENT, bare.kind)
        assertEquals(0, bare.fileIndex)
        assertEquals(0, trackers(bare.torrentSource(), "trackers").size)
        // Fallbacks travel separately; the engine announces to them only for a bare info hash naming no tracker.
        val fallback = bare.torrentSource().withFallbackTrackers(*MediaSource.PUBLIC_TRACKERS)
        assertEquals(0, trackers(fallback, "trackers").size)
        assertEquals(Arrays.asList(*MediaSource.PUBLIC_TRACKERS), trackers(fallback, "fallbackTrackers"))
        val hinted = MediaSource.list("p", JSONObject("{\"streams\":[{\"infoHash\":\"11ea02584fa6351956f35671962ab46354d99060\","
                + "\"sources\":[\"tracker:udp://tracker.example.org:80/announce\",\"dht:11ea02584fa6351956f35671962ab46354d99060\"]}]}")).get(0)
        assertEquals(Collections.singletonList("udp://tracker.example.org:80/announce"), trackers(hinted.torrentSource().withFallbackTrackers(*MediaSource.PUBLIC_TRACKERS), "trackers"))
        for (tracker in MediaSource.PUBLIC_TRACKERS) assertTrue(tracker, tracker.matches(Regex("(udp|http)://[a-z0-9.-]+:[0-9]+/announce")))
    }

    companion object {
        @Throws(Exception::class)
        private fun manifest(id: String, name: String, extra: String): JSONObject {
            return JSONObject("{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"version\":\"1.0.0\",\"types\":[\"movie\"],\"resources\":[\"stream\"]" + extra + "}")
        }
        @Throws(Exception::class)
        private fun entry(url: String, manifest: JSONObject): JSONObject {
            return JSONObject().put("transportUrl", url).put("transportName", "http").put("manifest", manifest)
        }
        @Suppress("UNCHECKED_CAST")
        @Throws(Exception::class)
        private fun trackers(source: TorrentEngine.Source, name: String): List<String> {
            val field = TorrentEngine.Source::class.java.getDeclaredField(name)
            field.setAccessible(true)
            return Arrays.asList(*(field.get(source) as Array<String>))
        }
    }
}
