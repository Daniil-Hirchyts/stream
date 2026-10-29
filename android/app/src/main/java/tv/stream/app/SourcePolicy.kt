package tv.stream.app

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Comparator
import java.util.Locale
import java.util.regex.Pattern
import tv.stream.playback.Capabilities
import tv.stream.app.addon.MediaSource
import tv.stream.torrent.TorrentException
import tv.stream.torrent.TorrentFile

/** Conservative advertised hints, followed by actual Media3 format/decoder validation. No download side effects. */
@OptIn(markerClass = [UnstableApi::class])
object SourcePolicy {
    private val ABOVE_PROFILE: Pattern = Pattern.compile("\\b(1440p|2160p|4k|8k|uhd|4320p)\\b")
    private val HDR_HINT: Pattern = Pattern.compile("\\b(?:hdr(?:10(?:\\+|plus)?)?|hlg|dovi|dolby[ ._-]?vision|dv)\\b")
    private val COMMON_AUDIO: Pattern = Pattern.compile("\\b(?:aac|opus|mp3)(?=[^a-z]|$)")
    private val TRUEHD_AUDIO: Pattern = Pattern.compile("\\btrue[ ._-]?hd\\b")
    private val DTS_AUDIO: Pattern = Pattern.compile("\\bdts(?=[^a-z]|$)")
    private val EAC3_AUDIO: Pattern = Pattern.compile("\\b(?:e[ ._-]?ac[ ._-]?3|ddp(?:lus)?|dd\\+)(?=[^a-z]|$)|\\bdolby[ ._-]+digital[ ._-]+plus\\b")
    private val AC3_AUDIO: Pattern = Pattern.compile("\\b(?:ac[ ._-]?3|dd)(?=[^a-z]|$)|\\bdolby[ ._-]+digital\\b")
    private val TEN_BIT: Pattern = Pattern.compile("\\b(?:10[ ._-]?bit|hi10p|main[ ._-]?10)\\b")
    private val DOLBY_VISION: Pattern = Pattern.compile("\\b(?:dovi|dolby[ ._-]?vision|dv)\\b")
    private val AV1_HINT: Pattern = Pattern.compile("\\b(av1|av01)\\b")
    private val HEVC_HINT: Pattern = Pattern.compile("\\b(hevc|h[ ._-]?265|x265)\\b")
    private val VP9_HINT: Pattern = Pattern.compile("\\b(vp9|vp09)\\b")
    private val AVC_HINT: Pattern = Pattern.compile("\\b(avc|h[ ._-]?264|x264)\\b")
    data class Assessment(val usable: Boolean, val label: String, val rank: Int)
    fun assess(source: MediaSource): Assessment {
        if (!source.playable()) return Assessment(false, source.reason, -1000)
        val hint = (source.name + " " + source.description + " " + (source.filename ?: "")).lowercase(Locale.ROOT)
        if (ABOVE_PROFILE.matcher(hint).find()) return Assessment(false, "Above the 1080p device profile", -1000)
        val codec: String? = if (AV1_HINT.matcher(hint).find()) "video/av01" else if (HEVC_HINT.matcher(hint).find()) "video/hevc"
                else if (VP9_HINT.matcher(hint).find()) "video/x-vnd.on2.vp9" else if (AVC_HINT.matcher(hint).find()) "video/avc" else null
        val audio = advertisedAudio(hint)
        try {
            if (codec != null && !StreamDecoders.supports(codec)
                    && (MediaCodecUtil.getDecoderInfos(codec, false, false).isEmpty() || !Capabilities.decodes(Capabilities.name(codec)))) return Assessment(false, "Video decoder unavailable", -1000)
            if (audio != null && !StreamDecoders.supports(audio) && MediaCodecUtil.getDecoderInfos(audio, false, false).isEmpty()) return Assessment(false, "Audio decoder unavailable", -1000)
            if (codec != null && !advertisedProfileSupported(codec, hint))
                return Assessment(false, "Advertised video profile unavailable", -1000)
            val hdr = HDR_HINT.matcher(hint).find()
            if (hdr && !DecoderProfile.hdrHintSupported(codec)) return Assessment(false, "HDR needs a compatible hardware decoder and display", -1000)
            // Compatibility is a gate; it must not outweigh availability, HD quality and transfer size.
            val rank = AutoSourceRank.score(SourceSort.facts(source), PlaybackOutcomes.rank(source)) + (if ("video/avc" == codec) 3 else 0)
            if (codec == null) return Assessment(true, "Compatibility unknown; checked when opened", rank)
            val hardware = MediaCodecUtil.getDecoderInfos(codec, false, false).stream().anyMatch { info -> info.hardwareAccelerated }
            return Assessment(true, if (hardware) "Supported hardware decoder; format checked when opened" else "Supported decoder; format checked when opened", rank + (if (hardware) 2 else 0))
        } catch (e: Exception) { return Assessment(true, "Compatibility unknown; checked when opened", AutoSourceRank.score(SourceSort.facts(source), PlaybackOutcomes.rank(source))) }
    }
    /** Codec labels often touch channel counts: DDP5.1 and DD+5.1 are E-AC-3, not unknown audio. */
    fun advertisedAudio(hint: String): String? {
        val text = hint.lowercase(Locale.ROOT)
        // A release advertising a common alternative can still contain a supported selectable audio track.
        if (COMMON_AUDIO.matcher(text).find()) return null
        if (TRUEHD_AUDIO.matcher(text).find()) return "audio/true-hd"
        if (DTS_AUDIO.matcher(text).find()) return "audio/vnd.dts"
        if (EAC3_AUDIO.matcher(text).find()) return "audio/eac3"
        if (AC3_AUDIO.matcher(text).find()) return "audio/ac3"
        return null
    }
    @Throws(Exception::class)
    private fun advertisedProfileSupported(mime: String, hint: String): Boolean {
        val tenBit = TEN_BIT.matcher(hint).find()
        val dolby = DOLBY_VISION.matcher(hint).find()
        if (dolby && MediaCodecUtil.getDecoderInfos("video/dolby-vision", false, false).isEmpty()) return false
        if (!tenBit) return true
        if (StreamDecoders.supports(mime)) return true
        val wanted = if ("video/avc" == mime) android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
                else if ("video/hevc" == mime) android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                else if ("video/x-vnd.on2.vp9" == mime) android.media.MediaCodecInfo.CodecProfileLevel.VP9Profile2
                else if ("video/av01" == mime) android.media.MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 else 0
        if (wanted == 0) return true
        for (decoder in android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (decoder.isEncoder) continue
            for (type in decoder.supportedTypes) if (type.equals(mime, ignoreCase = true))
                for (profile in decoder.getCapabilitiesForType(type).profileLevels)
                    if (profile.profile == wanted || tenBitHdrProfile(mime, profile.profile)) return true
        }
        return false
    }

