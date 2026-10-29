package tv.stream.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.stream.app.addon.AddonException
import tv.stream.torrent.TorrentException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiagnosticReportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test @Throws(Exception::class) fun preservesOriginalCausesCodesAndFrozenState() {
        val root = TorrentException(TorrentException.METADATA_FAILED, "metadata fetch failed: unexpected end of file")
        val error = AddonException(AddonException.Kind.HTTP, "HTTP 503 from provider", 503, root)
        val state = JSONObject().put("peers", 0).put("positionMs", 12345)
        val report = DiagnosticReport.capture(context, "Opening source", "Could not play", "Friendly message", "opening", error, state)
        state.put("peers", 99)
        val json = JSONObject(report.text)
        assertEquals("opening", json.getJSONObject("error").getString("code"))
        assertEquals(0, json.getJSONObject("state").getInt("peers"))
        assertEquals(503, json.getJSONArray("causes").getJSONObject(0).getInt("httpStatus"))
        assertEquals(TorrentException.METADATA_FAILED, json.getJSONArray("causes").getJSONObject(1).getInt("torrentKind"))
        assertTrue(report.text.contains("unexpected end of file"))
        // The stack frames name this source file, now Kotlin.
        assertTrue(report.text.contains("DiagnosticReportTest.kt"))
    }

    @Test @Throws(Exception::class) fun redactsAddressesHeadersSecretsAndPrivatePathsEverywhere() {
        val privateText = ("https://user:pass@provider.example/config-secret/stream?token=query-secret "
                + "magnet:?xt=urn:btih:privatehash /data/user/0/tv.stream.app/private-file C:\\Users\\Owner\\private.torrent\n"
                + "Authorization: Bearer header-secret\nCookie: session=cookie-secret\npassword=\"two word secret\" api_key=key-secret")
        val report = DiagnosticReport.capture(context, "provider", "Failed", privateText, "network",
                IllegalStateException(privateText), JSONObject().put("nested", JSONObject().put("detail", privateText)))
        for (secret in arrayOf("provider.example", "config-secret", "query-secret", "privatehash", "private-file", "Owner", "header-secret", "cookie-secret", "two word secret", "key-secret"))
            assertFalse(secret, report.text.contains(secret))
        assertTrue(report.text.contains("redacted"))
        JSONObject(report.text) // Redaction must preserve valid JSON.
    }

    @Test @Throws(Exception::class) fun everyUnicodePageDecodesAndReassemblesWithoutLosingBytes() {
        val longMessage = StringBuilder()
        for (i in 0 until 240) longMessage.append("Ошибка 🛠 ")
        val report = DiagnosticReport.capture(context, "decode", "Failed", longMessage.toString(), "decoder", null, null)
        assertTrue(report.pages.size > 1)
        val joined = StringBuilder()
        for (page in 0 until report.pages.size) {
            val payload: String = report.pages.get(page)
            assertTrue(payload.startsWith("Stream error " + report.id + " — " + (page + 1) + "/" + report.pages.size))
            val matrix: BitMatrix = ErrorQrPanel.encode(payload)
            val bounds = matrix.enclosingRectangle
            val symbol = BitMatrix(bounds[2])
            for (y in 0 until bounds[3]) for (x in 0 until bounds[2]) if (matrix.get(x + bounds[0], y + bounds[1])) symbol.set(x, y)
            assertEquals(payload, Decoder().decode(symbol).text)
            val chunk = payload.substring(payload.indexOf('\n', payload.indexOf('\n') + 1) + 1)
            assertTrue(chunk.toByteArray(StandardCharsets.UTF_8).size <= DiagnosticReport.PAGE_BYTES)
            joined.append(chunk)
        }
        assertEquals(report.text, joined.toString())
    }
}
