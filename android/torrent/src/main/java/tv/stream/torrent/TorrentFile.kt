package tv.stream.torrent

/** One file of a torrent; the index is the torrent's own, pad files excluded from the list but not renumbered. */
class TorrentFile(@JvmField val index: Int, @JvmField val path: String, @JvmField val size: Long) {
    /** The last path component, such as "Episode 2.mkv". */
    fun name(): String {
        val slash = path.lastIndexOf('/')
        return if (slash < 0) path else path.substring(slash + 1)
    }
}