    private fun tenBitHdrProfile(mime: String, profile: Int): Boolean {
        return "video/hevc" == mime && (profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                || profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
                || "video/x-vnd.on2.vp9" == mime && (profile == android.media.MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR
                || profile == android.media.MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus)
                || "video/av01" == mime && (profile == android.media.MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10
                || profile == android.media.MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus)
    }

    /** Give another installed provider a chance before spending the budget on one provider's entire list. */
    fun nextCandidate(ranked: List<MediaSource>, attempted: Set<String>, providers: Set<String>): Int {
        for (differentProvider in booleanArrayOf(true, false))
            for (i in 0 until ranked.size) {
                val source = ranked[i]
                if (attempted.contains(retryKey(source)) || (differentProvider && providers.contains(source.provider))) continue
                return i
            }
        return -1
    }

    /** Recovery identity is payload/file, not the addon or release label advertising it. Never persisted as an address. */
    fun retryKey(source: MediaSource): String {
        var hash = source.infoHash
        if (hash == null && source.kind == MediaSource.Kind.MAGNET) {
            val query = java.net.URI.create(source.url).rawSchemeSpecificPart
            for (pair in Pattern.compile("&").split(query.substring(1))) {
                val fields = Pattern.compile("=").split(pair, 2)
                if (fields.size == 2 && fields[0] == "xt") {
                    val xt: String
                    try { xt = java.net.URLDecoder.decode(fields[1], "UTF-8") }
                    catch (impossible: java.io.UnsupportedEncodingException) { throw AssertionError(impossible) }
                    if (xt.regionMatches(0, "urn:btih:", 0, 9, ignoreCase = true)) hash = xt.substring(9)
                    else if (xt.regionMatches(0, "urn:btmh:1220", 0, 13, ignoreCase = true)) hash = xt.substring(13)
                    if (hash != null) break
                }
            }
        }
        val identity = if (hash == null) source.kind.toString() + "\n" + source.url + "\n" + java.util.TreeMap(source.requestHeaders)
                else "torrent:" + canonicalHash(hash) + ":" + source.fileIndex
        return digest(identity)
    }
    private fun canonicalHash(hash: String): String {
        if (hash.matches("(?i)[a-z2-7]{32}".toRegex())) {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            val hex = StringBuilder(); var bits = 0; var value = 0
            for (c in hash.uppercase(Locale.ROOT).toCharArray()) {
                value = (value shl 5) or alphabet.indexOf(c); bits += 5
                if (bits >= 8) { bits -= 8; hex.append(String.format(Locale.ROOT, "%02x", (value shr bits) and 255)) }
            }
            return hex.toString()
        }
        return hash.lowercase(Locale.ROOT)
    }
    private fun digest(identity: String): String {
        try { val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8)); val result = StringBuilder(); for (b in digest) result.append(String.format(Locale.ROOT, "%02x", b.toInt() and 255)); return result.toString() }
        catch (e: Exception) { throw AssertionError(e) }
    }
    /** The playable sources, best first. Each source is assessed once: a torrent add-on can return a hundred results. */
    fun ranked(sources: List<MediaSource>): List<MediaSource> {
        val ranks: MutableMap<MediaSource, Int> = java.util.IdentityHashMap()
        for (s in sources) { val a = assess(s); if (a.usable) ranks[s] = a.rank }
        val result: MutableList<MediaSource> = ArrayList(); for (s in sources) if (ranks.containsKey(s)) result.add(s)
        result.sortWith(Comparator.comparingInt { s: MediaSource -> ranks[s]!! }.reversed()); return result
    }
    /** Media3's actual renderer capabilities validate codecs, including software and audio passthrough.
     * Only our separate resolution budget belongs here; MediaCodec-only checks reject working extensions. */
    fun withinDeviceProfile(format: Format?): Boolean {
        if (format == null || format.sampleMimeType == null) return true
        return !format.sampleMimeType!!.startsWith("video/") || (format.width <= 1920 && format.height <= 1080)
    }
    /** Stable across refreshed signed URLs, without carrying credentials in intents or diagnostics. */
    fun key(s: MediaSource): String {
        val identity = s.provider + "\n" + s.name + "\n" + s.kind + "\n" + s.infoHash + "\n" + s.fileIndex + "\n" + s.filename
        return digest(identity)
    }
    fun video(file: TorrentFile): Boolean { return file.size > 0 && file.name().lowercase(Locale.ROOT).matches(".*\\.(mp4|m4v|mkv|webm|avi|ts|m2ts|mov|mpg|mpeg)".toRegex()) }
    /** null means an explicit file choice is required. An explicit invalid index is always an error. */
    @Throws(TorrentException::class)
    fun select(files: List<TorrentFile>, explicit: Int, season: Int, episode: Int): TorrentFile? {
        if (explicit >= 0) {
            for (file in files) if (file.index == explicit && video(file)) return file
            throw TorrentException(TorrentException.INVALID_FILE, "The advertised file index is unavailable")
        }
        val videos: MutableList<TorrentFile> = ArrayList(); for (file in files) if (video(file)) videos.add(file)
        if (videos.size == 1) return videos[0]
        if (season >= 0 && episode >= 0) {
            val pattern = Pattern.compile("(?i)(?:^|[^a-z0-9])(?:s0*" + season + "[ ._-]*e0*" + episode + "|0*" + season + "x0*" + episode + ")(?:[^0-9]|$)")
            var found: TorrentFile? = null
            for (file in videos) if (pattern.matcher(file.name()).find()) { if (found != null) return null; found = file }
            return found
        }
        return null
    }
}
