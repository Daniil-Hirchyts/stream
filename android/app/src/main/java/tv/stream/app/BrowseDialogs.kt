package tv.stream.app

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.function.Consumer
import tv.stream.app.ui.Tv

/** Native popup presentation, separate from navigation and provider work. */
class BrowseDialogs(
    private val activity: Activity,
    private val changed: () -> Unit,
    private val previewCancelled: () -> Unit,
) : AutoCloseable {
    private val dialogs = mutableSetOf<Dialog>()
    val isShowing: Boolean get() = dialogs.isNotEmpty()

    fun show(alert: Tv.Alert) {
        val dialog = alert.dialog
        dialogs.add(dialog); dialog.setOnDismissListener { ignored -> dialogs.remove(dialog); changed() }
        alert.show()
    }
    /** Long text (licences) scrolls inside the alert, left-aligned for reading. */
    fun message(title: String, message: CharSequence, scroll: Boolean, onClose: Runnable?) {
        val body: CharSequence? = if (scroll) null else message
        val alert = Tv.alert(activity, title, body)
        if (scroll) {
            val content = ScrollView(activity)
            content.isVerticalScrollBarEnabled = false; content.isFocusable = true
            val text = Tv.text(activity, message.toString(), 13.5f, R.color.tv_text_secondary)
            text.setLineSpacing(Tv.dp(activity, 3f).toFloat(), 1f)
            content.addView(text)
            val contentParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(activity, 300f))
            contentParams.topMargin = Tv.dp(activity, 16f)
            alert.surface.addView(Tv.clipped(content, 0), alert.surface.indexOfChild(alert.buttons), contentParams)
        }
        alert.dialog.setOnCancelListener { ignored -> if (onClose != null) onClose.run() }
        show(alert)
    }
    fun confirm(title: String, message: CharSequence, action: String, onAccept: Runnable, onCancel: Runnable?) {
        val alert = Tv.alert(activity, title, message)
        alert.button("popup:confirm", action, Runnable { alert.dialog.dismiss(); onAccept.run() })
        alert.dialog.setOnCancelListener { ignored -> if (onCancel != null) onCancel.run() }
        show(alert)
    }
    fun edit(title: String, description: String, hint: String, initial: String?, accept: Consumer<String>) {
        val alert = Tv.alert(activity, title, description as CharSequence)
        val input = EditText(activity)
        input.setSingleLine(true); input.hint = hint; input.setText(initial); input.textSize = 17f
        input.setTextColor(activity.getColor(R.color.tv_text)); input.setHintTextColor(activity.getColor(R.color.tv_text_tertiary))
        input.setSelectAllOnFocus(true); input.contentDescription = title; input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setBackgroundResource(R.drawable.tv_dialog_field); input.setPadding(Tv.dp(activity, 16f), 0, Tv.dp(activity, 16f), 0)
        val inputParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Tv.dp(activity, 50f))
        inputParams.topMargin = Tv.dp(activity, 20f)
        alert.surface.addView(input, alert.surface.indexOfChild(alert.buttons), inputParams)
        val error = Tv.text(activity, "", 13.5f, R.color.tv_warning)
        error.visibility = View.GONE; error.gravity = Gravity.START; error.setPadding(Tv.dp(activity, 12f), Tv.dp(activity, 8f), Tv.dp(activity, 12f), 0)
        alert.surface.addView(error, alert.surface.indexOfChild(alert.buttons))
        val proceed = alert.button("popup:continue", "Continue", Runnable {
            val text = input.text.toString().trim { it <= ' ' }
            if (text.isEmpty()) { error.text = "Enter a value"; error.visibility = View.VISIBLE; input.requestFocus(); return@Runnable }
            error.visibility = View.GONE; alert.dialog.dismiss(); accept.accept(text)
        })
        input.setOnEditorActionListener(TextView.OnEditorActionListener { v, action, event ->
            if (action != EditorInfo.IME_ACTION_DONE) return@OnEditorActionListener false
            proceed.performClick(); true
        })
        show(alert)
        input.requestFocus()
    }
    fun options(title: String, contents: Consumer<Tv.Alert>) {
        val menu = Tv.alert(activity, title, null as View?); contents.accept(menu); show(menu)
    }
    fun choose(title: String, options: Array<String>, accept: Consumer<Int>) { show(Tv.choices(activity, title, options, accept, null)) }
    fun chooseWithPreview(title: String, header: View?, options: Array<String>, selected: Int,
                                   accept: Consumer<Int>, onPreview: Consumer<Int>?) {
        val accepted = booleanArrayOf(false)
        val alert = Tv.choices(activity, title, header, options, selected, Consumer { index ->
            accepted[0] = true; accept.accept(index)
        }, onPreview, null)
        show(alert)
        alert.dialog.setOnDismissListener { dialog ->
            dialogs.remove(dialog as Dialog)
            changed()
            if (!accepted[0] && header == null) {
                previewCancelled()
            }
        }
    }
    override fun close() { dialogs.toList().forEach { it.dismiss() }; dialogs.clear() }
}
