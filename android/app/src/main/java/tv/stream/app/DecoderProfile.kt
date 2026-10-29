package tv.stream.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/** A decoder existing and a format being suitable for this display are distinct questions. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object DecoderProfile {
    @Volatile private var hdrDisplay = false
    @Volatile private var hdrTypes = IntArray(0)
    private var application: Context? = null
    @JvmStatic fun load(context: Context) {
        application = context.applicationContext
        val manager = context.getSystemService(DisplayManager::class.java)
        val display = if (manager == null) null else manager.getDisplay(Display.DEFAULT_DISPLAY)
        hdrTypes = if (display == null) IntArray(0) else display.hdrCapabilities.supportedHdrTypes
        hdrDisplay = hdrTypes.size > 0
    }
    @JvmStatic fun hdr(format: Format?): Boolean {
        return format != null && ("video/dolby-vision" == format.sampleMimeType
                || format.colorInfo != null && (format.colorInfo!!.colorTransfer == C.COLOR_TRANSFER_ST2084 || format.colorInfo!!.colorTransfer == C.COLOR_TRANSFER_HLG))
    }
    @JvmStatic fun hardware(mime: String?): Boolean {
        try { return MediaCodecUtil.getDecoderInfos(mime!!, false, false).stream().anyMatch { info -> info.hardwareAccelerated } }
        catch (ignored: Exception) { return false }
    }
    @JvmStatic fun hdrHintSupported(mime: String?): Boolean = hdrDisplay && (mime == null || hardware(mime))
    @JvmStatic fun hdrSupported(format: Format?): Boolean {
        if (!hdr(format)) return true
        if (!hdrDisplay) return false
        format!!
        val wanted = if ("video/dolby-vision" == format.sampleMimeType) Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
                else if (format.colorInfo != null && format.colorInfo!!.colorTransfer == C.COLOR_TRANSFER_HLG) Display.HdrCapabilities.HDR_TYPE_HLG else Display.HdrCapabilities.HDR_TYPE_HDR10
        var displaySupports = false
        for (type in hdrTypes) if (type == wanted || wanted == Display.HdrCapabilities.HDR_TYPE_HDR10 && type == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) displaySupports = true
        if (!displaySupports) return false
        try { return MediaCodecUtil.getDecoderInfos(format.sampleMimeType!!, false, false).stream().anyMatch { info ->
            try { info.hardwareAccelerated && info.isFormatSupported(application!!, format) } catch (ignored: Exception) { false }
        } } catch (ignored: Exception) { return false }
    }
}
