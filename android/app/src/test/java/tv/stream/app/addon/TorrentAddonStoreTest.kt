package tv.stream.app.addon

import java.net.URI
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashMap
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Curated discovery, offline directory merging and configured addresses, without a device or public requests. */
class TorrentAddonStoreTest {
    @Test @Throws(Exception::class) fun curatedScrapersAreUnconfiguredHttpsListingsWithExplicitSetupPages() {
        val names: MutableList<String> = ArrayList()
        val ids: MutableSet<String> = HashSet(); val urls: MutableSet<String> = HashSet()
        for (listing in TorrentAddons.listings()) {
            names.add(listing.manifest.name)
            assertTrue(ids.add(listing.manifest.id)); assertTrue(urls.add(listing.url))
            assertTrue(listing.sources()); assertTrue(listing.torrents()); assertFalse(listing.builtin())
            assertFalse(listing.catalogues()); assertFalse(listing.subtitles())
            assertEquals(TorrentAddons.COLLECTION, listing.collection)
            val address: URI = AddonUrl.web(AddonUrl(listing.url).manifest)
            assertEquals("https", address.getScheme()); assertNull(address.getRawQuery())
            val entry: TorrentAddons.Entry? = TorrentAddons.find(listing.url)
            assertNotNull(entry); assertSame(listing, entry!!.listing)
            assertEquals("https", AddonUrl.web(entry.configureUrl).getScheme())
        }
        assertEquals(Arrays.asList("Torrentio", "Comet", "Meteor", "MediaFusion", "StremThru Torz", "Peerflix", "TorrentsDB"), names)
        assertTrue(TorrentAddons.find("https://stremthru.13377001.xyz/stremio/torz/manifest.json")!!.listing.manifest.configurationRequired)
        assertEquals("https://config.peerflix.mov", TorrentAddons.find("https://peerflix.mov/manifest.json")!!.configureUrl)
    }

    @Test @Throws(Exception::class) fun configuredProvidersKeepTheirPublicSetupPageWithoutCopyingTokens() {
        val configured = "https://peerflix.mov/secret%2Fsettings/manifest.json?token=private"
        val entry: TorrentAddons.Entry? = TorrentAddons.find(configured)
        assertNotNull(entry)
        assertEquals("https://peerflix.mov/manifest.json", entry!!.url)
        assertEquals("https://config.peerflix.mov", entry.configureUrl)
        val live = AddonManifest.parse(JSONObject(entry.listing.manifest.json).put("behaviorHints", JSONObject()))
        assertFalse(live.p2p) // Several current providers don't advertise the optional p2p hint.
        assertTrue(AddonListing(configured, live, "Community").torrents())
        for (address in arrayOf("https://peerflix.mov.example.org/manifest.json", "http://peerflix.mov/manifest.json",
                "https://peerflix.mov:8443/manifest.json", "https://stremthru.13377001.xyz/stremio/store/manifest.json",
                "https://stremthru.13377001.xyz/stremio/torz-other/manifest.json", "not a URL", BuiltinAddons.INTERNET_ARCHIVE)) assertNull(address, TorrentAddons.find(address))
        assertNull(TorrentAddons.find(null))
    }

    @Test fun directoryFailureRetainsCuratedScrapersAndDirectoryDuplicatesAppearOnce() {
        val comet = TorrentAddons.listings().get(1)
        val groups: MutableMap<String, List<AddonListing>> = LinkedHashMap()
        groups.put("Community", Collections.singletonList(comet))
        val offline = AddonRepository.Result<AddonListing>(Collections.singletonList(comet),
                Collections.singletonMap("Official", AddonException.Kind.NETWORK), true, groups)
        val found: AddonRepository.Result<AddonListing> = AddonRepository.combineListings(offline, TorrentAddons.listings())
        assertEquals(Arrays.asList("Built in", TorrentAddons.COLLECTION), ArrayList(found.byResource.keys))
        assertEquals(8, found.items.size); assertEquals(7, found.byResource.get(TorrentAddons.COLLECTION)!!.size)
        assertTrue(found.stale); assertEquals(offline.failures, found.failures)
        assertEquals(1L, found.items.stream().filter { l -> l.manifest.id.equals(comet.manifest.id) }.count())
        // An isolated/fixture directory never gains the public scraper section.
        val isolated: AddonRepository.Result<AddonListing> = AddonRepository.combineListings(
                AddonRepository.Result<AddonListing>(Collections.emptyList(), Collections.emptyMap(), false), Collections.emptyList())
        assertEquals(Collections.singletonList("Built in"), ArrayList(isolated.byResource.keys))
    }

    @Test @Throws(Exception::class) fun setupAddressPreservesConfiguredEscapesAndQuery() {
        val address = AddonUrl("https://example.com/secret%2Fconfiguration/manifest.json?token=abc%2Fdef")
        assertEquals("https://example.com/secret%2Fconfiguration/configure?token=abc%2Fdef", address.configuration())
        assertFalse(address.toString().contains("secret")); assertFalse(address.toString().contains("token"))
    }
}
