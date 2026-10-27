package tv.stream.app.addon;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Curated discovery, offline directory merging and configured addresses, without a device or public requests. */
public final class TorrentAddonStoreTest {
    @Test public void curatedScrapersAreUnconfiguredHttpsListingsWithExplicitSetupPages() throws Exception {
        List<String> names = new ArrayList<>();
        Set<String> ids = new HashSet<>(), urls = new HashSet<>();
        for (AddonListing listing : TorrentAddons.listings()) {
            names.add(listing.manifest.name);
            assertTrue(ids.add(listing.manifest.id)); assertTrue(urls.add(listing.url));
            assertTrue(listing.sources()); assertTrue(listing.torrents()); assertFalse(listing.builtin());
            assertFalse(listing.catalogues()); assertFalse(listing.subtitles());
            assertEquals(TorrentAddons.COLLECTION, listing.collection);
            URI address = AddonUrl.web(new AddonUrl(listing.url).manifest);
            assertEquals("https", address.getScheme()); assertNull(address.getRawQuery());
            TorrentAddons.Entry entry = TorrentAddons.find(listing.url);
            assertNotNull(entry); assertSame(listing, entry.listing);
            assertEquals("https", AddonUrl.web(entry.configureUrl).getScheme());
        }
        assertEquals(Arrays.asList("Torrentio", "Comet", "Meteor", "MediaFusion", "StremThru Torz", "Peerflix", "TorrentsDB"), names);
        assertTrue(TorrentAddons.find("https://stremthru.13377001.xyz/stremio/torz/manifest.json").listing.manifest.configurationRequired);
        assertEquals("https://config.peerflix.mov", TorrentAddons.find("https://peerflix.mov/manifest.json").configureUrl);
    }

    @Test public void configuredProvidersKeepTheirPublicSetupPageWithoutCopyingTokens() throws Exception {
        String configured = "https://peerflix.mov/secret%2Fsettings/manifest.json?token=private";
        TorrentAddons.Entry entry = TorrentAddons.find(configured);
        assertNotNull(entry);
        assertEquals("https://peerflix.mov/manifest.json", entry.url);
        assertEquals("https://config.peerflix.mov", entry.configureUrl);
        AddonManifest live = AddonManifest.parse(new JSONObject(entry.listing.manifest.json).put("behaviorHints", new JSONObject()));
        assertFalse(live.p2p); // Several current providers don't advertise the optional p2p hint.
        assertTrue(new AddonListing(configured, live, "Community").torrents());
        for (String address : new String[]{"https://peerflix.mov.example.org/manifest.json", "http://peerflix.mov/manifest.json",
                "https://peerflix.mov:8443/manifest.json", "https://stremthru.13377001.xyz/stremio/store/manifest.json",
                "https://stremthru.13377001.xyz/stremio/torz-other/manifest.json", "not a URL", BuiltinAddons.INTERNET_ARCHIVE}) assertNull(address, TorrentAddons.find(address));
        assertNull(TorrentAddons.find(null));
    }

    @Test public void directoryFailureRetainsCuratedScrapersAndDirectoryDuplicatesAppearOnce() {
        AddonListing comet = TorrentAddons.listings().get(1);
        Map<String, List<AddonListing>> groups = new LinkedHashMap<>();
        groups.put("Community", Collections.singletonList(comet));
        AddonRepository.Result<AddonListing> offline = new AddonRepository.Result<>(Collections.singletonList(comet),
                Collections.singletonMap("Official", AddonException.Kind.NETWORK), true, groups);
        AddonRepository.Result<AddonListing> found = AddonRepository.combineListings(offline, TorrentAddons.listings());
        assertEquals(Arrays.asList("Built in", TorrentAddons.COLLECTION), new ArrayList<>(found.byResource.keySet()));
        assertEquals(8, found.items.size()); assertEquals(7, found.byResource.get(TorrentAddons.COLLECTION).size());
        assertTrue(found.stale); assertEquals(offline.failures, found.failures);
        assertEquals(1, found.items.stream().filter(l -> l.manifest.id.equals(comet.manifest.id)).count());
        // An isolated/fixture directory never gains the public scraper section.
        AddonRepository.Result<AddonListing> isolated = AddonRepository.combineListings(
                new AddonRepository.Result<>(Collections.emptyList(), Collections.emptyMap(), false), Collections.emptyList());
        assertEquals(Collections.singletonList("Built in"), new ArrayList<>(isolated.byResource.keySet()));
    }

    @Test public void setupAddressPreservesConfiguredEscapesAndQuery() throws Exception {
        AddonUrl address = new AddonUrl("https://example.com/secret%2Fconfiguration/manifest.json?token=abc%2Fdef");
        assertEquals("https://example.com/secret%2Fconfiguration/configure?token=abc%2Fdef", address.configuration());
        assertFalse(address.toString().contains("secret")); assertFalse(address.toString().contains("token"));
    }
}
