package tv.stream.app

import java.util.concurrent.Executor
import tv.stream.app.addon.Title
import tv.stream.app.data.TvStore
import tv.stream.playback.PlaybackSnapshot

/**
 * What playback records for the viewer: position, completion, explicit watched marks, and the audio and subtitle
 * languages they switch to. Everything is written in order on the application's [persistence] queue, so a later
 * position never undoes a watched mark. Main thread only; holds no activity, view or playable address.
 */
class PlaybackProgress(private val persistence: Executor, private val store: () -> TvStore?) {
    /** The values recorded from one moment of playback. */
    data class Moment(
        val loaded: Boolean, val started: Boolean, val ended: Boolean,
        val positionMs: Long, val durationMs: Long,
        val audioId: String, val audioLanguage: String?, val subtitleId: String, val subtitleLanguage: String?,
    ) {
        companion object {
            fun of(s: PlaybackSnapshot) = Moment(
                s.loaded, s.started, s.ended, s.positionMs, s.durationMs,
                s.audio?.id ?: "", s.audio?.language, s.subtitles?.id ?: "off", if (s.subtitles == null) "off" else s.subtitles?.language,
            )
        }
    }

    /** The current episode is watched: shown on the Watched control. */
    var watched = false
    private var completionRecorded = false
    private var audioChoice = ""
    private var subtitleChoice = ""
    private var choicesKnown = false

    /** A new episode or source starts: completion is counted again and track choices are learned anew. */
    fun startEpisode() { completionRecorded = false }
    fun startSource() { choicesKnown = false }

    /** Whether the viewer changed audio or subtitles since playback settled; the first settled choice is learned. */
    fun tracksChanged(m: Moment): Boolean {
        if (!m.started) return false
        if (!choicesKnown) { audioChoice = m.audioId; subtitleChoice = m.subtitleId; choicesKnown = true; return false }
        return audioChoice != m.audioId || subtitleChoice != m.subtitleId
    }

    /** Records the position. Returns true when this moment completed the episode and so marked it watched. */
    fun save(title: Title, video: String, m: Moment): Boolean {
        val store = store() ?: return false
        if (!m.loaded) return false
        val ended = m.ended && !completionRecorded
        if (ended) { completionRecorded = true; watched = true }
        else if (!m.ended && m.started) completionRecorded = false
        val changedAudio = choicesKnown && audioChoice != m.audioId
        val changedText = choicesKnown && subtitleChoice != m.subtitleId
        if (m.started) { audioChoice = m.audioId; subtitleChoice = m.subtitleId; choicesKnown = true }
        persistence.execute {
            store.progress(title, video, m.positionMs, m.durationMs, ended)
            if (changedAudio && m.audioLanguage != null) store.setPreference("audioLanguage", m.audioLanguage)
            if (changedText && m.subtitleLanguage != null) store.setPreference("subtitleLanguage", m.subtitleLanguage)
        }
        return ended
    }

    /** The viewer marks the episode watched or not. Returns false when nothing changed. */
    fun mark(title: Title, video: String, m: Moment, watched: Boolean): Boolean {
        val store = store() ?: return false
        if (!m.started || this.watched == watched) return false
        if (m.ended) completionRecorded = true
        this.watched = watched
        persistence.execute {
            store.progress(title, video, m.positionMs, m.durationMs, watched)
            if (!watched) store.watched(title, video, false)
        }
        return true
    }
}

/** A series' neighbouring episodes and the "next episode" offer's dismissal, per episode. */
class EpisodeNavigation {
    private var offeredFor = ""
    /** The viewer dismissed the next-episode offer for the episode playing now. */
    var dismissed = false
        private set

    fun neighbour(title: Title?, video: String?, offset: Int): Title.Episode? {
        val episodes = title?.episodes ?: return null
        val i = episodes.indexOfFirst { it.id == video }
        if (i < 0) return null
        return episodes.getOrNull(i + offset)
    }

    /** Called as each episode plays: a new episode may be offered again. */
    fun playing(video: String) { if (video != offeredFor) { offeredFor = video; dismissed = false } }
    fun dismiss() { dismissed = true }
}
