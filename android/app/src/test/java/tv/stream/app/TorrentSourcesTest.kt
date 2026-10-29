package tv.stream.app

import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import java.util.ArrayList
import java.util.Arrays
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList
import tv.stream.app.addon.MediaSource
import tv.stream.torrent.TorrentEngine

/**
 * A torrent add-on's answer, in the shape Torrentio, Comet and Peerflix send, through the app's own parsing and
 * source policy on a TV with an H.264 hardware decoder and no HEVC decoder. Made-up hashes and trackers; no network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [TorrentSourcesTest.SoftwareDecoders::class])
class TorrentSourcesTest {
    @Implements(value = io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary::class, isInAndroidSdk = false)
    open class SoftwareDecoders {
        companion object {
            @JvmField var available: Boolean = false
            @Implementation @JvmStatic fun supportsFormat(mime: String?): Boolean {
                return available && Arrays.asList("video/avc", "video/hevc", "video/av01", "video/x-vnd.on2.vp9",
                        "audio/eac3", "audio/ac3", "audio/true-hd", "audio/vnd.dts").contains(mime)
            }
        }
    }
    @Test @Throws(Exception::class) fun magnetBootstrapPeersSurviveMetadataRecoveryWithoutDns() {
        val source = TorrentEngine.Source.magnet("magnet:?xt=urn:btih:" + "1".repeat(40)
                + "&x.pe=127.0.0.1%3A58134&x.pe=%5B%3A%3A1%5D%3A58134&x.pe=host.invalid%3A80&x.pe=999.0.0.1%3A80&x.pe=127.0.0.1%3A99999")
        val peers = TorrentEngine.Source::class.java.getDeclaredField("peers"); peers.setAccessible(true)
        @Suppress("UNCHECKED_CAST")
        assertArrayEquals(arrayOf("127.0.0.1:58134", "[::1]:58134"), peers.get(source) as Array<String>)
    }

    @Before fun avcOnlyTv() {
        SoftwareDecoders.available = false
        ShadowMediaCodecList.reset()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("c2.test.avc.decoder").setIsEncoder(false)
                .setIsHardwareAccelerated(true).setIsVendor(true)
                .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                        .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)).setIsEncoder(false)
                        .setColorFormats(intArrayOf(android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible))
                        .setProfileLevels(arrayOf<android.media.MediaCodecInfo.CodecProfileLevel>()).build())
                .build())
        tv.stream.playback.Capabilities.load(ApplicationProvider.getApplicationContext())
    }

    @Test @Throws(Exception::class) fun torrentResultsParseWithTheirFileAndTrackers() {
        val sources = answer()
        assertEquals(5, sources.size)
        for (s in sources) { assertEquals(MediaSource.Kind.TORRENT, s.kind); assertTrue(s.playable()) }
        val bluray = sources.get(2)
        assertEquals("b".repeat(40), bluray.infoHash)
        assertEquals(1, bluray.fileIndex)
        assertEquals("Film.1080p.BluRay.x264.mkv", bluray.filename)
        assertNotNull(bluray.torrentSource())
        // A result naming no trackers still finds peers: playback adds the long-running public trackers.
        val bare = sources.get(4)
        assertTrue(bare.discovery.isEmpty())
        assertNotNull(bare.torrentSource().withFallbackTrackers(*MediaSource.PUBLIC_TRACKERS))
    }

    @Test @Throws(Exception::class) fun sourcesListOnlyWhatThisTvPlaysBestFirst() {
        val sources = answer()
        val shown: MutableList<String> = ArrayList()
        for (s in SourcePolicy.ranked(sources)) shown.add(s.infoHash!!.substring(0, 1))
        // 1080p H.264, then 720p H.264, then the result whose codec is unknown until it opens. The 4K Dolby Vision and
        // HEVC results are hidden: this TV can't decode them.
        assertEquals(Arrays.asList("b", "c", "e"), shown)
        assertEquals("Above the 1080p device profile", SourcePolicy.assess(sources.get(0)).label)
        assertEquals("Video decoder unavailable", SourcePolicy.assess(sources.get(3)).label)
    }

    @Test @Throws(Exception::class) fun bundledDecodersKeepTenBitHevcAndSurroundReleasesEligible() {
        SoftwareDecoders.available = true
        val shown: MutableList<String> = ArrayList()
        for (s in SourcePolicy.ranked(answer())) shown.add(s.infoHash!!.substring(0, 1))
        assertEquals(Arrays.asList("b", "c", "d", "e"), shown)
        for (audio in arrayOf("DDP5.1", "DD+5.1", "E-AC-3", "AC3", "DTS-HD.MA", "TrueHD")) {
            val release = MediaSource.list("fixture", JSONObject().put("streams", JSONArray()
                    .put(stream("1080p x264 " + audio, "Film", 'f', 0, null)))).get(0)
            assertTrue(audio, SourcePolicy.assess(release).usable)
        }
        // Software decoders don't remove the resolution budget or create Dolby Vision display support.
        assertFalse(SourcePolicy.assess(answer().get(0)).usable)
        val dolby = MediaSource.list("fixture", JSONObject().put("streams", JSONArray()
                .put(stream("1080p x265 10bit DV", "Film", 'f', 0, null)))).get(0)
        assertFalse(SourcePolicy.assess(dolby).usable)
    }

    @Test fun selectedSoftwareAudioIsNotRejectedByASecondPlatformDecoderCheck() {
        assertTrue(SourcePolicy.withinDeviceProfile(androidx.media3.common.Format.Builder().setSampleMimeType("audio/eac3").build()))
        assertTrue(SourcePolicy.withinDeviceProfile(androidx.media3.common.Format.Builder().setSampleMimeType("video/hevc").setWidth(1920).setHeight(1080).build()))
        assertFalse(SourcePolicy.withinDeviceProfile(androidx.media3.common.Format.Builder().setSampleMimeType("video/hevc").setWidth(3840).setHeight(2160).build()))
    }

    companion object {
        @Throws(Exception::class)
        private fun stream(name: String, title: String, hash: Char, file: Int, filename: String?, vararg sources: String): JSONObject {
            val o = JSONObject().put("name", name).put("title", title).put("infoHash", hash.toString().repeat(40)).put("fileIdx", file)
            if (filename != null) o.put("behaviorHints", JSONObject().put("bingeGroup", "test|" + name).put("filename", filename))
            if (sources.size > 0) o.put("sources", JSONArray(Arrays.asList(*sources)))
            return o
        }

        @Throws(Exception::class)
        private fun answer(): List<MediaSource> {
            val streams = JSONArray()
                    .put(stream("Torrentio\n4k DV | HDR", "Film.2160p.WEB-DL.DV.HDR.H265\n👤 120 💾 18.2 GB ⚙️ Example", 'a', 0,
                            "Film.2160p.WEB-DL.DV.HDR.H265.mkv", "tracker:udp://tracker.example.org:1337/announce", "dht:" + "a".repeat(40)))
                    .put(stream("Torrentio\n720p", "Film.720p.WEB.x264\n👤 30 💾 900 MB ⚙️ Example", 'c', 2, "Film.720p.WEB.x264.mkv",
                            "tracker:udp://tracker.example.org:1337/announce"))
                    .put(stream("Torrentio\n1080p", "Film.1080p.BluRay.x264\n👤 80 💾 2.1 GB ⚙️ Example", 'b', 1, "Film.1080p.BluRay.x264.mkv",
                            "tracker:udp://tracker.example.org:1337/announce", "tracker:http://tracker.example.net/announce"))
                    .put(stream("Comet\n1080p", "Film.1080p.WEB.x265.10bit", 'd', 0, "Film.1080p.WEB.x265.10bit.mkv"))
                    .put(stream("Peerflix 🇬🇧 720p", "Film (720p)", 'e', 0, null))
            return MediaSource.list("torrentio", JSONObject().put("streams", streams))
        }
    }
}
