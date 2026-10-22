package tv.daniil.playback;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which video and audio formats this TV decodes, so Daniil Cast's Mac converts only what the TV can't play and
 * sources are chosen before playback rather than after a failure.
 */
@OptIn(markerClass = UnstableApi.class)
public final class Capabilities {
    /** Names shared with the Chrome extension, by MIME type. */
    private static final Map<String, String> VIDEO = new LinkedHashMap<>();
    private static final Map<String, String> AUDIO = new LinkedHashMap<>();

    static {
        VIDEO.put(MimeTypes.VIDEO_H264, "H.264");
        VIDEO.put(MimeTypes.VIDEO_H265, "HEVC");
        VIDEO.put(MimeTypes.VIDEO_VP9, "VP9");
        VIDEO.put(MimeTypes.VIDEO_AV1, "AV1");
        VIDEO.put(MimeTypes.VIDEO_DOLBY_VISION, "Dolby Vision");
        AUDIO.put(MimeTypes.AUDIO_AAC, "AAC");
        AUDIO.put(MimeTypes.AUDIO_OPUS, "Opus");
        AUDIO.put(MimeTypes.AUDIO_AC3, "AC-3");
        AUDIO.put(MimeTypes.AUDIO_E_AC3, "E-AC-3");
        AUDIO.put(MimeTypes.AUDIO_MPEG, "MP3");
    }

    private static Set<String> blocked = Collections.emptySet();
    @Nullable private static JSONObject cached;

    private Capabilities() {}

    /**
     * Debug builds can hide decoders, so an emulator behaves like the Fire TV Stick, which has no AV1 decoder:
     * {@code files/debug.json} with {@code {"blockDecoders": ["video/av01"]}}.
     */
    public static synchronized void load(Context context) {
        Set<String> list = new HashSet<>();
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            try {
                File file = new File(context.getFilesDir(), "debug.json");
                if (file.exists()) {
                    JSONArray items = new JSONObject(new String(Files.readAllBytes(file.toPath()),
                            StandardCharsets.UTF_8)).optJSONArray("blockDecoders");
                    for (int i = 0; items != null && i < items.length(); i++) list.add(items.optString(i));
                }
            } catch (Exception ignored) { /* No simulation then. */ }
        }
        blocked = list;
        cached = null;
    }

    /** Media3's decoder choice without the hidden decoders. */
    public static MediaCodecSelector selector() {
        return (mime, secure, tunneling) -> blocked.contains(mime) ? Collections.emptyList()
                : MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling);
    }

    public static boolean decodes(@Nullable String codec) {
        if (codec == null || codec.isEmpty()) return true;
        try {
            JSONArray video = json().getJSONArray("video");
            for (int i = 0; i < video.length(); i++) if (codec.equalsIgnoreCase(video.getString(i))) return true;
        } catch (Exception ignored) { /* Unknown means try. */ }
        return false;
    }

    /** The display name of a MIME type, such as "AV1" for video/av01, or the type itself. */
    public static String name(@Nullable String mime) {
        if (mime == null) return "";
        String name = VIDEO.containsKey(mime) ? VIDEO.get(mime) : AUDIO.get(mime);
        return name != null ? name : mime.replaceFirst("^\\w+/", "").toUpperCase(Locale.US);
    }

    /** {"video": ["H.264", "VP9"…], "audio": ["AAC"…]}. */
    public static synchronized JSONObject json() throws Exception {
        if (cached != null) return cached;
        Set<String> found = new HashSet<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) found.add(type.toLowerCase(Locale.US));
        }
        found.removeAll(blocked);
        JSONArray video = new JSONArray(), audio = new JSONArray();
        for (Map.Entry<String, String> entry : VIDEO.entrySet()) if (found.contains(entry.getKey())) video.put(entry.getValue());
        for (Map.Entry<String, String> entry : AUDIO.entrySet()) if (found.contains(entry.getKey())) audio.put(entry.getValue());
        cached = new JSONObject().put("video", video).put("audio", audio);
        return cached;
    }
}
