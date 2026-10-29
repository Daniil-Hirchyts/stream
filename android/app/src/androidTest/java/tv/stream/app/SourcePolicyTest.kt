package tv.stream.app

import org.junit.Assert.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Arrays
import tv.stream.app.addon.MediaSource
import tv.stream.torrent.TorrentException
import tv.stream.torrent.TorrentFile

@RunWith(AndroidJUnit4::class)
class SourcePolicyTest {
    @Test @Throws(Exception::class) fun explicitZeroAndInvalidIndexesAreNeverGuessed() {
        val files: List<TorrentFile> = Arrays.asList(TorrentFile(0, "Season/Show S01E01.mkv", 100),
                TorrentFile(2, "Season/Show S01E02.mkv", 100), TorrentFile(3, "poster.jpg", 10))
        assertEquals(0, SourcePolicy.select(files, 0, 1, 2)!!.index)
        assertEquals(2, SourcePolicy.select(files, -1, 1, 2)!!.index)
        assertNull(SourcePolicy.select(files, -1, -1, -1))
        for (index in intArrayOf(1, 3, 99)) try { SourcePolicy.select(files, index, 1, 1); fail("Invalid advertised index accepted") }
        catch (expected: TorrentException) { assertEquals(TorrentException.INVALID_FILE, expected.kind) }
        assertNull(SourcePolicy.select(Arrays.asList(TorrentFile(0, "Show S01E01.mkv", 1), TorrentFile(1, "Show S01E01 alt.mp4", 1)), -1, 1, 1))
    }
    @Test @Throws(Exception::class) fun unknownHintsStayVisibleAndExpiredUrlsDoNotChangeIdentity() {
        val first = source("Unknown", "http://127.0.0.1/old?secret=a")
        assertTrue(SourcePolicy.assess(first).usable)
        assertTrue(SourcePolicy.assess(first).label.contains("unknown"))
        assertFalse(SourcePolicy.assess(source("4K AVC", "http://127.0.0.1/video")).usable)
        assertFalse(SourcePolicy.assess(source("1440p AVC", "http://127.0.0.1/video")).usable)
        if (androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos("video/dolby-vision", false, false).isEmpty())
            assertFalse(SourcePolicy.assess(source("1080p HEVC DOVI", "http://127.0.0.1/dv.mp4")).usable)
        assertEquals(SourcePolicy.key(first), SourcePolicy.key(source("Unknown", "http://127.0.0.1/new?secret=b")))
        assertEquals(64, SourcePolicy.key(first).length.toLong())
    }
    @Test @Throws(Exception::class) fun advertisedDolbyAudioIsCheckedBeforeOpening() {
        for (label in arrayOf("EAC3", "E-AC-3", "DDP5.1", "DD+5.1", "Dolby Digital Plus")) {
            assertEquals(label, "audio/eac3", SourcePolicy.advertisedAudio(label))
            val supported = StreamDecoders.supports("audio/eac3")
                    || !androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos("audio/eac3", false, false).isEmpty()
            assertEquals(label, supported, SourcePolicy.assess(source("1080p H264 $label", "http://127.0.0.1/video")).usable)
        }
        assertEquals("audio/ac3", SourcePolicy.advertisedAudio("DD5.1"))
        assertEquals("audio/ac3", SourcePolicy.advertisedAudio("AC-3"))
        assertNull(SourcePolicy.advertisedAudio("AAC EAC3 dual audio"))
        assertNull(SourcePolicy.advertisedAudio("Added 1080p"))
        assertTrue(SourcePolicy.assess(source("1080p H264 AAC", "http://127.0.0.1/video")).usable)
    }
    @Test @Throws(Exception::class) fun retryIdentityIgnoresTheProviderButPreservesFileAndHeaders() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val torrent = org.json.JSONObject().put("infoHash", hash).put("fileIdx", 0)
        val first = MediaSource.list("one", org.json.JSONObject().put("streams", org.json.JSONArray().put(torrent)))[0]
        val other = MediaSource.list("two", org.json.JSONObject().put("streams", org.json.JSONArray().put(org.json.JSONObject(torrent.toString()).put("name", "Another label"))))[0]
        assertEquals(SourcePolicy.retryKey(first), SourcePolicy.retryKey(other))
        val magnet = MediaSource.list("three", org.json.JSONObject().put("streams", org.json.JSONArray().put(org.json.JSONObject()
                .put("url", "magnet:?xt=urn:btih:" + hash.uppercase(java.util.Locale.ROOT) + "&tr=udp%3A%2F%2Ftracker.example%3A80").put("fileIdx", 0))))[0]
        assertEquals(SourcePolicy.retryKey(first), SourcePolicy.retryKey(magnet))
        torrent.put("fileIdx", 1)
        val file = MediaSource.list("one", org.json.JSONObject().put("streams", org.json.JSONArray().put(torrent)))[0]
        assertNotEquals(SourcePolicy.retryKey(first), SourcePolicy.retryKey(file))
        assertNotEquals(SourcePolicy.retryKey(source("one", "http://127.0.0.1/one.mp4")), SourcePolicy.retryKey(source("two", "http://127.0.0.1/two.mp4")))
        assertEquals(64, SourcePolicy.retryKey(first).length.toLong())
    }
    @Test @Throws(Exception::class) fun recoveryTriesADifferentProviderBeforeMoreOfTheSame() {
        val first = source("one", "http://127.0.0.1/a.mp4"); val another = source("two", "http://127.0.0.1/b.mp4")
        val otherProvider = MediaSource.list("different", org.json.JSONObject().put("streams", org.json.JSONArray()
                .put(org.json.JSONObject().put("name", "Different provider").put("url", "http://127.0.0.1/c.mp4"))))[0]
        val tried: MutableSet<String> = java.util.HashSet(); tried.add(SourcePolicy.retryKey(first))
        val providers: MutableSet<String> = java.util.HashSet(); providers.add(first.provider)
        val ranked: List<MediaSource> = java.util.Arrays.asList(first, another, otherProvider)
        assertEquals(2, SourcePolicy.nextCandidate(ranked, tried, providers).toLong())
        tried.add(SourcePolicy.retryKey(otherProvider)); providers.add(otherProvider.provider)
        assertEquals(1, SourcePolicy.nextCandidate(ranked, tried, providers).toLong())
        tried.add(SourcePolicy.retryKey(another))
        assertEquals(-1, SourcePolicy.nextCandidate(ranked, tried, providers).toLong())
    }
    companion object {
        @Throws(Exception::class)
        private fun source(name: String, url: String): MediaSource {
            return MediaSource.list("provider", JSONObject().put("streams", JSONArray().put(JSONObject().put("name", name).put("url", url))))[0]
        }
    }
}
