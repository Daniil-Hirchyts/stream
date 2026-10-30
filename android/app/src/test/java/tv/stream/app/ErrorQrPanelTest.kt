package tv.stream.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import tv.stream.app.ui.Tv

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ErrorQrPanelTest {
    @get:org.junit.Rule val composition = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    @Test @Throws(Exception::class) fun developerGateScannableTvLayoutAndPointerPagination() {
        Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            activity.setTheme(R.style.TvTheme)
            activity.setContentView(R.layout.activity_watch)
            val layers = tv.stream.app.ui.ComposePlayerLayers(activity, true, {})
            layers.install()
            layers.render(tv.stream.playback.PlayerScreen.LayerState(errorTitle = "Could not play this source",
                    errorMessage = "The provider's certificate can't be verified.", retry = true, sources = true))
            activity.findViewById<View>(R.id.error).visibility = View.VISIBLE
            val report = DiagnosticReport.capture(activity, "Preparing video", "Could not play this source", "Certificate verification failed", "certificate",
                    javax.net.ssl.SSLHandshakeException("Trust anchor for certification path not found"), JSONObject().put("positionMs", 12000).put("peers", 0))
            val gate = android.widget.FrameLayout(activity)
            ErrorQrPanel.attach(gate, false, report)
            assertEquals(View.GONE, gate.visibility); assertEquals(0, gate.childCount)
            layers.diagnostics(report); composition.waitForIdle(); layout(activity); composition.waitForIdle()
            val container: ViewGroup = activity.findViewById(R.id.error_diagnostics)
            assertEquals(View.VISIBLE, container.visibility)
            var code = waitForCode(container, report.id)
            layout(activity)
            assertEquals(report.pages.get(0), decode(code))
            val visible = Rect(); assertTrue(code.getGlobalVisibleRect(visible))
            assertEquals(code.getHeight(), visible.height()); assertEquals(code.getWidth(), visible.width())
            save(activity.getWindow().getDecorView(), "developer-on.png")
            if (report.pages.size > 1) {
                val next = container.findViewById<View>(R.id.error_qr_next)
                assertTrue(next.isFocusable()); assertTrue(next.performClick())
                code = waitForCode(container, report.id); layout(activity)
                assertEquals(report.pages.get(1), decode(code))
                val previous = container.findViewById<View>(R.id.error_qr_previous)
                assertTrue(previous.requestFocus())
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                code = waitForCode(container, report.id); layout(activity)
                assertEquals(report.pages.get(0), decode(code))
            }
            layers.diagnostics(null); composition.waitForIdle(); layout(activity)
            assertEquals(0, container.getChildCount()); assertEquals(View.GONE, container.getVisibility())
            save(activity.getWindow().getDecorView(), "developer-off.png")
        }
    }

    @Test @Throws(Exception::class) fun reportDialogKeepsTheCodeInsideTheTvScreenAndBackClosesIt() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller: org.robolectric.android.controller.ActivityController<Activity> ->
            val activity = controller.get(); activity.setTheme(R.style.TvTheme)
            val report = DiagnosticReport.capture(activity, "Catalogue", "Unable to load", "Provider failed", "application",
                    IllegalStateException("HTTP response could not be parsed"), null)
            val alert: Tv.Alert = ErrorQrPanel.alert(activity, report); alert.show()
            try {
                val code = waitForCode(alert.surface, report.id)
                val root = alert.dialog.getWindow()!!.getDecorView()
                root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 1920, 1080)
                val visible = Rect(); assertTrue(code.getGlobalVisibleRect(visible))
                assertEquals(code.getHeight(), visible.height()); assertEquals(report.pages.get(0), decode(code))
                assertEquals("No Close footer: Back closes popups", 0, alert.buttons.getChildCount())
                save(root, "report-dialog.png")
                @Suppress("DEPRECATION")
                alert.dialog.onBackPressed()
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
                assertFalse(alert.dialog.isShowing())
            } finally { alert.dialog.dismiss() }
        }
    }

    companion object {
        @Throws(Exception::class)
        private fun waitForCode(root: ViewGroup, id: String): View {
            val until = System.nanoTime() + 5_000_000_000L
            do {
                shadowOf(Looper.getMainLooper()).idle()
                val result = findCode(root, id)
                if (result != null) return result
                Thread.sleep(10)
            } while (System.nanoTime() < until)
            throw AssertionError("QR generation did not complete")
        }
        private fun findCode(root: View, id: String): View? {
            if (root.getContentDescription() != null && root.getContentDescription().toString().startsWith("Error report " + id)) return root
            if (root is ViewGroup) for (i in 0 until root.getChildCount()) {
                val result = findCode(root.getChildAt(i), id); if (result != null) return result
            }
            return null
        }
        private fun layout(activity: Activity) {
            val decor = activity.getWindow().getDecorView()
            decor.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 1920, 1080)
        }
        @Throws(Exception::class)
        private fun decode(code: View): String {
            val bitmap = Bitmap.createBitmap(code.getWidth(), code.getHeight(), Bitmap.Config.ARGB_8888)
            code.draw(Canvas(bitmap)); val pixels = IntArray(bitmap.getWidth() * bitmap.getHeight())
            bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight())
            val text = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.getWidth(), bitmap.getHeight(), pixels)))).getText()
            bitmap.recycle(); return text
        }
        @Throws(Exception::class)
        private fun save(view: View, name: String) {
            val folder = File("build/error-report-snapshots"); folder.mkdirs()
            val bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
            FileOutputStream(File(folder, name)).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bitmap.recycle()
        }
    }
}
