package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.Format
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.extractor.Ac3Util
import org.junit.Assert.*

/** Package-local decoder access for real JNI PCM validation; excluded from production APKs. */
object StereoDecoderProbe {
    @JvmStatic @Throws(Exception::class)
    fun check(address: String) {
        val connection = java.net.URL(address).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 5000; connection.readTimeout = 5000
        val frame: ByteArray
        try {
            frame = java.io.DataInputStream(connection.inputStream).use { input ->
                val header = ByteArray(4); input.readFully(header)
                assertEquals(0x0b.toLong(), (header[0].toInt() and 255).toLong()); assertEquals(0x77.toLong(), (header[1].toInt() and 255).toLong())
                val size = ((((header[2].toInt() and 7) shl 8) or (header[3].toInt() and 255)) + 1) * 2
                val read = ByteArray(size); System.arraycopy(header, 0, read, 0, header.size)
                input.readFully(read, header.size, read.size - header.size)
                read
            }
        } finally { connection.disconnect() }
        val info = Ac3Util.parseAc3SyncframeInfo(ParsableBitArray(frame))
        assertEquals("audio/eac3", info.mimeType); assertEquals(6, info.channelCount.toLong())
        val decoder = FfmpegAudioDecoder(
                Format.Builder().setSampleMimeType(info.mimeType).setChannelCount(info.channelCount).setSampleRate(info.sampleRate).build(), 4, 4, 65536, false, true)
        try {
            val input: androidx.media3.decoder.DecoderInputBuffer? = decoder.dequeueInputBuffer(); assertNotNull(input)
            input!!.ensureSpaceForWrite(frame.size); input.data!!.put(frame); input.flip(); decoder.queueInputBuffer(input)
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000; var pcm = false
            while (android.os.SystemClock.elapsedRealtime() < deadline && !pcm) {
                val output: androidx.media3.decoder.SimpleDecoderOutputBuffer? = decoder.dequeueOutputBuffer()
                if (output != null) { pcm = output.data != null && output.data!!.remaining() > 0; output.release() }
                android.os.SystemClock.sleep(5)
            }
            assertTrue("Decoded PCM samples", pcm); assertEquals("Downmixed output", 2, decoder.getChannelCount().toLong())
        } finally { decoder.release() }
    }
}
