package tv.daniil.torrent;

/** One file of a torrent; the index is the torrent's own, pad files excluded from the list but not renumbered. */
public final class TorrentFile {
    public final int index;
    public final String path;
    public final long size;

    public TorrentFile(int index, String path, long size) {
        this.index = index;
        this.path = path;
        this.size = size;
    }

    /** The last path component, such as "Episode 2.mkv". */
    public String name() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
