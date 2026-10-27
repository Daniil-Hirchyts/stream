package tv.daniil.stream;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.widget.TextView;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import tv.daniil.stream.ui.Tv;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ErrorQrPanelTest {
    @Test public void developerGateScannableTvLayoutAndPointerPagination() throws Exception {
        try (org.robolectric.android.controller.ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            activity.setTheme(R.style.TvTheme);
            activity.setContentView(R.layout.activity_watch);
            ViewGroup container = activity.findViewById(R.id.error_diagnostics);
            activity.findViewById(R.id.error).setVisibility(View.VISIBLE);
            ((TextView) activity.findViewById(R.id.error_title)).setText("Could not play this source");
            ((TextView) activity.findViewById(R.id.error_message)).setText("The provider's certificate can't be verified.");
            DiagnosticReport report = DiagnosticReport.capture(activity, "Preparing video", "Could not play this source", "Certificate verification failed", "certificate",
                    new javax.net.ssl.SSLHandshakeException("Trust anchor for certification path not found"), new JSONObject().put("positionMs", 12000).put("peers", 0));
            ErrorQrPanel.attach(container, false, report);
            assertEquals(View.GONE, container.getVisibility()); assertEquals(0, container.getChildCount());
            ErrorQrPanel.attach(container, true, report);
            assertEquals(View.VISIBLE, container.getVisibility());
            View code = waitForCode(container, report.id);
            layout(activity);
            assertEquals(report.pages.get(0), decode(code));
            Rect visible = new Rect(); assertTrue(code.getGlobalVisibleRect(visible));
            assertEquals(code.getHeight(), visible.height()); assertEquals(code.getWidth(), visible.width());
            save(activity.getWindow().getDecorView(), "developer-on.png");
            if (report.pages.size() > 1) {
                View next = container.findViewById(R.id.error_qr_next);
                assertTrue(next.isFocusable()); assertTrue(next.performClick());
                code = waitForCode(container, report.id); layout(activity);
                assertEquals(report.pages.get(1), decode(code));
                View previous = container.findViewById(R.id.error_qr_previous);
                assertTrue(previous.requestFocus());
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
                code = waitForCode(container, report.id); layout(activity);
                assertEquals(report.pages.get(0), decode(code));
            }
            ErrorQrPanel.attach(container, false, report); layout(activity);
            assertEquals(0, container.getChildCount()); assertEquals(View.GONE, container.getVisibility());
            save(activity.getWindow().getDecorView(), "developer-off.png");
        }
    }

    @Test public void reportDialogKeepsTheCodeInsideTheTvScreenAndBackClosesIt() throws Exception {
        try (org.robolectric.android.controller.ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get(); activity.setTheme(R.style.TvTheme);
            DiagnosticReport report = DiagnosticReport.capture(activity, "Catalogue", "Unable to load", "Provider failed", "application",
                    new IllegalStateException("HTTP response could not be parsed"), null);
            Tv.Alert alert = ErrorQrPanel.alert(activity, report); alert.show();
            try {
                View code = waitForCode(alert.surface, report.id);
                View root = alert.dialog.getWindow().getDecorView();
                root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, 1920, 1080);
                Rect visible = new Rect(); assertTrue(code.getGlobalVisibleRect(visible));
                assertEquals(code.getHeight(), visible.height()); assertEquals(report.pages.get(0), decode(code));
                assertEquals("No Close footer: Back closes popups", 0, alert.buttons.getChildCount());
                save(root, "report-dialog.png");
                alert.dialog.onBackPressed();
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
                assertFalse(alert.dialog.isShowing());
            } finally { alert.dialog.dismiss(); }
        }
    }

    private static View waitForCode(ViewGroup root, String id) throws Exception {
        long until = System.nanoTime() + 5_000_000_000L;
        do {
            shadowOf(Looper.getMainLooper()).idle();
            View result = findCode(root, id);
            if (result != null) return result;
            Thread.sleep(10);
        } while (System.nanoTime() < until);
        throw new AssertionError("QR generation did not complete");
    }
    private static View findCode(View root, String id) {
        if (root.getContentDescription() != null && root.getContentDescription().toString().startsWith("Error report " + id)) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View result = findCode(((ViewGroup) root).getChildAt(i), id); if (result != null) return result;
        }
        return null;
    }
    private static void layout(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1920, 1080);
    }
    private static String decode(View code) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(code.getWidth(), code.getHeight(), Bitmap.Config.ARGB_8888);
        code.draw(new Canvas(bitmap)); int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        String text = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(bitmap.getWidth(), bitmap.getHeight(), pixels)))).getText();
        bitmap.recycle(); return text;
    }
    private static void save(View view, String name) throws Exception {
        File folder = new File("build/error-report-snapshots"); folder.mkdirs();
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888); view.draw(new Canvas(bitmap));
        try (FileOutputStream out = new FileOutputStream(new File(folder, name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        bitmap.recycle();
    }
}
