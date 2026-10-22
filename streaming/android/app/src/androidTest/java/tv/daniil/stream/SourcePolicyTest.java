package tv.daniil.stream;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import tv.daniil.stream.addon.MediaSource;
import tv.daniil.torrent.TorrentException;
import tv.daniil.torrent.TorrentFile;

@RunWith(AndroidJUnit4.class)
public final class SourcePolicyTest {
    @Test public void explicitZeroAndInvalidIndexesAreNeverGuessed() throws Exception {
        java.util.List<TorrentFile> files = Arrays.asList(new TorrentFile(0, "Season/Show S01E01.mkv", 100),
                new TorrentFile(2, "Season/Show S01E02.mkv", 100), new TorrentFile(3, "poster.jpg", 10));
        assertEquals(0, SourcePolicy.select(files, 0, 1, 2).index);
        assertEquals(2, SourcePolicy.select(files, -1, 1, 2).index);
        assertNull(SourcePolicy.select(files, -1, -1, -1));
        for (int index : new int[]{1, 3, 99}) try { SourcePolicy.select(files, index, 1, 1); fail("Invalid advertised index accepted"); }
        catch (TorrentException expected) { assertEquals(TorrentException.INVALID_FILE, expected.kind); }
        assertNull(SourcePolicy.select(Arrays.asList(new TorrentFile(0, "Show S01E01.mkv", 1), new TorrentFile(1, "Show S01E01 alt.mp4", 1)), -1, 1, 1));
    }
    @Test public void unknownHintsStayVisibleAndExpiredUrlsDoNotChangeIdentity() throws Exception {
        MediaSource first = source("Unknown", "http://127.0.0.1/old?secret=a");
        assertTrue(SourcePolicy.assess(first).usable);
        assertTrue(SourcePolicy.assess(first).label.contains("unknown"));
        assertFalse(SourcePolicy.assess(source("2160p HEVC", "http://127.0.0.1/video")).usable);
        assertFalse(SourcePolicy.assess(source("4K AVC", "http://127.0.0.1/video")).usable);
        assertEquals(SourcePolicy.key(first), SourcePolicy.key(source("Unknown", "http://127.0.0.1/new?secret=b")));
        assertEquals(64, SourcePolicy.key(first).length());
    }
    private static MediaSource source(String name, String url) throws Exception {
        return MediaSource.list("provider", new JSONObject().put("streams", new JSONArray().put(new JSONObject().put("name", name).put("url", url)))).get(0);
    }
}
