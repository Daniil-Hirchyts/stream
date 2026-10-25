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
    private static final Pattern ABOVE_PROFILE = Pattern.compile("\\b(2160p|4k|8k|uhd|4320p)\\b");
    private static final Pattern HDR_HINT = Pattern.compile("\\b(?:hdr(?:10(?:\\+|plus)?)?|hlg|dovi|dolby[ ._-]?vision|dv)\\b");
    private static final Pattern COMMON_AUDIO = Pattern.compile("\\b(?:aac|opus|mp3)(?=[^a-z]|$)");
    private static final Pattern TRUEHD_AUDIO = Pattern.compile("\\btrue[ ._-]?hd\\b");
    private static final Pattern DTS_AUDIO = Pattern.compile("\\bdts(?=[^a-z]|$)");
    private static final Pattern EAC3_AUDIO = Pattern.compile("\\b(?:e[ ._-]?ac[ ._-]?3|ddp(?:lus)?|dd\\+)(?=[^a-z]|$)|\\bdolby[ ._-]+digital[ ._-]+plus\\b");
    private static final Pattern AC3_AUDIO = Pattern.compile("\\b(?:ac[ ._-]?3|dd)(?=[^a-z]|$)|\\bdolby[ ._-]+digital\\b");
    private static final Pattern TEN_BIT = Pattern.compile("\\b(?:10[ ._-]?bit|hi10p|main[ ._-]?10)\\b");
    private static final Pattern DOLBY_VISION = Pattern.compile("\\b(?:dovi|dolby[ ._-]?vision|dv)\\b");
    private static final Pattern AV1_HINT = Pattern.compile("\\b(av1|av01)\\b");
    private static final Pattern HEVC_HINT = Pattern.compile("\\b(hevc|h[ ._-]?265|x265)\\b");
    private static final Pattern VP9_HINT = Pattern.compile("\\b(vp9|vp09)\\b");
    private static final Pattern AVC_HINT = Pattern.compile("\\b(avc|h[ ._-]?264|x264)\\b");
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
        if (ABOVE_PROFILE.matcher(hint).find()) return new Assessment(false, "Above the 1080p device profile", -1000);
        String codec = AV1_HINT.matcher(hint).find() ? "video/av01" : HEVC_HINT.matcher(hint).find() ? "video/hevc"
                : VP9_HINT.matcher(hint).find() ? "video/x-vnd.on2.vp9" : AVC_HINT.matcher(hint).find() ? "video/avc" : null;
        String audio = advertisedAudio(hint);
        try {
            if (codec != null && !StreamDecoders.supports(codec)
                    && (MediaCodecUtil.getDecoderInfos(codec, false, false).isEmpty() || !Capabilities.decodes(Capabilities.name(codec)))) return new Assessment(false, "Video decoder unavailable", -1000);
            if (audio != null && !StreamDecoders.supports(audio) && MediaCodecUtil.getDecoderInfos(audio, false, false).isEmpty()) return new Assessment(false, "Audio decoder unavailable", -1000);
            if (codec != null && !advertisedProfileSupported(codec, hint))
                return new Assessment(false, "Advertised video profile unavailable", -1000);
            boolean hdr = HDR_HINT.matcher(hint).find();
            if (hdr && !DecoderProfile.hdrHintSupported(codec)) return new Assessment(false, "HDR needs a compatible hardware decoder and display", -1000);
            int rank = hint.contains("1080p") ? 20 : hint.contains("720p") ? 10 : 0;
            // Automatic Play favours the broadly supported AVC path over less certain high-bit-depth releases.
            if ("video/avc".equals(codec)) rank += 25;
            if (COMMON_AUDIO.matcher(hint).find()) rank += 5;
            SourceSort.Facts facts = SourceSort.facts(source);
            rank += PlaybackOutcomes.rank(source)
                    + (facts.seeds > 0 ? 30 : facts.seeds == 0 ? -30 : 0)
                    - (int) Math.min(200, facts.bytes / (256L << 20));
            if (codec == null) return new Assessment(true, "Compatibility unknown; checked when opened", rank);
            boolean hardware = MediaCodecUtil.getDecoderInfos(codec, false, false).stream().anyMatch(info -> info.hardwareAccelerated);
            return new Assessment(true, hardware ? "Supported hardware decoder; format checked when opened" : "Supported decoder; format checked when opened", rank + (hardware ? 2000 : 1000));
        } catch (Exception e) { return new Assessment(true, "Compatibility unknown; checked when opened", PlaybackOutcomes.rank(source)); }
    }
    /** Codec labels often touch channel counts: DDP5.1 and DD+5.1 are E-AC-3, not unknown audio. */
    static String advertisedAudio(String hint) {
        String text = hint.toLowerCase(Locale.ROOT);
        // A release advertising a common alternative can still contain a supported selectable audio track.
        if (COMMON_AUDIO.matcher(text).find()) return null;
        if (TRUEHD_AUDIO.matcher(text).find()) return "audio/true-hd";
        if (DTS_AUDIO.matcher(text).find()) return "audio/vnd.dts";
        if (EAC3_AUDIO.matcher(text).find()) return "audio/eac3";
        if (AC3_AUDIO.matcher(text).find()) return "audio/ac3";
        return null;
    }
    private static boolean advertisedProfileSupported(String mime, String hint) throws Exception {
        boolean tenBit = TEN_BIT.matcher(hint).find();
        boolean dolby = DOLBY_VISION.matcher(hint).find();
        if (dolby && MediaCodecUtil.getDecoderInfos("video/dolby-vision", false, false).isEmpty()) return false;
        if (!tenBit) return true;
        if (StreamDecoders.supports(mime)) return true;
        int wanted = "video/avc".equals(mime) ? android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
                : "video/hevc".equals(mime) ? android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                : "video/x-vnd.on2.vp9".equals(mime) ? android.media.MediaCodecInfo.CodecProfileLevel.VP9Profile2
                : "video/av01".equals(mime) ? android.media.MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 : 0;
        if (wanted == 0) return true;
        for (android.media.MediaCodecInfo decoder : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (decoder.isEncoder()) continue;
            for (String type : decoder.getSupportedTypes()) if (type.equalsIgnoreCase(mime))
                for (android.media.MediaCodecInfo.CodecProfileLevel profile : decoder.getCapabilitiesForType(type).profileLevels)
                    if (profile.profile == wanted || tenBitHdrProfile(mime, profile.profile)) return true;
        }
        return false;
    }

    private static boolean tenBitHdrProfile(String mime, int profile) {
        return "video/hevc".equals(mime) && (profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                || profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
                || "video/x-vnd.on2.vp9".equals(mime) && (profile == android.media.MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR
                || profile == android.media.MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus)
                || "video/av01".equals(mime) && (profile == android.media.MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10
                || profile == android.media.MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus);
    }

    /** Give another installed provider a chance before spending the budget on one provider's entire list. */
    public static int nextCandidate(List<MediaSource> ranked, java.util.Set<String> attempted, java.util.Set<String> providers) {
        for (boolean differentProvider : new boolean[]{true, false})
            for (int i = 0; i < ranked.size(); i++) {
                MediaSource source = ranked.get(i);
                if (attempted.contains(retryKey(source)) || (differentProvider && providers.contains(source.provider))) continue;
                return i;
            }
        return -1;
    }

    /** Recovery identity is payload/file, not the addon or release label advertising it. Never persisted as an address. */
    public static String retryKey(MediaSource source) {
        String hash = source.infoHash;
        if (hash == null && source.kind == MediaSource.Kind.MAGNET) {
            String query = java.net.URI.create(source.url).getRawSchemeSpecificPart();
            for (String pair : query.substring(1).split("&")) {
                String[] fields = pair.split("=", 2);
                if (fields.length == 2 && fields[0].equals("xt")) {
                    String xt;
                    try { xt = java.net.URLDecoder.decode(fields[1], "UTF-8"); }
                    catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
                    if (xt.regionMatches(true, 0, "urn:btih:", 0, 9)) hash = xt.substring(9);
                    else if (xt.regionMatches(true, 0, "urn:btmh:1220", 0, 13)) hash = xt.substring(13);
                    if (hash != null) break;
                }
            }
        }
        String identity = hash == null ? source.kind + "\n" + source.url + "\n" + new java.util.TreeMap<>(source.requestHeaders)
                : "torrent:" + canonicalHash(hash) + ":" + source.fileIndex;
        return digest(identity);
    }
    private static String canonicalHash(String hash) {
        if (hash.matches("(?i)[a-z2-7]{32}")) {
            String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
            StringBuilder hex = new StringBuilder(); int bits = 0, value = 0;
            for (char c : hash.toUpperCase(Locale.ROOT).toCharArray()) {
                value = (value << 5) | alphabet.indexOf(c); bits += 5;
                if (bits >= 8) { bits -= 8; hex.append(String.format(Locale.ROOT, "%02x", (value >> bits) & 255)); }
            }
            return hex.toString();
        }
        return hash.toLowerCase(Locale.ROOT);
    }
    private static String digest(String identity) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(); for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 255)); return result.toString(); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    /** The playable sources, best first. Each source is assessed once: a torrent add-on can return a hundred results. */
    public static List<MediaSource> ranked(List<MediaSource> sources) {
        java.util.Map<MediaSource, Integer> ranks = new java.util.IdentityHashMap<>();
        for (MediaSource s : sources) { Assessment a = assess(s); if (a.usable) ranks.put(s, a.rank); }
        List<MediaSource> result = new ArrayList<>(); for (MediaSource s : sources) if (ranks.containsKey(s)) result.add(s);
        result.sort(Comparator.comparingInt((MediaSource s) -> ranks.get(s)).reversed()); return result;
    }
    /** Media3's actual renderer capabilities validate codecs, including software and audio passthrough.
     * Only our separate resolution budget belongs here; MediaCodec-only checks reject working extensions. */
    public static boolean withinDeviceProfile(Format format) {
        if (format == null || format.sampleMimeType == null) return true;
        return !format.sampleMimeType.startsWith("video/") || (format.width <= 1920 && format.height <= 1080);
    }
    /** Stable across refreshed signed URLs, without carrying credentials in intents or diagnostics. */
    public static String key(MediaSource s) {
        String identity = s.provider + "\n" + s.name + "\n" + s.kind + "\n" + s.infoHash + "\n" + s.fileIndex + "\n" + s.filename;
        return digest(identity);
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
