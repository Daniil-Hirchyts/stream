package tv.stream.playback

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.MediaCodecInfo
import android.media.MediaCodecList

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

import org.json.JSONArray
import org.json.JSONObject

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Which video and audio formats this TV decodes, so sources can be chosen before playback.
 */
@OptIn(markerClass = [UnstableApi::class])
object Capabilities {
    /** Codec names indexed by MIME type. */
    private val VIDEO: MutableMap<String, String> = LinkedHashMap()
    private val AUDIO: MutableMap<String, String> = LinkedHashMap()

    init {
        VIDEO[MimeTypes.VIDEO_H264] = "H.264"
        VIDEO[MimeTypes.VIDEO_H265] = "HEVC"
        VIDEO[MimeTypes.VIDEO_VP9] = "VP9"
        VIDEO[MimeTypes.VIDEO_AV1] = "AV1"
        VIDEO[MimeTypes.VIDEO_DOLBY_VISION] = "Dolby Vision"
        AUDIO[MimeTypes.AUDIO_AAC] = "AAC"
        AUDIO[MimeTypes.AUDIO_OPUS] = "Opus"
        AUDIO[MimeTypes.AUDIO_AC3] = "AC-3"
        AUDIO[MimeTypes.AUDIO_E_AC3] = "E-AC-3"
        AUDIO[MimeTypes.AUDIO_MPEG] = "MP3"
    }

    private var blocked: Set<String> = Collections.emptySet()
    private var cached: JSONObject? = null

    /**
     * Debug builds can hide decoders, so an emulator behaves like the Fire TV Stick, which has no AV1 decoder:
     * `files/debug.json` with `{"blockDecoders": ["video/av01"]}`.
     */
    @JvmStatic
    @Synchronized
    fun load(context: Context) {
        val list: MutableSet<String> = HashSet()
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            try {
                val file = File(context.filesDir, "debug.json")
                if (file.exists()) {
                    val items: JSONArray? = JSONObject(String(Files.readAllBytes(file.toPath()),
                            StandardCharsets.UTF_8)).optJSONArray("blockDecoders")
                    var i = 0
                    while (items != null && i < items.length()) {
                        list.add(items.optString(i))
                        i++
                    }
                }
            } catch (ignored: Exception) { /* No simulation then. */ }
        }
        blocked = list
        cached = null
    }

    /** Media3's decoder choice without the hidden decoders. */
    @JvmStatic
    fun selector(): MediaCodecSelector {
        return MediaCodecSelector { mime, secure, tunneling ->
            if (blocked.contains(mime)) Collections.emptyList()
            else MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
        }
    }

    @JvmStatic
    fun decodes(codec: String?): Boolean {
        if (codec == null || codec.isEmpty()) return true
        try {
            val video = json().getJSONArray("video")
            for (i in 0 until video.length()) if (codec.equals(video.getString(i), ignoreCase = true)) return true
        } catch (ignored: Exception) { /* Unknown means try. */ }
        return false
    }

    /** The display name of a MIME type, such as "AV1" for video/av01, or the type itself. */
    @JvmStatic
    fun name(mime: String?): String {
        if (mime == null) return ""
        val name = if (VIDEO.containsKey(mime)) VIDEO[mime] else AUDIO[mime]
        return name ?: mime.replaceFirst(Regex("^\\w+/"), "").uppercase(Locale.US)
    }

    /** {"video": ["H.264", "VP9"…], "audio": ["AAC"…]}. */
    @JvmStatic
    @Synchronized
    @Throws(Exception::class)
    fun json(): JSONObject {
        if (cached != null) return cached!!
        val found: MutableSet<String> = HashSet()
        for (info: MediaCodecInfo in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) found.add(type.lowercase(Locale.US))
        }
        found.removeAll(blocked)
        val video = JSONArray()
        val audio = JSONArray()
        for ((key, value) in VIDEO) if (found.contains(key)) video.put(value)
        for ((key, value) in AUDIO) if (found.contains(key)) audio.put(value)
        cached = JSONObject().put("video", video).put("audio", audio)
        return cached!!
    }
}
