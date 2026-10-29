package tv.stream.app

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.View
import java.util.Objects
import tv.stream.playback.AudioTracks
import tv.stream.playback.PlaybackSession
import tv.stream.playback.PlayerScreen
import tv.stream.playback.SubtitleStyle
import tv.stream.playback.SubtitleTracks
import tv.stream.playback.VideoQualities
import tv.stream.app.ui.SubtitlePreviewView
import tv.stream.app.ui.SubtitleFonts
import tv.stream.app.ui.Tv

/** App-owned popup presentation; Media3, cue handling and the shared session remain unchanged. */
class PlayerMenus(private val activity: WatchActivity, private val session: PlaybackSession, private val screen: PlayerScreen, private val preferences: SharedPreferences, private val host: Host) {
    class SubtitleOffer(@JvmField val label: String, @JvmField val choose: Runnable)
    interface Host {
        fun hasEpisode(direction: Int): Boolean
        fun episode(direction: Int)
        fun canChooseSource(): Boolean
        fun sources(page: Tv.Alert)
        fun offers(): List<SubtitleOffer>
        fun saveStyle(setting: SubtitleStyle.Setting, value: String)
        fun visibilityChanged()
        fun developerMode(): Boolean
        fun diagnostics(page: Tv.Alert)
    }
    companion object {
        private val SPEEDS = floatArrayOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        @JvmStatic fun speed(value: Float): String = java.math.BigDecimal.valueOf(value.toDouble()).stripTrailingZeros().toPlainString() + "×"
    }
    private val main = Handler(Looper.getMainLooper())
    private var root: Tv.Alert? = null
    private var signature: String = ""

