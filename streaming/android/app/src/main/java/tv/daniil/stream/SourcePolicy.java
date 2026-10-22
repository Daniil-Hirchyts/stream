package tv.daniil.stream;

import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tv.daniil.playback.Capabilities;
import tv.daniil.stream.addon.MediaSource;
import tv.daniil.torrent.TorrentException;
import tv.daniil.torrent.TorrentFile;

/** Conservative advertised hints, followed by actual Media3 format/decoder validation. No download side effects. */
@OptIn(markerClass = UnstableApi.class)
public final class SourcePolicy {
    private SourcePolicy() {}
    public static final class Assessment {
        public final boolean usable;
        public final String label;
        final int rank;
        Assessment(boolean usable, String label, int rank) { this.usable = usable; this.label = label; this.rank = rank; }
    }
    public static Assessment assess(MediaSource source) {
        if (!source.playable()) return new Assessment(false, source.reason, -1000);
        String hint = (source.name + " " + source.description + " " + (source.filename == null ? "" : source.filename)).toLowerCase(Locale.ROOT);
        if (Pattern.compile("\\b(2160p|4k|8k|uhd|4320p)\\b").matcher(hint).find()) return new Assessment(false, "Above the 1080p device profile", -1000);
        String codec = hint.matches("(?s).*\\b(av1|av01)\\b.*") ? "video/av01" : hint.matches("(?s).*\\b(hevc|h[ ._-]?265|x265)\\b.*") ? "video/hevc"
                : hint.matches("(?s).*\\b(vp9|vp09)\\b.*") ? "video/x-vnd.on2.vp9" : hint.matches("(?s).*\\b(avc|h[ ._-]?264|x264)\\b.*") ? "video/avc" : null;
        String audio = hint.matches("(?s).*\\b(truehd)\\b.*") ? "audio/true-hd" : hint.matches("(?s).*\\b(dts)\\b.*") ? "audio/vnd.dts" : null;
        try {
            if (codec != null && (MediaCodecUtil.getDecoderInfos(codec, false, false).isEmpty() || !Capabilities.decodes(Capabilities.name(codec)))) return new Assessment(false, "Video decoder unavailable", -1000);
            if (audio != null && MediaCodecUtil.getDecoderInfos(audio, false, false).isEmpty()) return new Assessment(false, "Audio decoder unavailable", -1000);
            int rank = hint.contains("1080p") ? 20 : hint.contains("720p") ? 10 : 0;
            if (codec == null) return new Assessment(true, "Compatibility unknown; checked when opened", rank);
            boolean hardware = MediaCodecUtil.getDecoderInfos(codec, false, false).stream().anyMatch(info -> info.hardwareAccelerated);
            return new Assessment(true, hardware ? "Supported hardware decoder; format checked when opened" : "Supported decoder; format checked when opened", rank + (hardware ? 40 : 5));
        } catch (Exception e) { return new Assessment(true, "Compatibility unknown; checked when opened", 0); }
    }
    public static List<MediaSource> ranked(List<MediaSource> sources) {
        List<MediaSource> result = new ArrayList<>(); for (MediaSource s : sources) if (assess(s).usable) result.add(s);
        result.sort(Comparator.comparingInt((MediaSource s) -> assess(s).rank).reversed()); return result;
    }
    public static boolean actualSupported(android.content.Context context, Format format) {
        if (format == null || format.sampleMimeType == null) return true;
        if (format.sampleMimeType.startsWith("video/") && (format.width > 1920 || format.height > 1080)) return false;
        try { for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo decoder : MediaCodecUtil.getDecoderInfos(format.sampleMimeType, false, false)) if (decoder.isFormatSupported(context, format)) return true; }
        catch (Exception ignored) { /* report unsupported rather than assuming */ }
        return false;
    }
    /** Stable across refreshed signed URLs, without carrying credentials in intents or diagnostics. */
    public static String key(MediaSource s) {
        String identity = s.provider + "\n" + s.name + "\n" + s.kind + "\n" + s.infoHash + "\n" + s.fileIndex + "\n" + s.filename;
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(); for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 255)); return result.toString(); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    public static boolean video(TorrentFile file) { return file.size > 0 && file.name().toLowerCase(Locale.ROOT).matches(".*\\.(mp4|m4v|mkv|webm|avi|ts|m2ts|mov|mpg|mpeg)"); }
    /** null means an explicit file choice is required. An explicit invalid index is always an error. */
    public static TorrentFile select(List<TorrentFile> files, int explicit, int season, int episode) throws TorrentException {
        if (explicit >= 0) {
            for (TorrentFile file : files) if (file.index == explicit && video(file)) return file;
            throw new TorrentException(TorrentException.INVALID_FILE, "The advertised file index is unavailable");
        }
        List<TorrentFile> videos = new ArrayList<>(); for (TorrentFile file : files) if (video(file)) videos.add(file);
        if (videos.size() == 1) return videos.get(0);
        if (season >= 0 && episode >= 0) {
            Pattern pattern = Pattern.compile("(?i)(?:^|[^a-z0-9])(?:s0*" + season + "[ ._-]*e0*" + episode + "|0*" + season + "x0*" + episode + ")(?:[^0-9]|$)");
            TorrentFile found = null;
            for (TorrentFile file : videos) if (pattern.matcher(file.name()).find()) { if (found != null) return null; found = file; }
            return found;
        }
        return null;
    }
}
