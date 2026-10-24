package tv.daniil.stream;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.Decoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import tv.daniil.stream.addon.AddonException;
import tv.daniil.torrent.TorrentException;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class DiagnosticReportTest {
    private final Context context = ApplicationProvider.getApplicationContext();

    @Test public void preservesOriginalCausesCodesAndFrozenState() throws Exception {
        TorrentException root = new TorrentException(TorrentException.METADATA_FAILED, "metadata fetch failed: unexpected end of file");
        AddonException error = new AddonException(AddonException.Kind.HTTP, "HTTP 503 from provider", 503, root);
        JSONObject state = new JSONObject().put("peers", 0).put("positionMs", 12345);
        DiagnosticReport report = DiagnosticReport.capture(context, "Opening source", "Could not play", "Friendly message", "opening", error, state);
        state.put("peers", 99);
        JSONObject json = new JSONObject(report.text);
        assertEquals("opening", json.getJSONObject("error").getString("code"));
        assertEquals(0, json.getJSONObject("state").getInt("peers"));
        assertEquals(503, json.getJSONArray("causes").getJSONObject(0).getInt("httpStatus"));
        assertEquals(TorrentException.METADATA_FAILED, json.getJSONArray("causes").getJSONObject(1).getInt("torrentKind"));
        assertTrue(report.text.contains("unexpected end of file"));
        assertTrue(report.text.contains("DiagnosticReportTest.java"));
    }

    @Test public void redactsAddressesHeadersSecretsAndPrivatePathsEverywhere() throws Exception {
        String privateText = "https://user:pass@provider.example/config-secret/stream?token=query-secret "
                + "magnet:?xt=urn:btih:privatehash /data/user/0/tv.daniil.stream/private-file C:\\Users\\Owner\\private.torrent\n"
                + "Authorization: Bearer header-secret\nCookie: session=cookie-secret\npassword=\"two word secret\" api_key=key-secret";
        DiagnosticReport report = DiagnosticReport.capture(context, "provider", "Failed", privateText, "network",
                new IllegalStateException(privateText), new JSONObject().put("nested", new JSONObject().put("detail", privateText)));
        for (String secret : new String[]{"provider.example", "config-secret", "query-secret", "privatehash", "private-file", "Owner", "header-secret", "cookie-secret", "two word secret", "key-secret"})
            assertFalse(secret, report.text.contains(secret));
        assertTrue(report.text.contains("redacted"));
        new JSONObject(report.text); // Redaction must preserve valid JSON.
    }

    @Test public void everyUnicodePageDecodesAndReassemblesWithoutLosingBytes() throws Exception {
        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 240; i++) longMessage.append("Ошибка 🛠 ");
        DiagnosticReport report = DiagnosticReport.capture(context, "decode", "Failed", longMessage.toString(), "decoder", null, null);
        assertTrue(report.pages.size() > 1);
        StringBuilder joined = new StringBuilder();
        for (int page = 0; page < report.pages.size(); page++) {
            String payload = report.pages.get(page);
            assertTrue(payload.startsWith("Daniil TV error " + report.id + " — " + (page + 1) + "/" + report.pages.size()));
            BitMatrix matrix = ErrorQrPanel.encode(payload);
            int[] bounds = matrix.getEnclosingRectangle();
            BitMatrix symbol = new BitMatrix(bounds[2]);
            for (int y = 0; y < bounds[3]; y++) for (int x = 0; x < bounds[2]; x++) if (matrix.get(x + bounds[0], y + bounds[1])) symbol.set(x, y);
            assertEquals(payload, new Decoder().decode(symbol).getText());
            String chunk = payload.substring(payload.indexOf('\n', payload.indexOf('\n') + 1) + 1);
            assertTrue(chunk.getBytes(StandardCharsets.UTF_8).length <= DiagnosticReport.PAGE_BYTES);
            joined.append(chunk);
        }
        assertEquals(report.text, joined.toString());
    }
}
