package tv.daniil.stream;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import tv.daniil.stream.addon.MediaSource;
import tv.daniil.torrent.TorrentException;
import tv.daniil.torrent.TorrentFile;

@RunWith(AndroidJUnit4.class)
public final class SourcePolicyTest {
    @Test public void explicitZeroAndInvalidIndexesAreNeverGuessed() throws Exception {
        java.util.List<TorrentFile> files = Arrays.asList(new TorrentFile(0, "Season/Show S01E01.mkv", 100),
                new TorrentFile(2, "Season/Show S01E02.mkv", 100), new TorrentFile(3, "poster.jpg", 10));
        assertEquals(0, SourcePolicy.select(files, 0, 1, 2).index);
        assertEquals(2, SourcePolicy.select(files, -1, 1, 2).index);
        assertNull(SourcePolicy.select(files, -1, -1, -1));
        for (int index : new int[]{1, 3, 99}) try { SourcePolicy.select(files, index, 1, 1); fail("Invalid advertised index accepted"); }
        catch (TorrentException expected) { assertEquals(TorrentException.INVALID_FILE, expected.kind); }
        assertNull(SourcePolicy.select(Arrays.asList(new TorrentFile(0, "Show S01E01.mkv", 1), new TorrentFile(1, "Show S01E01 alt.mp4", 1)), -1, 1, 1));
    }
    @Test public void unknownHintsStayVisibleAndExpiredUrlsDoNotChangeIdentity() throws Exception {
        MediaSource first = source("Unknown", "http://127.0.0.1/old?secret=a");
        assertTrue(SourcePolicy.assess(first).usable);
        assertTrue(SourcePolicy.assess(first).label.contains("unknown"));
        assertFalse(SourcePolicy.assess(source("4K AVC", "http://127.0.0.1/video")).usable);
        assertFalse(SourcePolicy.assess(source("1440p AVC", "http://127.0.0.1/video")).usable);
        if (androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos("video/dolby-vision", false, false).isEmpty())
            assertFalse(SourcePolicy.assess(source("1080p HEVC DOVI", "http://127.0.0.1/dv.mp4")).usable);
        assertEquals(SourcePolicy.key(first), SourcePolicy.key(source("Unknown", "http://127.0.0.1/new?secret=b")));
        assertEquals(64, SourcePolicy.key(first).length());
    }
    @Test public void advertisedDolbyAudioIsCheckedBeforeOpening() throws Exception {
        for (String label : new String[]{"EAC3", "E-AC-3", "DDP5.1", "DD+5.1", "Dolby Digital Plus"}) {
            assertEquals(label, "audio/eac3", SourcePolicy.advertisedAudio(label));
            boolean supported = StreamDecoders.supports("audio/eac3")
                    || !androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos("audio/eac3", false, false).isEmpty();
            assertEquals(label, supported, SourcePolicy.assess(source("1080p H264 " + label, "http://127.0.0.1/video")).usable);
        }
        assertEquals("audio/ac3", SourcePolicy.advertisedAudio("DD5.1"));
        assertEquals("audio/ac3", SourcePolicy.advertisedAudio("AC-3"));
        assertNull(SourcePolicy.advertisedAudio("AAC EAC3 dual audio"));
        assertNull(SourcePolicy.advertisedAudio("Added 1080p"));
        assertTrue(SourcePolicy.assess(source("1080p H264 AAC", "http://127.0.0.1/video")).usable);
    }
    @Test public void retryIdentityIgnoresTheProviderButPreservesFileAndHeaders() throws Exception {
        String hash = "0123456789abcdef0123456789abcdef01234567";
        org.json.JSONObject torrent = new org.json.JSONObject().put("infoHash", hash).put("fileIdx", 0);
        MediaSource first = MediaSource.list("one", new org.json.JSONObject().put("streams", new org.json.JSONArray().put(torrent))).get(0);
        MediaSource other = MediaSource.list("two", new org.json.JSONObject().put("streams", new org.json.JSONArray().put(new org.json.JSONObject(torrent.toString()).put("name", "Another label")))).get(0);
        assertEquals(SourcePolicy.retryKey(first), SourcePolicy.retryKey(other));
        MediaSource magnet = MediaSource.list("three", new org.json.JSONObject().put("streams", new org.json.JSONArray().put(new org.json.JSONObject()
                .put("url", "magnet:?xt=urn:btih:" + hash.toUpperCase(java.util.Locale.ROOT) + "&tr=udp%3A%2F%2Ftracker.example%3A80").put("fileIdx", 0)))).get(0);
        assertEquals(SourcePolicy.retryKey(first), SourcePolicy.retryKey(magnet));
        torrent.put("fileIdx", 1);
        MediaSource file = MediaSource.list("one", new org.json.JSONObject().put("streams", new org.json.JSONArray().put(torrent))).get(0);
        assertNotEquals(SourcePolicy.retryKey(first), SourcePolicy.retryKey(file));
        assertNotEquals(SourcePolicy.retryKey(source("one", "http://127.0.0.1/one.mp4")), SourcePolicy.retryKey(source("two", "http://127.0.0.1/two.mp4")));
        assertEquals(64, SourcePolicy.retryKey(first).length());
    }
    @Test public void recoveryTriesADifferentProviderBeforeMoreOfTheSame() throws Exception {
        MediaSource first = source("one", "http://127.0.0.1/a.mp4"), another = source("two", "http://127.0.0.1/b.mp4");
        MediaSource otherProvider = MediaSource.list("different", new org.json.JSONObject().put("streams", new org.json.JSONArray()
                .put(new org.json.JSONObject().put("name", "Different provider").put("url", "http://127.0.0.1/c.mp4")))).get(0);
        java.util.Set<String> tried = new java.util.HashSet<>(); tried.add(SourcePolicy.retryKey(first));
        java.util.Set<String> providers = new java.util.HashSet<>(); providers.add(first.provider);
        java.util.List<MediaSource> ranked = java.util.Arrays.asList(first, another, otherProvider);
        assertEquals(2, SourcePolicy.nextCandidate(ranked, tried, providers));
        tried.add(SourcePolicy.retryKey(otherProvider)); providers.add(otherProvider.provider);
        assertEquals(1, SourcePolicy.nextCandidate(ranked, tried, providers));
        tried.add(SourcePolicy.retryKey(another));
        assertEquals(-1, SourcePolicy.nextCandidate(ranked, tried, providers));
    }
    private static MediaSource source(String name, String url) throws Exception {
        return MediaSource.list("provider", new JSONObject().put("streams", new JSONArray().put(new JSONObject().put("name", name).put("url", url)))).get(0);
    }
}
