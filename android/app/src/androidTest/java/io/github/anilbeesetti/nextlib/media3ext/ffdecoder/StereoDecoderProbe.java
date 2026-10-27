package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.extractor.Ac3Util;
import static org.junit.Assert.*;

/** Package-local decoder access for real JNI PCM validation; excluded from production APKs. */
public final class StereoDecoderProbe {
    private StereoDecoderProbe() {}
    public static void check(String address) throws Exception {
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(address).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        byte[] frame;
        try (java.io.DataInputStream input = new java.io.DataInputStream(connection.getInputStream())) {
            byte[] header = new byte[4]; input.readFully(header);
            assertEquals(0x0b, header[0] & 255); assertEquals(0x77, header[1] & 255);
            int size = ((((header[2] & 7) << 8) | (header[3] & 255)) + 1) * 2;
            frame = new byte[size]; System.arraycopy(header, 0, frame, 0, header.length);
            input.readFully(frame, header.length, frame.length - header.length);
        } finally { connection.disconnect(); }
        Ac3Util.SyncFrameInfo info = Ac3Util.parseAc3SyncframeInfo(new ParsableBitArray(frame));
        assertEquals("audio/eac3", info.mimeType); assertEquals(6, info.channelCount);
        FfmpegAudioDecoder decoder = new FfmpegAudioDecoder(
                new Format.Builder().setSampleMimeType(info.mimeType).setChannelCount(info.channelCount).setSampleRate(info.sampleRate).build(), 4, 4, 65536, false, true);
        try {
            androidx.media3.decoder.DecoderInputBuffer input = decoder.dequeueInputBuffer(); assertNotNull(input);
            input.ensureSpaceForWrite(frame.length); input.data.put(frame); input.flip(); decoder.queueInputBuffer(input);
            long deadline = android.os.SystemClock.elapsedRealtime() + 10000; boolean pcm = false;
            while (android.os.SystemClock.elapsedRealtime() < deadline && !pcm) {
                androidx.media3.decoder.SimpleDecoderOutputBuffer output = decoder.dequeueOutputBuffer();
                if (output != null) { pcm = output.data != null && output.data.remaining() > 0; output.release(); }
                android.os.SystemClock.sleep(5);
            }
            assertTrue("Decoded PCM samples", pcm); assertEquals("Downmixed output", 2, decoder.getChannelCount());
        } finally { decoder.release(); }
    }
}
