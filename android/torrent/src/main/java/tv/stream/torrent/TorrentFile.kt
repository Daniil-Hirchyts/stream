package tv.stream.torrent

/** One file of a torrent; the index is the torrent's own, pad files excluded from the list but not renumbered. */
data class TorrentFile(val index: Int, val path: String, val size: Long) {
    /** The last path component, such as "Episode 2.mkv". */
    fun name(): String {
        val slash = path.lastIndexOf('/')
        return if (slash < 0) path else path.substring(slash + 1)
    }
}
