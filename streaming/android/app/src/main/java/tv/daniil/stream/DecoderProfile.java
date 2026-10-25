package tv.daniil.stream;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;

/** A decoder existing and a format being suitable for this display are distinct questions. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class DecoderProfile {
    private static volatile boolean hdrDisplay;
    private static volatile int[] hdrTypes = new int[0];
    private static Context application;
    static void load(Context context) {
        application = context.getApplicationContext();
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
        hdrTypes = display == null ? new int[0] : display.getHdrCapabilities().getSupportedHdrTypes();
        hdrDisplay = hdrTypes.length > 0;
    }
    static boolean hdr(Format format) {
        return format != null && ("video/dolby-vision".equals(format.sampleMimeType)
                || format.colorInfo != null && (format.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084 || format.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG));
    }
    static boolean hardware(String mime) {
        try { return MediaCodecUtil.getDecoderInfos(mime, false, false).stream().anyMatch(info -> info.hardwareAccelerated); }
        catch (Exception ignored) { return false; }
    }
    static boolean hdrHintSupported(String mime) { return hdrDisplay && (mime == null || hardware(mime)); }
    static boolean hdrSupported(Format format) {
        if (!hdr(format)) return true;
        if (!hdrDisplay) return false;
        int wanted = "video/dolby-vision".equals(format.sampleMimeType) ? Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
                : format.colorInfo != null && format.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG ? Display.HdrCapabilities.HDR_TYPE_HLG : Display.HdrCapabilities.HDR_TYPE_HDR10;
        boolean displaySupports = false;
        for (int type : hdrTypes) if (type == wanted || wanted == Display.HdrCapabilities.HDR_TYPE_HDR10 && type == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) displaySupports = true;
        if (!displaySupports) return false;
        try { return MediaCodecUtil.getDecoderInfos(format.sampleMimeType, false, false).stream().anyMatch(info -> {
            try { return info.hardwareAccelerated && info.isFormatSupported(application, format); } catch (Exception ignored) { return false; }
        }); } catch (Exception ignored) { return false; }
    }
}
