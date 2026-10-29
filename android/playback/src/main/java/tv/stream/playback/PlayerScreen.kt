package tv.stream.playback

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView

import java.util.Arrays
import java.util.Collections
import java.util.HashSet
import java.util.function.Supplier

/**
 * The shared player layers of an activity, driven by a [PlaybackSession]: preparing and error states, the
 * playback overlay, subtitles, the up-next card, notices and the options menu. Every action works by mouse, touch
 * and remote. The activity forwards keys and Back, and adds its own layers through [PlaybackHost].
 */
@OptIn(markerClass = [UnstableApi::class])
class PlayerScreen(private val activity: Activity, private val session: PlaybackSession, private val host: PlaybackHost) :
        PlaybackSession.Listener, MenuPanel.Listener {
    /** What the screen says while there is no picture yet. */
    class Loading(
        /** The app's name for this step, such as "opening". */
        @JvmField val id: String,
        @JvmField val overline: String,
        @JvmField val message: String?,
        /** Clears itself once the media is ready; otherwise it stays until replaced. */
        @JvmField val untilReady: Boolean,
    )

    private val player: Player
    private val handler = Handler(Looper.getMainLooper())
    private val playerView: PlayerView
    private val subtitles: SubtitleView
    private val loadingView: View
    private val errorView: View
    private val errorRetry: View
    private val errorSources: View
    private val bufferingView: View
    private val upNextView: View
    private val loadingStep: TextView
    private val loadingTitle: TextView
    private val loadingMessage: TextView
    private val errorTitle: TextView
    private val errorMessage: TextView
    private val notice: TextView
    private val upNextTitle: TextView
    private val upNextDetail: TextView
    private val controls: ControlsOverlay
    private val menu: MenuPanel
    private var controlsBehindMenu = false
    private val style: SubtitleStyle
    private val sample: List<Cue>

    private var title = ""
    private var loading: Loading? = null
    private var shownError: PlaybackErrors.Description? = null
    private var upNext: Runnable? = null
    private var upNextContext = ""
    private var upNextSeconds = 0
    private var previewUntil: Long = 0
    private var screenKeptOn = false
    // The overlay reacts to changes of these, not to every update.
    private var lastState = Player.STATE_IDLE
    private var lastPlayWhenReady = false

    private val showCues = Runnable { showCues() }
    private val hideNotice = Runnable { hideNotice() }
    private val countdown = Runnable { countDown() }

    init {
        this.player = session.player()
        playerView = activity.findViewById(R.id.player_view)
        loadingView = activity.findViewById(R.id.loading)
        loadingStep = activity.findViewById(R.id.loading_step)
        loadingTitle = activity.findViewById(R.id.loading_title)
        loadingMessage = activity.findViewById(R.id.loading_message)
        bufferingView = activity.findViewById(R.id.buffering)
        errorView = activity.findViewById(R.id.error)
        errorTitle = activity.findViewById(R.id.error_title)
        errorMessage = activity.findViewById(R.id.error_message)
        errorRetry = activity.findViewById(R.id.error_retry)
        errorSources = activity.findViewById(R.id.error_sources)
        notice = activity.findViewById(R.id.notice)
        upNextView = activity.findViewById(R.id.up_next)
        upNextTitle = activity.findViewById(R.id.up_next_title)
        upNextDetail = activity.findViewById(R.id.up_next_detail)
        subtitles = activity.findViewById(R.id.subtitles)
        playerView.subtitleView!!.visibility = View.GONE
        playerView.player = player
        // The gap below subtitles is a fraction of the view's height, known only after layout.
        subtitles.addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (bottom - top != oldBottom - oldTop) layoutSubtitles()
        }
        style = SubtitleStyle(session.preferences())
        style.applyTo(subtitles)
        sample = Collections.singletonList(Cue.Builder().setText(activity.getString(R.string.subtitle_sample)).build())
        controls = ControlsOverlay(activity, session, handler, Runnable { layoutSubtitles() })
        menu = MenuPanel(activity, this)
        bindPointerControls()
        session.addListener(this)
    }

    // State shown by the app

    fun setTitle(text: String) {
        title = text
        controls.setTitle(text)
        if (loading != null) loadingTitle.text = text
    }

    /** Shows a step before the picture, such as opening or converting; null hides it. */
    fun setLoading(step: Loading?) {
        loading = step
        render()
    }

    fun loading(): Loading? {
        return loading
    }

    /** Nothing is loaded, failing or being prepared: the app's idle layer shows. */
    fun isIdle(): Boolean {
        return session.error() == null && loading == null && !session.isLoaded()
    }

    fun isMenuOpen(): Boolean {
        return menu.isOpen()
    }

    fun menu(): MenuPanel {
        return menu
    }

    fun openMenu() {
        menu.open(rootPage())
    }

    /** Audio and subtitle choices alone, for an app's own Audio & subtitles button. */
    fun openTracksMenu() {
        menu.open(tracksPage())
    }

    /** Everything but the audio and subtitle choices, for an app's own Settings button. */
    fun openSettingsMenu() {
        menu.open(settingsPage())
    }

    fun refreshMenu() {
        menu.refresh()
    }

    fun style(): SubtitleStyle {
        return style
    }

    /** Applies a changed style; a `preview` shows the sample line for a moment between cues. */
    fun onStyleChanged(preview: Boolean) {
        style.applyTo(subtitles)
        layoutSubtitles()
        session.notifyChanged()
        if (!preview) return
        menu.refresh()
        previewUntil = SystemClock.uptimeMillis() + PREVIEW_MS
        handler.removeCallbacks(showCues)
        handler.postDelayed(showCues, PREVIEW_MS)
        showCues()
    }

    fun notice(text: String?) {
        notice.text = text
        Ui.fade(notice, true)
        handler.removeCallbacks(hideNotice)
        handler.postDelayed(hideNotice, NOTICE_MS)
    }

    private fun hideNotice() {
        Ui.fade(notice, false)
    }

    fun showControls() {
        controls.show()
    }

    /** Logical visibility changes when the controls begin fading, before the view becomes gone. */
    fun controlsVisible(): Boolean { return controls.isShown() }

    /** For an app whose menu is a popup over the video: the overlay stays visible beneath an open menu. */
    fun keepControlsBehindMenu(keep: Boolean) {
        controlsBehindMenu = keep
    }

    // Up next

    /** Counts down, then plays the next episode unless the viewer cancels; `context` reads "Season 2". */
    fun offerUpNext(nextTitle: String?, context: String, play: Runnable) {
        if (upNext != null) return
        upNext = play
        upNextContext = context
        upNextSeconds = UP_NEXT_SECONDS
        controls.hide()
        upNextTitle.text = nextTitle
        Ui.fade(upNextView, true)
        countDown()
    }

    fun hasUpNext(): Boolean {
        return upNext != null
    }

    fun cancelUpNext() {
        handler.removeCallbacks(countdown)
        upNext = null
        Ui.fade(upNextView, false)
    }

    private fun countDown() {
        if (upNext == null) return
        if (upNextSeconds <= 0) {
            val next = upNext
            cancelUpNext()
            next!!.run()
            return
        }
        upNextDetail.text = upNextContext + "  ·  " + activity.getString(R.string.up_next_countdown, upNextSeconds)
        upNextSeconds--
        handler.postDelayed(countdown, 1000)
    }

    private fun playUpNext() {
        val next = upNext
        cancelUpNext()
        if (next != null) next.run()
    }

    // Rendering

    /** Shows exactly one of the app's idle layer, preparing, error or the video itself. */
    fun render() {
        val error = session.error()
        if (error !== shownError) {
            shownError = error
            if (error != null) {
                // The error replaces whatever step was showing.
                loading = null
                cancelUpNext()
                errorTitle.text = error.title
                errorMessage.text = error.message
                errorRetry.visibility = if (error.retry && host.canRetry()) View.VISIBLE else View.GONE
                errorSources.visibility = if (host.canChooseSource()) View.VISIBLE else View.GONE
            }
        }
        val failed = error != null
        val preparing = !failed && loading != null
        Ui.fade(loadingView, preparing)
        Ui.fade(errorView, failed)
        bufferingView.visibility = if (!failed && !preparing && session.started()
                && player.playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
        if (failed || preparing || !session.started() || menu.isOpen() && !controlsBehindMenu) controls.hide()
        if (preparing) {
            loadingTitle.text = title
            loadingStep.text = loading!!.overline
            loadingMessage.text = if (loading!!.message == null) "" else loading!!.message
            loadingMessage.visibility = if (loadingMessage.text.length > 0) View.VISIBLE else View.GONE
        }
        // The screensaver may start while idle or paused, never during a video or while it opens.
        keepScreenOn(preparing || player.isPlaying)
        host.onScreenChanged()
    }

    private fun keepScreenOn(on: Boolean) {
        if (on == screenKeptOn) return
        screenKeptOn = on
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** A load shows its title at once, even while its subtitles download; a stop keeps the last one. */
    override fun onMediaChanged() {
        cancelUpNext()
        controls.hide()
        val request = session.request()
        if (request != null) setTitle(request.title)
    }

    override fun onPlaybackChanged() {
        if (session.started() && loading != null && loading!!.untilReady) loading = null
        val state = player.playbackState
        val playWhenReady = player.playWhenReady
        val changed = state != lastState || playWhenReady != lastPlayWhenReady
        lastState = state
        lastPlayWhenReady = playWhenReady
        // The up-next card replaces the overlay at the end of an episode.
        if (changed && session.started() && upNext == null && (!menu.isOpen() || controlsBehindMenu)) controls.onPlaybackChanged()
        render()
    }

    override fun onCuesChanged() {
        showCues()
    }

    /** Mirrors the tracks in the overlay and the menu once they are known. */
    override fun onTracksChanged() {
        val details: MutableList<String> = ArrayList()
        val request = session.request()
        if (request != null && !request.detail.isEmpty()) details.add(request.detail)
        val playing = AudioTracks.selected(session.audioTracks())
        if (session.audioTracks().size > 1 && playing != null) details.add(playing.label)
        if (!session.textTracks().isEmpty()) details.add(activity.getString(R.string.details_subtitles, subtitleSummary()))
        controls.setDetails(TextUtils.join("  ·  ", details))
        showCues()
        menu.refresh()
    }

    override fun onSeekGathering(deltaMs: Long) {
        controls.showSeek(deltaMs)
    }

    /** The current cues, or the sample line while the style is being changed and nothing else is shown. */
    private fun showCues() {
        val cues = session.cues()
        val preview = menu.previewsSubtitles() || SystemClock.uptimeMillis() < previewUntil
        subtitles.setCues(if (cues.isEmpty() && preview) sample else bottomAligned(cues))
    }

    /** Places subtitles at the chosen height, clear of the menu and the overlay's timeline and buttons. */
    private fun layoutSubtitles() {
        val right = if (menu.isOpen()) activity.resources.getDimensionPixelSize(R.dimen.panel_width) else 0
        // Moving captions above controls changes padding, never their text size.
        if (subtitles.height > 0) subtitles.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                subtitles.height * style.sizeFraction())
        val gap = Math.round(subtitles.height * style.bottomGap())
        val overlay = if (controls.isShown()) controls.bottomClearance() else 0
        subtitles.setPadding(0, 0, right, Math.max(gap, overlay))
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
    }

    // Menu pages

    private fun subtitleSummary(): String {
        val shown = SubtitleTracks.selected(session.textTracks())
        return if (shown == null) activity.getString(R.string.track_off) else shown.label
    }

    private fun qualitySummary(): String {
        val quality = session.quality()
        if (quality != null) return quality.label
        val lines = session.playingLines()
        return if (lines > 0) activity.getString(R.string.quality_auto_current, VideoQualities.label(lines))
        else activity.getString(R.string.quality_auto)
    }

    private fun speedLabel(value: Float): String {
        if (value == 1f) return activity.getString(R.string.speed_normal)
        val number = if (value.toDouble() == Math.floor(value.toDouble())) value.toInt().toString() else value.toString()
        return activity.getString(R.string.speed_value, number)
    }

    private fun styleSummary(): String {
        return activity.getString(SubtitleStyle.Setting.SIZE.labels[style.get(SubtitleStyle.Setting.SIZE)]) + " · " +
                activity.getString(SubtitleStyle.Setting.COLOR.labels[style.get(SubtitleStyle.Setting.COLOR)])
    }

    private fun rootPage(): MenuPanel.Page {
        val loaded = session.isLoaded()
        return MenuPanel.Page.of(activity.getString(if (loaded) R.string.menu_options else R.string.menu_settings), false, Supplier {
            val items: MutableList<MenuPanel.Item> = ArrayList(host.menuItems(true))
            val audio = session.audioTracks()
            if (audio.size > 1) {
                val playing = AudioTracks.selected(audio)
                items.add(MenuPanel.Item.page(activity.getString(R.string.menu_audio),
                        if (playing == null) null else playing.label, false, Runnable { menu.push(audioPage()) }))
            }
            if (hasSubtitles()) {
                items.add(MenuPanel.Item.page(activity.getString(R.string.menu_subtitles), subtitleSummary(), false,
                        Runnable { menu.push(subtitlesPage()) }))
            }
            addSettings(items, loaded)
            items.addAll(host.menuItems(false))
            items
        })
    }

    private fun hasSubtitles(): Boolean {
        return !session.textTracks().isEmpty() || !host.subtitleItems().isEmpty()
    }

    private fun addSettings(items: MutableList<MenuPanel.Item>, loaded: Boolean) {
        if (!session.qualities().isEmpty()) {
            items.add(MenuPanel.Item.page(activity.getString(R.string.menu_quality), qualitySummary(), false,
                    Runnable { menu.push(qualityPage()) }))
        }
        if (loaded) {
            items.add(MenuPanel.Item.page(activity.getString(R.string.menu_speed), speedLabel(session.speed()), false,
                    Runnable { menu.push(speedPage()) }))
        }
        items.add(MenuPanel.Item.page(activity.getString(R.string.menu_style), styleSummary(), false,
                Runnable { menu.push(stylePage()) }))
    }

    /** Subtitle choices first, then the audio row: what an Audio & subtitles button is pressed for. */
    private fun tracksPage(): MenuPanel.Page {
        return MenuPanel.Page.of(activity.getString(R.string.menu_tracks), false, Supplier {
            val items: MutableList<MenuPanel.Item> = ArrayList(subtitleChoices())
            val audio = session.audioTracks()
            if (audio.size > 1) {
                val playing = AudioTracks.selected(audio)
                items.add(MenuPanel.Item.page(activity.getString(R.string.menu_audio),
                        if (playing == null) null else playing.label, false, Runnable { menu.push(audioPage()) }))
            }
            items
        })
    }

    private fun settingsPage(): MenuPanel.Page {
        val loaded = session.isLoaded()
        return MenuPanel.Page.of(activity.getString(R.string.menu_settings), false, Supplier {
            val items: MutableList<MenuPanel.Item> = ArrayList(host.menuItems(true))
            addSettings(items, loaded)
            items.addAll(host.menuItems(false))
            items
        })
    }

    private fun audioPage(): MenuPanel.Page {
        return MenuPanel.Page.of(activity.getString(R.string.menu_audio), false, Supplier {
            val items: MutableList<MenuPanel.Item> = ArrayList()
            for (track in session.audioTracks()) {
                items.add(MenuPanel.Item.choice(track.label, null, track.selected, Runnable {
                    session.selectAudio(track)
                    menu.pop()
                }))
            }
            items
        })
    }

    private fun subtitlesPage(): MenuPanel.Page {
        return MenuPanel.Page.of(activity.getString(R.string.menu_subtitles), false, Supplier { subtitleChoices() })
    }

    /** Off, the loaded tracks, then whatever more the app can add. */
    private fun subtitleChoices(): List<MenuPanel.Item> {
        val items: MutableList<MenuPanel.Item> = ArrayList()
        val tracks = session.textTracks()
        val shown = SubtitleTracks.selected(tracks)
        items.add(MenuPanel.Item.choice(activity.getString(R.string.track_off), null, shown == null, Runnable {
            if (!tracks.isEmpty()) session.selectSubtitles(null)
            menu.pop()
        }))
        for (track in tracks) {
            items.add(MenuPanel.Item.choice(track.label, null, track === shown, Runnable {
                session.selectSubtitles(track)
                menu.pop()
            }))
        }
        items.addAll(host.subtitleItems())
        return items
    }

    private fun qualityPage(): MenuPanel.Page {
        return MenuPanel.Page.of(activity.getString(R.string.menu_quality), false, Supplier {
            val items: MutableList<MenuPanel.Item> = ArrayList()
            val quality = session.quality()
            val lines = session.playingLines()
            items.add(MenuPanel.Item.choice(activity.getString(R.string.quality_auto),
                    if (quality == null && lines > 0) VideoQualities.label(lines) else null, quality == null, Runnable {
                        session.selectQuality(null)
                        menu.pop()
                    }))
            for (option in session.qualities()) {
                items.add(MenuPanel.Item.choice(option.label, null, quality != null && option.id == quality.id, Runnable {
                    session.selectQuality(option)
                    menu.pop()
                }))
            }
            items
        })
    }

    private fun speedPage(): MenuPanel.Page {
        return MenuPanel.Page.of(activity.getString(R.string.menu_speed), false, Supplier {
            val items: MutableList<MenuPanel.Item> = ArrayList()
            for (value in SPEEDS) {
                items.add(MenuPanel.Item.choice(speedLabel(value), null, value == session.speed(), Runnable {
                    session.setSpeed(value)
                    menu.pop()
                }))
            }
            items
        })
    }

    private fun stylePage(): MenuPanel.Page {
        return MenuPanel.Page.of(activity.getString(R.string.menu_style), true, Supplier {
            val items: MutableList<MenuPanel.Item> = ArrayList()
            for (setting in SubtitleStyle.Setting.values()) {
                items.add(MenuPanel.Item.page(activity.getString(setting.title),
                        activity.getString(setting.labels[style.get(setting)]), false,
                        Runnable { menu.push(styleSettingPage(setting)) }))
            }
            items
        })
    }

    private fun styleSettingPage(setting: SubtitleStyle.Setting): MenuPanel.Page {
        return MenuPanel.Page.of(activity.getString(setting.title), true, Supplier {
            val items: MutableList<MenuPanel.Item> = ArrayList()
            for (i in setting.values.indices) {
                val index = i
                val apply = Runnable {
                    if (style.set(setting, index)) onStyleChanged(true)
                }
                items.add(MenuPanel.Item.choice(activity.getString(setting.labels[i]), null, style.get(setting) == i, apply).onFocus(apply))
            }
            items
        })
    }

    override fun onMenuToggled(open: Boolean) {
        if (open && !controlsBehindMenu) controls.hide()
        else controls.hold(open)
        host.onMenuToggled(open)
        layoutSubtitles()
        showCues()
    }

    override fun onMenuPageChanged() {
        showCues()
    }

    // Pointer and remote input

    /** Pointer actions use the same playback and menu paths as remote keys. */
    private fun bindPointerControls() {
        Ui.action(activity.findViewById(R.id.control_options), Runnable { openMenu() })
        Ui.action(activity.findViewById(R.id.center_icon), Runnable { togglePlay() })
        Ui.action(activity.findViewById(R.id.control_play), Runnable { togglePlay() })
        Ui.action(activity.findViewById(R.id.control_rewind), Runnable { session.seekBy(-PlaybackSession.SEEK_STEP_MS) })
        Ui.action(activity.findViewById(R.id.control_forward), Runnable { session.seekBy(PlaybackSession.SEEK_STEP_MS) })
        Ui.action(activity.findViewById(R.id.control_stop), Runnable { host.onExit() })
        Ui.action(errorRetry, Runnable { host.onRetry() })
        Ui.action(errorSources, Runnable { host.onChooseSource() })
        Ui.action(activity.findViewById(R.id.error_close), Runnable { host.onExit() })
        Ui.action(activity.findViewById(R.id.up_next_play), Runnable { playUpNext() })
        Ui.action(activity.findViewById(R.id.up_next_cancel), Runnable {
            cancelUpNext()
            controls.show()
        })
        val surface = View.OnClickListener { view ->
            if (menu.isOpen()) menu.close()
            else if (session.started() && session.error() == null && loading == null) {
                if (controls.isShown() && player.playWhenReady) controls.hide()
                else controls.show()
            }
        }
        // The activity's root holds the video layer; a click anywhere off the controls toggles them.
        (playerView.parent as View).setOnClickListener(surface)
        activity.findViewById<View>(R.id.controls).setOnClickListener(surface)
        playerView.setOnClickListener(surface)
    }

    /** For the activity's `dispatchKeyEvent`; Back goes through [onRemoteKey] from onBackPressed. */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) return false
        if (event.action == KeyEvent.ACTION_DOWN && onRemoteKey(code)) return true
        return event.action == KeyEvent.ACTION_UP && REMOTE_KEYS.contains(code)
    }

    /** Handles a remote key; false lets the activity act, such as closing on Back. */
    fun onRemoteKey(code: Int): Boolean {
        if (menu.isOpen() && menu.onKeyDown(code)) return true
        val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (upNext != null && code != KeyEvent.KEYCODE_MENU && code != KeyEvent.KEYCODE_CAPTIONS) {
            if (ok) playUpNext()
            else if (code == KeyEvent.KEYCODE_BACK) {
                cancelUpNext()
                controls.show()
            }
            return true
        }
        if (host.onKey(code)) return true
        when (code) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS -> { openMenu(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { host.onNextEpisode(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { host.onPreviousEpisode(); return true }
            else -> {}
        }
        val error = session.error()
        if (error != null) {
            if (ok && error.retry && host.canRetry()) {
                host.onRetry()
                return true
            }
            return false
        }
        // While idle or preparing, Back closes the app as usual.
        if (!session.started() || loading != null) return false
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { session.seekBy(-PlaybackSession.SEEK_STEP_MS); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { session.seekBy(PlaybackSession.SEEK_STEP_MS); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { controls.show(); return true }
            KeyEvent.KEYCODE_BACK -> {
                if (!controls.isShown() || !player.playWhenReady) return false
                controls.hide()
                return true
            }
            else -> return false
        }
    }

    private fun togglePlay() {
        session.togglePlay()
        controls.show()
    }

    companion object {
        private const val PREVIEW_MS: Long = 4000
        private const val NOTICE_MS: Long = 4000
        private const val UP_NEXT_SECONDS = 10
        private val SPEEDS = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        // Back is routed through onBackPressed/the system dispatcher on every Android version.
        private val REMOTE_KEYS: Set<Int> = HashSet(Arrays.asList(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS))

        /**
         * SubRip and similar parsers place an ordinary bottom cue at a fraction of the height (0.92) rather than leaving
         * it unset. A fraction is measured inside the view's bottom padding, so the cue would float well above the
         * chosen gap and the overlay. Such cues sit on the padding instead; cues placed elsewhere keep their place.
         */
        private fun bottomAligned(cues: List<Cue>): List<Cue> {
            var result: MutableList<Cue>? = null
            for (i in cues.indices) {
                val cue = cues[i]
                val low = cue.lineType == Cue.LINE_TYPE_FRACTION && cue.line != Cue.DIMEN_UNSET && cue.line >= 0.75f &&
                        cue.lineAnchor != Cue.ANCHOR_TYPE_START && cue.verticalType == Cue.TYPE_UNSET
                if (!low) continue
                if (result == null) result = ArrayList(cues)
                result[i] = cue.buildUpon().setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).setLineAnchor(Cue.TYPE_UNSET).build()
            }
            return if (result == null) cues else result
        }
    }
}
