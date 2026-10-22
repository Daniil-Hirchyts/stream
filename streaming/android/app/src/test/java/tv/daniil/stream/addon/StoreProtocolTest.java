package tv.daniil.stream.addon;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import tv.daniil.torrent.TorrentEngine;

/** Store listings, addon_catalog addresses, the built-in add-on and torrent discovery, without a device or network. */
public final class StoreProtocolTest {
    private static JSONObject manifest(String id, String name, String extra) throws Exception {
        return new JSONObject("{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"version\":\"1.0.0\",\"types\":[\"movie\"],\"resources\":[\"stream\"]" + extra + "}");
    }
    private static JSONObject entry(String url, JSONObject manifest) throws Exception {
        return new JSONObject().put("transportUrl", url).put("transportName", "http").put("manifest", manifest);
    }

    @Test public void manifestsPublishAddonListsAtProtocolAddresses() throws Exception {
        AddonManifest cinemeta = AddonManifest.parse(new JSONObject("{\"id\":\"com.linvo.cinemeta\",\"name\":\"Cinemeta\",\"version\":\"3\",\"types\":[\"movie\",\"series\"],"
                + "\"resources\":[\"catalog\",\"meta\",\"addon_catalog\"],\"catalogs\":[],\"addonCatalogs\":[{\"type\":\"all\",\"id\":\"official\",\"name\":\"Official\"},"
                + "{\"type\":\"movie\",\"id\":\"community\",\"name\":\"Community\"},{\"name\":\"no id\"}]}"));
        assertTrue(cinemeta.declares(AddonManifest.ADDON_CATALOG));
        assertFalse(cinemeta.declares(AddonManifest.STREAM));
        assertEquals(2, cinemeta.addonCatalogs.size());
        assertEquals("Official", cinemeta.addonCatalogs.get(0).name);
        AddonUrl address = new AddonUrl(AddonUrl.CINEMETA);
        assertEquals("https://v3-cinemeta.strem.io/addon_catalog/all/official.json",
                address.resource(AddonManifest.ADDON_CATALOG, "all", "official", Collections.emptyMap()));
        assertEquals("https://example.com/cfg/manifest.json", AddonUrl.normalize("  stremio://example.com/cfg/manifest.json "));
        assertEquals("https://example.com/manifest.json", AddonUrl.normalize("https://example.com/manifest.json"));
        AddonUrl configured = new AddonUrl("https://example.com/secret%2Fconfig/manifest.json?token=abc");
        assertEquals("https://example.com/secret%2Fconfig/addon_catalog/all/community.json?token=abc",
                configured.resource(AddonManifest.ADDON_CATALOG, "all", "community", Collections.emptyMap()));
    }

    @Test public void listingsSkipWhatCannotInstallAndKeepThePlainAddress() throws Exception {
        JSONObject plain = manifest("org.example.source", "Example Source", "");
        JSONArray addons = new JSONArray()
                .put(entry("https://example.com/eyJhY2Nlc3NfdG9rZW4iOiJzZWNyZXQifQ/manifest.json", plain))
                .put(entry("https://example.com/manifest.json", plain))
                .put(entry("https://opensubtitles.strem.io/stremio/v1", manifest("legacy", "Legacy", "")))
                .put(entry("http://127.0.0.1:11470/local-addon/manifest.json", manifest("local", "Local Files", "")))
                .put(entry("https://192.168.1.4/manifest.json", manifest("lan", "LAN", "")))
                .put(entry("https://adult.example.com/manifest.json", manifest("adult", "Adult", ",\"behaviorHints\":{\"adult\":true}")))
                .put(entry("https://broken.example.com/manifest.json", new JSONObject("{\"id\":\"broken\"}")))
                .put(new JSONObject().put("transportUrl", "ipfs://x/manifest.json").put("transportName", "ipfs").put("manifest", manifest("ipfs", "IPFS", "")))
                .put(entry("daniiltv://internet-archive/manifest.json", manifest("spoofed", "Spoofed built-in", "")))
                .put(entry("https://setup.example.com/manifest.json", manifest("setup", "Needs setup", ",\"behaviorHints\":{\"configurable\":true,\"configurationRequired\":true}")))
                .put("not an entry");
        List<AddonListing> listed = AddonListing.parse("Community", "https://v3-cinemeta.strem.io/addon_catalog/all/community.json", new JSONObject().put("addons", addons));
        assertEquals(2, listed.size());
        assertEquals("https://example.com/manifest.json", listed.get(0).url);
        assertTrue(listed.get(0).sources());
        assertFalse(listed.get(0).subtitles());
        assertEquals("Community", listed.get(0).collection);
        assertTrue(listed.get(1).manifest.configurationRequired);
        // A directory on this device may list add-ons on the same host (the emulator's fixture service does).
        JSONObject local = new JSONObject().put("addons", new JSONArray().put(entry("http://127.0.0.1:58133/addon/good/a/manifest.json", plain)));
        assertEquals(1, AddonListing.parse("Fixtures", "http://127.0.0.1:58133/addon/good/directory/addon_catalog/all/official.json", local).size());
        assertEquals(0, AddonListing.parse("Elsewhere", "http://127.0.0.2:58133/addon_catalog/all/official.json", local).size());
        try { AddonListing.parse("Bad", "https://example.com/addon_catalog/all/x.json", new JSONObject()); fail(); }
        catch (AddonException e) { assertEquals(AddonException.Kind.MALFORMED, e.kind); }
    }

