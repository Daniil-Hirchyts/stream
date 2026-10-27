package tv.stream.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.EnumMap;
import tv.stream.app.ui.Tv;
import tv.stream.app.ui.Sounds;

/** Phone-readable plain text, with no server, upload, network permission change or external QR service. */
final class ErrorQrPanel extends LinearLayout {
    private int page, revision;
    private final DiagnosticReport report;
    private final TextView status;
    private final CodeView image;

    static void attach(ViewGroup container, boolean developerMode, DiagnosticReport report) {
        container.removeAllViews();
        container.setVisibility(developerMode && report != null ? View.VISIBLE : View.GONE);
        if (developerMode && report != null) {
            ErrorQrPanel panel = new ErrorQrPanel(container.getContext(), report);
            panel.setBackgroundResource(R.drawable.tv_dialog);
            container.addView(panel);
        }
    }

    static Tv.Alert alert(Context context, DiagnosticReport report) {
        Tv.Alert alert = Tv.alert(context, "Error report", (CharSequence) null);
        alert.surface.removeAllViews(); alert.surface.setPadding(0, 0, 0, 0);
        alert.surface.addView(new ErrorQrPanel(context, report));
        return alert;
    }

    /** Back or Left closes it, as every popup; it has no Close button of its own. */
    private ErrorQrPanel(Context context, DiagnosticReport report) {
        super(context); this.report = report;
        setOrientation(VERTICAL); setGravity(Gravity.CENTER_HORIZONTAL);
        setClipChildren(false); setClipToPadding(false);
        int inset = Tv.dp(context, 20);
        setPadding(inset, inset, inset, inset);
        LinearLayout header = new LinearLayout(context); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false); header.setClipToPadding(false);
        LinearLayout labels = new LinearLayout(context); labels.setOrientation(VERTICAL);
        labels.addView(Tv.heading(context, "Error report", 18, R.color.tv_text));
        TextView reference = Tv.text(context, report.id, 12, R.color.tv_text_secondary);
        reference.setLetterSpacing(.06f);
        LayoutParams referenceParams = new LayoutParams(-1, -2); referenceParams.topMargin = Tv.dp(context, 3);
        labels.addView(reference, referenceParams);
        header.addView(labels, new LayoutParams(0, -2, 1));
        addView(header, new LayoutParams(-1, -2));
        image = new CodeView(context);
        LayoutParams code = new LayoutParams(Tv.dp(context, 288), Tv.dp(context, 288));
        code.topMargin = Tv.dp(context, 18); addView(image, code);
        LinearLayout pager = new LinearLayout(context); pager.setGravity(Gravity.CENTER_VERTICAL);
        pager.setClipChildren(false); pager.setClipToPadding(false);
        status = Tv.text(context, "", 14, R.color.tv_text_secondary); status.setGravity(Gravity.CENTER);
        if (report.pages.size() > 1) {
            ImageView previous = icon(context, "Previous QR", R.id.error_qr_previous, R.drawable.ic_tv_chevron, () -> change(-1));
            previous.setRotation(180);
            pager.addView(previous, new LayoutParams(Tv.dp(context, 48), Tv.dp(context, 48)));
            pager.addView(status, new LayoutParams(0, -2, 1));
            ImageView next = icon(context, "Next QR", R.id.error_qr_next, R.drawable.ic_tv_chevron, () -> change(1));
            pager.addView(next, new LayoutParams(Tv.dp(context, 48), Tv.dp(context, 48)));
        } else pager.addView(status, new LayoutParams(-1, Tv.dp(context, 32)));
        LayoutParams pagerParams = new LayoutParams(Tv.dp(context, 288), -2);
        pagerParams.topMargin = Tv.dp(context, 16); addView(pager, pagerParams);
        render();
    }

    private static ImageView icon(Context context, String label, int id, int drawable, Runnable action) {
        ImageView button = new ImageView(context);
        button.setId(id); button.setImageResource(drawable); button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setImageTintList(context.getColorStateList(R.color.tv_on_glass));
        int inset = Tv.dp(context, 13); button.setPadding(inset, inset, inset, inset);
        button.setBackgroundResource(R.drawable.tv_qr_icon_button);
        button.setContentDescription(label);
        Tv.control(button, "qr:" + id, action, ignored -> {}, 1.02f, false);
        return button;
    }

    private void change(int delta) { page = Math.floorMod(page + delta, report.pages.size()); render(); }

    private void render() {
        int mine = ++revision;
        status.setText((page + 1) + " / " + report.pages.size());
        status.setContentDescription("QR " + (page + 1) + " of " + report.pages.size());
        image.matrix = null; image.setContentDescription(null); image.invalidate();
        String payload = report.pages.get(page);
        ((TvApplication) getContext().getApplicationContext()).workers.execute(() -> {
            BitMatrix encoded;
            try { encoded = encode(payload); }
            catch (WriterException | IllegalArgumentException error) {
                post(() -> { if (mine == revision) status.setText("QR unavailable"); }); return;
            }
            post(() -> {
                if (mine != revision) return;
                image.matrix = encoded;
                image.setContentDescription("Error report " + report.id + ", QR " + (page + 1) + " of " + report.pages.size());
                image.invalidate();
            });
        });
    }

    @Override protected void onDetachedFromWindow() { revision++; super.onDetachedFromWindow(); }

    static BitMatrix encode(String payload) throws WriterException {
        EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8"); hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M); hints.put(EncodeHintType.MARGIN, 4);
        return new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints);
    }

    /** Integer module sizes and the four-module white quiet zone survive TV scaling without blurred edges. */
    private static final class CodeView extends View {
        private final Paint paint = new Paint();
        private BitMatrix matrix;
        CodeView(Context context) {
            super(context); paint.setColor(Color.BLACK);
            GradientDrawable paper = new GradientDrawable(); paper.setColor(Color.WHITE); paper.setCornerRadius(Tv.dp(context, 10));
            setBackground(paper); setClipToOutline(true);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (matrix == null) return;
            int size = matrix.getWidth(), scale = Math.min(getWidth(), getHeight()) / size;
            int left = (getWidth() - size * scale) / 2, top = (getHeight() - size * scale) / 2;
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) if (matrix.get(x, y))
                canvas.drawRect(left + x * scale, top + y * scale, left + (x + 1) * scale, top + (y + 1) * scale, paint);
        }
    }
}