    fun isOpen(): Boolean = root != null && root!!.dialog.isShowing
    fun open(tracks: Boolean, origin: View) {
        if (isOpen()) { close(); return }
        origin.isFocusableInTouchMode = true; origin.requestFocus()
        val menu = Tv.alert(activity, if (tracks) "Audio & subtitles" else "Settings", null as View?)
        root = menu
        menu.onAppear { menu.reset(); if (tracks) tracks(menu, true) else settings(menu); signature = signature() }
        // Retain the shared menu's transport/caption guard, but never draw its old presentation.
        // INVISIBLE still measures its rows, avoiding the shared reveal callback retrying an unmeasured GONE view.
        // Opening the shared guard normally focuses its panel. Keep the opener focused while the
        // invisible panel starts, so it never shrinks or changes tint beneath the popup snapshot.
        val sharedMenu = activity.findViewById<android.view.ViewGroup>(R.id.menu)
        val descendants = sharedMenu.descendantFocusability
        val focusable = sharedMenu.isFocusable; val touchFocusable = sharedMenu.isFocusableInTouchMode
        sharedMenu.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        sharedMenu.isFocusable = false
        try {
            screen.openSettingsMenu()
            sharedMenu.visibility = View.INVISIBLE
        } finally {
            sharedMenu.descendantFocusability = descendants
            sharedMenu.isFocusable = focusable
            sharedMenu.isFocusableInTouchMode = touchFocusable
        }
        menu.onClosing { releaseGuard() }
        menu.dialog.setOnDismissListener { d -> if (root === menu) root = null; main.removeCallbacks(refresh); host.visibilityChanged() }
        menu.show(); host.visibilityChanged()
    }
    private fun releaseGuard() {
        if (screen.isMenuOpen()) screen.menu().close()
        if (!activity.isFinishing) screen.showControls()
    }
    fun close() { if (root != null) root!!.dialog.dismiss() }
    private fun done(page: Tv.Alert, top: Boolean) { if (top) page.dialog.dismiss() else page.back() }
    private fun settings(page: Tv.Alert) {
        if (host.canChooseSource()) page.submenu("player:sources", "Choose source") { child -> child.onAppear {
            child.reset(); host.sources(child)
        } }
        if (host.hasEpisode(-1)) page.button("player:previous", "Previous episode") { close(); host.episode(-1) }
        if (host.hasEpisode(1)) page.button("player:next", "Next episode") { close(); host.episode(1) }
        if (!session.qualities().isEmpty()) page.submenu("player:quality", "Quality", if (session.quality() == null) "Auto" else session.quality()!!.label) { child ->
            child.onAppear {
                child.reset(); child.choice("quality:auto", "Auto", "", session.quality() == null) { session.selectQuality(null); child.back() }
                for (option in session.qualities()) child.choice("quality:" + option.id, option.label, "",
                        session.quality() != null && option.id.equals(session.quality()!!.id)) { session.selectQuality(option); child.back() }
                child.scrollChoices(276)
            }
        }
        page.submenuInline("player:speed", "Speed", speed(session.speed())) { child ->
            child.onAppear {
                child.reset(); for (value in SPEEDS) child.choice("speed:" + value, speed(value), "", value == session.speed()) { session.setSpeed(value); child.back() }
                child.scrollChoices(276)
            }
        }
        page.submenu("player:style", "Subtitle style") { child -> child.onAppear { style(child) } }
        if (host.developerMode()) page.submenu("player:diagnostics", "Developer") { child -> host.diagnostics(child) }
        page.scrollChoices(322)
    }
    private fun tracks(page: Tv.Alert, top: Boolean) {
        val tracks = session.textTracks(); val selected = SubtitleTracks.selected(tracks)
        page.choice("subtitle:off", "Off", "", selected == null) { session.selectSubtitles(null); done(page, top) }
        for (track in tracks) page.choice("subtitle:" + track.id, track.label, if (track.external) "Downloaded" else "In video", track === selected,
                { session.selectSubtitles(track); done(page, top) })
        var i = 0; for (offer in host.offers()) {
            page.button("subtitle:download:" + i++, offer.label + " · Download") { close(); offer.choose.run() }
        }
        if (session.audioTracks().size > 1) {
            val playing = AudioTracks.selected(session.audioTracks())
            page.submenu("player:audio", "Audio", if (playing == null) "" else playing.label) { child -> child.onAppear {
                child.reset(); for (track in session.audioTracks()) child.choice("audio:" + track.id, track.label, "", track.selected,
                        { session.selectAudio(track); child.back() })
                child.scrollChoices(276)
            } }
        }
        page.scrollChoices(322)
    }
    private fun preview(page: Tv.Alert): SubtitlePreviewView {
        val view = SubtitlePreviewView(activity); view.setCompact(true); view.updateFrom(preferences); page.body(view); return view
    }
    private fun style(page: Tv.Alert) {
        page.reset(); preview(page)
        for (setting in SubtitleStyle.Setting.values()) {
            val label = activity.getString(setting.title); val value = if (setting == SubtitleStyle.Setting.FONT)
                    SubtitleFonts.labels(activity)[SubtitleFonts.selected(preferences)] else activity.getString(setting.labels[screen.style().get(setting)])
            page.submenu("style:" + setting.key, label, value) { child -> child.onAppear { styleChoices(child, setting) } }
        }
        page.scrollChoices(230)
    }
    private fun styleChoices(page: Tv.Alert, setting: SubtitleStyle.Setting) {
        page.reset(); val view = preview(page)
        val font = setting == SubtitleStyle.Setting.FONT
        val selected = if (font) SubtitleFonts.selected(preferences) else screen.style().get(setting)
        val values: Array<String> = if (font) SubtitleFonts.VALUES else setting.values
        val labels: Array<String?> = if (font) arrayOf(*SubtitleFonts.labels(activity)) else arrayOfNulls(setting.labels.size)
        if (!font) for (i in 0 until labels.size) labels[i] = activity.getString(setting.labels[i])
        for (i in 0 until values.size) {
            val index = i
            page.choice("style:" + setting.key + ":" + i, labels[i]!!, "", selected == i, {
                screen.style().set(setting, if (font && index >= setting.values.size) 0 else index)
                if (font) preferences.edit().putString("subtitle_font", values[index]).apply()
                screen.onStyleChanged(false); restoreFont()
                host.saveStyle(setting, values[index]); page.back()
            }, { ignored ->
                view.preview(setting, index)
            })
        }
        page.scrollChoices(184)
    }
    /** The shared style resets the subtitle font and drops cue emphasis; put Stream's choice back. */
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private fun restoreFont() {
        val subtitles = activity.findViewById<androidx.media3.ui.SubtitleView>(R.id.subtitles)
        androidx.media3.ui.StreamSubtitleOutput.font(subtitles, SubtitleFonts.selected(preferences))
        androidx.media3.ui.StreamSubtitleOutput.configure(subtitles)
    }
    private fun signature(): String {
        val result = StringBuilder()
        for (track in session.textTracks()) result.append(track.id).append(track.selected)
        for (track in session.audioTracks()) result.append(track.id).append(track.selected)
        result.append(session.qualities().size).append(host.offers().size); return result.toString()
    }
    fun changed() { if (isOpen() && !Objects.equals(signature, signature())) { main.removeCallbacks(refresh); main.postDelayed(refresh, 60) } }
    private val refresh = Runnable { if (isOpen() && !Objects.equals(signature, signature())) { signature = signature(); root!!.refresh() } }
}