    @Test public void builtInAddonAnswersItsManifestWithoutNetwork() throws Exception {
        try (AddonClient client = new AddonClient()) {
            AddonManifest archive = AddonManifest.parse(client.json(BuiltinAddons.INTERNET_ARCHIVE, new Cancellation(), AddonClient.deadline()));
            assertEquals("Internet Archive", archive.name);
            assertTrue(archive.handles(AddonManifest.STREAM, "movie", "tt0063350"));
            assertFalse(archive.handles(AddonManifest.STREAM, "series", "tt0052520:1:1"));
            assertFalse(archive.handles(AddonManifest.STREAM, "movie", "kitsu:1"));
            assertTrue(archive.catalogs.get(0).searchable() && archive.catalogs.get(0).listable());
            try { client.json("daniiltv://internet-archive/meta/movie/tt0063350.json", new Cancellation(), AddonClient.deadline()); fail(); }
            catch (AddonException e) { assertEquals(404, e.httpStatus); }
            try { client.json("daniiltv://unknown/manifest.json", new Cancellation(), AddonClient.deadline()); fail(); }
            catch (AddonException e) { assertEquals(AddonException.Kind.HTTP, e.kind); }
        }
        assertEquals(BuiltinAddons.INTERNET_ARCHIVE, new AddonUrl(BuiltinAddons.INTERNET_ARCHIVE).toString());
        assertTrue(new AddonUrl(BuiltinAddons.INTERNET_ARCHIVE).builtin());
        assertEquals("daniiltv://internet-archive/stream/movie/tt0063350.json",
                new AddonUrl(BuiltinAddons.INTERNET_ARCHIVE).resource(AddonManifest.STREAM, "movie", "tt0063350", Collections.emptyMap()));
        for (String invalid : new String[]{"daniiltv://internet-archive/other.json", "daniiltv://Upper/manifest.json", "daniiltv://a/b/manifest.json"}) {
            try { new AddonUrl(invalid); fail(invalid); } catch (AddonException e) { assertEquals(AddonException.Kind.INVALID_URL, e.kind); }
        }
        assertEquals(1, BuiltinAddons.listings().size());
        assertTrue(BuiltinAddons.listings().get(0).builtin());
    }

    @Test public void archiveFieldsBecomeExactImdbIdsAndPlainText() {
        assertEquals("tt0063350", BuiltinAddons.imdb("https://www.imdb.com/title/tt0063350/?ref_=fn_tt_tt_1"));
        assertEquals("tt0044420", BuiltinAddons.imdb(new JSONArray().put("https://www.imdb.com//title/tt0044420/")));
        assertEquals("tt0063350", BuiltinAddons.imdb("tt0063350"));
        assertNull(BuiltinAddons.imdb("https://www.imdb.com/name/nm0001681/"));
        assertNull(BuiltinAddons.imdb(42));
        assertEquals("Title: Night\nSummary: A & B", BuiltinAddons.plain("Title: Night<br />Summary: A &amp; B<p></p>"));
        assertTrue(BuiltinAddons.plain(String.join(" ", Collections.nCopies(400, "word"))).endsWith("…"));
    }

    @Test public void torrentsWithoutTrackersAnnounceToPublicTrackersOnlyWhenAsked() throws Exception {
        MediaSource bare = MediaSource.list("p", new JSONObject("{\"streams\":[{\"name\":\"1080p\",\"infoHash\":\"11ea02584fa6351956f35671962ab46354d99060\",\"fileIdx\":0}]}")).get(0);
        assertEquals(MediaSource.Kind.TORRENT, bare.kind);
        assertEquals(0, bare.fileIndex);
        assertEquals(0, trackers(bare.torrentSource()).size());
        assertEquals(Arrays.asList(MediaSource.PUBLIC_TRACKERS), trackers(bare.torrentSource().withFallbackTrackers(MediaSource.PUBLIC_TRACKERS)));
        MediaSource hinted = MediaSource.list("p", new JSONObject("{\"streams\":[{\"infoHash\":\"11ea02584fa6351956f35671962ab46354d99060\","
                + "\"sources\":[\"tracker:udp://tracker.example.org:80/announce\",\"dht:11ea02584fa6351956f35671962ab46354d99060\"]}]}")).get(0);
        assertEquals(Collections.singletonList("udp://tracker.example.org:80/announce"), trackers(hinted.torrentSource().withFallbackTrackers(MediaSource.PUBLIC_TRACKERS)));
        for (String tracker : MediaSource.PUBLIC_TRACKERS) assertTrue(tracker, tracker.matches("(udp|http)://[a-z0-9.-]+:[0-9]+/announce"));
    }
    private static List<String> trackers(TorrentEngine.Source source) throws Exception {
        java.lang.reflect.Field field = TorrentEngine.Source.class.getDeclaredField("trackers");
        field.setAccessible(true);
        return Arrays.asList((String[]) field.get(source));
    }
}
