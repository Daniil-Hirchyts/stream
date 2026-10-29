package tv.stream.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap
import java.util.function.Consumer
import tv.stream.app.ui.Tv
import tv.stream.app.ui.Sounds

/** Phone-readable plain text, with no server, upload, network permission change or external QR service. */
class ErrorQrPanel : LinearLayout {
    private var page = 0
    private var revision = 0
    private val report: DiagnosticReport
    private val status: TextView
    private val image: CodeView

    companion object {
        @JvmStatic fun attach(container: ViewGroup, developerMode: Boolean, report: DiagnosticReport?) {
            container.removeAllViews()
            container.visibility = if (developerMode && report != null) View.VISIBLE else View.GONE
            if (developerMode && report != null) {
                val panel = ErrorQrPanel(container.context, report)
                panel.setBackgroundResource(R.drawable.tv_dialog)
                container.addView(panel)
            }
        }

        @JvmStatic fun alert(context: Context, report: DiagnosticReport): Tv.Alert {
            val alert = Tv.alert(context, "Error report", null as CharSequence?)
            alert.surface.removeAllViews(); alert.surface.setPadding(0, 0, 0, 0)
            alert.surface.addView(ErrorQrPanel(context, report))
            return alert
        }

        private fun icon(context: Context, label: String, id: Int, drawable: Int, action: Runnable): ImageView {
            val button = ImageView(context)
            button.id = id; button.setImageResource(drawable); button.scaleType = ImageView.ScaleType.CENTER_INSIDE
            button.imageTintList = context.getColorStateList(R.color.tv_on_glass)
            val inset = Tv.dp(context, 13f); button.setPadding(inset, inset, inset, inset)
            button.setBackgroundResource(R.drawable.tv_qr_icon_button)
            button.contentDescription = label
            Tv.control(button, "qr:" + id, action, Consumer<String> { ignored -> }, 1.02f, false)
            return button
        }

        @JvmStatic @Throws(WriterException::class)
        fun encode(payload: String): BitMatrix {
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"; hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M; hints[EncodeHintType.MARGIN] = 4
            return QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
        }
    }

    /** Back or Left closes it, as every popup; it has no Close button of its own. */
    private constructor(context: Context, report: DiagnosticReport) : super(context) {
        this.report = report
        orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false; clipToPadding = false
        val inset = Tv.dp(context, 20f)
        setPadding(inset, inset, inset, inset)
        val header = LinearLayout(context); header.gravity = Gravity.CENTER_VERTICAL
        header.clipChildren = false; header.clipToPadding = false
        val labels = LinearLayout(context); labels.orientation = VERTICAL
        labels.addView(Tv.heading(context, "Error report", 18f, R.color.tv_text))
        val reference = Tv.text(context, report.id, 12f, R.color.tv_text_secondary)
        reference.letterSpacing = .06f
        val referenceParams = LinearLayout.LayoutParams(-1, -2); referenceParams.topMargin = Tv.dp(context, 3f)
        labels.addView(reference, referenceParams)
        header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        addView(header, LinearLayout.LayoutParams(-1, -2))
        image = CodeView(context)
        val code = LinearLayout.LayoutParams(Tv.dp(context, 288f), Tv.dp(context, 288f))
        code.topMargin = Tv.dp(context, 18f); addView(image, code)
        val pager = LinearLayout(context); pager.gravity = Gravity.CENTER_VERTICAL
        pager.clipChildren = false; pager.clipToPadding = false
        status = Tv.text(context, "", 14f, R.color.tv_text_secondary); status.gravity = Gravity.CENTER
        if (report.pages.size > 1) {
            val previous = icon(context, "Previous QR", R.id.error_qr_previous, R.drawable.ic_tv_chevron, Runnable { change(-1) })
            previous.rotation = 180f
            pager.addView(previous, LinearLayout.LayoutParams(Tv.dp(context, 48f), Tv.dp(context, 48f)))
            pager.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            val next = icon(context, "Next QR", R.id.error_qr_next, R.drawable.ic_tv_chevron, Runnable { change(1) })
            pager.addView(next, LinearLayout.LayoutParams(Tv.dp(context, 48f), Tv.dp(context, 48f)))
        } else pager.addView(status, LinearLayout.LayoutParams(-1, Tv.dp(context, 32f)))
        val pagerParams = LinearLayout.LayoutParams(Tv.dp(context, 288f), -2)
        pagerParams.topMargin = Tv.dp(context, 16f); addView(pager, pagerParams)
        render()
    }

    private fun change(delta: Int) { page = Math.floorMod(page + delta, report.pages.size); render() }

    private fun render() {
        val mine = ++revision
        status.text = (page + 1).toString() + " / " + report.pages.size
        status.contentDescription = "QR " + (page + 1) + " of " + report.pages.size
        image.matrix = null; image.contentDescription = null; image.invalidate()
        val payload = report.pages.get(page)
        (context.applicationContext as TvApplication).workers.execute(Runnable {
            val encoded: BitMatrix
            try { encoded = encode(payload) }
            catch (error: WriterException) {
                post { if (mine == revision) status.text = "QR unavailable" }; return@Runnable
            }
            catch (error: IllegalArgumentException) {
                post { if (mine == revision) status.text = "QR unavailable" }; return@Runnable
            }
            post(Runnable {
                if (mine != revision) return@Runnable
                image.matrix = encoded
                image.contentDescription = "Error report " + report.id + ", QR " + (page + 1) + " of " + report.pages.size
                image.invalidate()
            })
        })
    }

    override fun onDetachedFromWindow() { revision++; super.onDetachedFromWindow() }

    /** Integer module sizes and the four-module white quiet zone survive TV scaling without blurred edges. */
    private class CodeView(context: Context) : View(context) {
        private val paint = Paint()
        // Private in Java; the Kotlin outer class needs it non-private to reach it.
        var matrix: BitMatrix? = null
        init {
            paint.color = Color.BLACK
            val paper = GradientDrawable(); paper.setColor(Color.WHITE); paper.cornerRadius = Tv.dp(context, 10f).toFloat()
            background = paper; clipToOutline = true
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val matrix = matrix ?: return
            val size = matrix.width; val scale = Math.min(width, height) / size
            val left = (width - size * scale) / 2; val top = (height - size * scale) / 2
            for (y in 0 until size) for (x in 0 until size) if (matrix.get(x, y))
                canvas.drawRect((left + x * scale).toFloat(), (top + y * scale).toFloat(), (left + (x + 1) * scale).toFloat(), (top + (y + 1) * scale).toFloat(), paint)
        }
    }
}
