package tv.daniil.stream.addon;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Real films through the app's add-on code and live services: Stremio's add-on directory, Cinemeta, Public Domain
 * Movies, OpenSubtitles and the built-in Internet Archive add-on. Every film here is in the public domain. Runs only
 * with {@code ./gradlew :app:testDebugUnitTest -PliveAddons=true}; FFmpeg checks are skipped when it isn't installed.
 * Required tests stay offline and deterministic.
 */
public final class RealFilmsLiveTest {
    /** IMDb ID → title. All are public-domain feature films. */
    private static final Map<String, String> FILMS = new LinkedHashMap<>();
    static {
        FILMS.put("tt0063350", "Night of the Living Dead");
        FILMS.put("tt0012349", "The Kid");
        FILMS.put("tt0013442", "Nosferatu");
        FILMS.put("tt0017925", "The General");
        FILMS.put("tt0032599", "His Girl Friday");
        FILMS.put("tt0056923", "Charade");
        FILMS.put("tt0058700", "The Last Man on Earth");
        FILMS.put("tt0051744", "House on Haunted Hill");
    }
    private static final String PUBLIC_DOMAIN_MOVIES = "Public Domain Movies";
    private static AddonClient client;
    private static File evidence;

    @BeforeClass public static void live() throws Exception {
        assumeTrue("Live add-on tests run with -PliveAddons=true", Boolean.getBoolean("dtv.live"));
        client = new AddonClient();
        evidence = new File(System.getProperty("dtv.evidence", "build/live-evidence"));
        Files.createDirectories(evidence.toPath());
    }
    @AfterClass public static void close() { if (client != null) client.close(); }
    private static JSONObject get(String address) throws AddonException { return client.json(address, new Cancellation(), AddonClient.deadline()); }
    private static String resource(String manifest, String resource, String type, String id, Map<String, String> extras) throws AddonException {
        return new AddonUrl(manifest).resource(resource, type, id, extras);
    }
    private static List<AddonListing> directory(String list) throws Exception {
        String address = resource(AddonUrl.CINEMETA, AddonManifest.ADDON_CATALOG, "all", list, Collections.emptyMap());
        return AddonListing.parse(list, address, get(address));
    }
    private static AddonListing listing(List<AddonListing> listings, String name) {
        for (AddonListing l : listings) if (l.manifest.name.equals(name)) return l;
        return null;
    }

    @Test public void storeDirectoryListsInstallableSourceAndSubtitleAddons() throws Exception {
        AddonManifest cinemeta = AddonManifest.parse(get(AddonUrl.CINEMETA));
        assertTrue(cinemeta.declares(AddonManifest.ADDON_CATALOG));
        assertTrue(cinemeta.addonCatalogs.stream().anyMatch(c -> c.type.equals("all") && c.id.equals("official")));
        List<AddonListing> official = directory("official"), community = directory("community");
        System.out.println("Official list: " + official.size() + " installable add-ons; community list: " + community.size());
        for (AddonListing l : official) System.out.println("  official  " + l.manifest.name + " — " + l.url);
        AddonListing pdm = listing(official, PUBLIC_DOMAIN_MOVIES);
        assertNotNull("Stremio's official list offers Public Domain Movies", pdm);
        assertTrue(pdm.sources());
        assertNotNull(listing(official, "OpenSubtitles v3"));
        assertTrue(listing(official, "OpenSubtitles v3").subtitles());
        assertNull("The local streaming-server add-on needs Stremio's desktop server", listing(official, "Local Files (without catalog support)"));
        assertTrue(community.size() > 20);
        for (AddonListing l : community) {
            assertFalse(l.manifest.adult);
            assertTrue(l.url, l.url.startsWith("https://") || l.url.startsWith("http://"));
            assertFalse(l.url, l.url.contains("127.0.0.1"));
        }
        // Every listed manifest URL must be installable by the app's own URL rules.
        for (AddonListing l : official) new AddonUrl(l.url);
    }

    @Test public void nightOfTheLivingDeadResolvesFromCinemetaToEverySourceKind() throws Exception {
        String id = "tt0063350";
        Map<String, String> search = Collections.singletonMap("search", "Night of the Living Dead");
        List<Title> found = Title.catalog("cinemeta", get(resource(AddonUrl.CINEMETA, "catalog", "movie", "top", search)));
        Title film = null;
        for (Title t : found) if (t.id.equals(id)) film = t;
        assertNotNull("Cinemeta search finds the 1968 film", film);
        Title details = Title.parse("cinemeta", get(resource(AddonUrl.CINEMETA, "meta", "movie", id, Collections.emptyMap())).optJSONObject("meta"));
        assertEquals(id, details.imdbId);
        assertTrue(details.releaseInfo.startsWith("1968"));
        assertNotNull(details.poster);
        System.out.println("Cinemeta: " + details.name + " (" + details.releaseInfo + "), " + details.directors);

        // Public Domain Movies, installed from the store, answers with a torrent and an explicit file.
        AddonListing pdm = listing(directory("official"), PUBLIC_DOMAIN_MOVIES);
        AddonManifest live = AddonManifest.parse(get(pdm.url));
        assertTrue(live.handles(AddonManifest.STREAM, "movie", id));
        List<MediaSource> torrents = MediaSource.list("pdm", get(resource(pdm.url, AddonManifest.STREAM, "movie", id, Collections.emptyMap())));
        assertFalse("Public Domain Movies has a source for " + id, torrents.isEmpty());
        MediaSource torrent = torrents.get(0);
        assertEquals(MediaSource.Kind.TORRENT, torrent.kind);
        assertTrue(torrent.fileIndex >= 0);
        assertTrue("No tracker hints, so playback adds the public trackers", torrent.discovery.isEmpty());
        assertNotNull(torrent.torrentSource().withFallbackTrackers(MediaSource.PUBLIC_TRACKERS));
        System.out.println("Public Domain Movies: " + torrent.name + " " + torrent.description.replace('\n', ' ') + " infoHash " + torrent.infoHash + " file " + torrent.fileIndex);

        // The built-in Internet Archive add-on answers with direct HTTPS files.
        List<MediaSource> direct = archiveStreams(id);
        assertFalse("The Internet Archive holds " + id, direct.isEmpty());
        MediaSource best = direct.get(0);
        assertEquals(MediaSource.Kind.HTTP, best.kind);
        assertTrue(best.url, best.url.startsWith("https://archive.org/download/"));
        assertTrue(best.videoSize > 100L << 20);
        System.out.println("Internet Archive: " + best.name + " " + best.description.replace('\n', ' ') + " " + best.url);
        verifyPlayable(best.url, "night-of-the-living-dead", 80 * 60);

        // OpenSubtitles v3 from the store's official list: real English subtitles for this film.
        AddonListing subtitles = listing(directory("official"), "OpenSubtitles v3");
        List<SubtitleSource> captions = SubtitleSource.list("opensubtitles", get(resource(subtitles.url, AddonManifest.SUBTITLES, "movie", id, Collections.emptyMap())));
        SubtitleSource english = null;
        for (SubtitleSource s : captions) if (s.language.equals("eng")) { english = s; break; }
        assertNotNull("English subtitles among " + captions.size(), english);
        String text = new String(client.fetch(english.url, 4 << 20, new Cancellation(), AddonClient.deadline()), StandardCharsets.UTF_8);
        assertTrue("SubRip cues", text.contains("-->"));
        System.out.println("OpenSubtitles v3: " + captions.size() + " subtitles; English sample has " + text.split("-->").length + " cues");
    }

    @Test public void builtInCatalogueBrowsesSearchesAndPlaysPublicDomainFilms() throws Exception {
        String catalogue = resource(BuiltinAddons.INTERNET_ARCHIVE, "catalog", "movie", "feature-films", Collections.emptyMap());
        List<Title> first = Title.catalog("archive", get(catalogue));
        assertTrue(first.size() >= 10);
        for (Title t : first) { assertNotNull(t.imdbId); assertNotNull(t.poster); }
        List<Title> second = Title.catalog("archive", get(resource(BuiltinAddons.INTERNET_ARCHIVE, "catalog", "movie", "feature-films", Collections.singletonMap("skip", "20"))));
        assertFalse(second.isEmpty());
        assertNotEquals(first.get(0).id, second.get(0).id);
        List<Title> searched = Title.catalog("archive", get(resource(BuiltinAddons.INTERNET_ARCHIVE, "catalog", "movie", "feature-films", Collections.singletonMap("search", "night of the living dead"))));
        assertTrue(searched.stream().anyMatch(t -> t.id.equals("tt0063350")));
        System.out.println("Public Domain Films catalogue, first page: " + first.size() + " films, e.g. " + first.get(0).name + " (" + first.get(0).releaseInfo + ")");

        int playable = 0;
        StringBuilder table = new StringBuilder("film | Internet Archive (direct HTTPS) | decoded by FFmpeg | Public Domain Movies (torrent)\n");
        AddonListing pdm = listing(directory("official"), PUBLIC_DOMAIN_MOVIES);
        for (Map.Entry<String, String> film : FILMS.entrySet()) {
            List<MediaSource> direct;
            try { direct = archiveStreams(film.getKey()); }
            catch (AddonException e) { table.append(film.getValue()).append(" (").append(film.getKey()).append(") | lookup failed: ").append(e.getMessage()).append('\n'); continue; }
            List<MediaSource> torrents = MediaSource.list("pdm", get(resource(pdm.url, AddonManifest.STREAM, "movie", film.getKey(), Collections.emptyMap())));
            String decoded = "—";
            if (!direct.isEmpty()) {
                try { decoded = verifyPlayable(direct.get(0).url, film.getKey(), 40 * 60); playable++; }
                catch (AssertionError e) { decoded = "FAILED: " + e.getMessage().replace('\n', ' '); }
            }
            table.append(film.getValue()).append(" (").append(film.getKey()).append(") | ")
                    .append(direct.isEmpty() ? "—" : direct.get(0).name + ", " + (direct.get(0).videoSize >> 20) + " MB").append(" | ").append(decoded).append(" | ")
                    .append(torrents.isEmpty() ? "—" : torrents.get(0).name + " " + torrents.get(0).description.replace('\n', ' ')).append('\n');
        }
        System.out.print(table);
        Files.write(new File(evidence, "real-films.txt").toPath(), table.toString().getBytes(StandardCharsets.UTF_8));
        // The Kid has no Archive copy linked by IMDb ID or Wikidata, and Charade's linked item is withdrawn.
        assertTrue("Most of these public-domain films resolve and decode from the Archive", playable >= FILMS.size() - 3);
    }

    @Test public void seriesKeepCinemetaEpisodeIdsForSourceRequests() throws Exception {
        Title series = Title.parse("cinemeta", get(resource(AddonUrl.CINEMETA, "meta", "series", "tt0052520", Collections.emptyMap())).optJSONObject("meta"));
        assertEquals("series", series.type);
        assertTrue(series.episodes.size() > 100);
        Title.Episode pilot = null;
        for (Title.Episode e : series.episodes) if (e.season == 1 && e.number == 1) pilot = e;
        assertNotNull(pilot);
        assertEquals("tt0052520:1:1", pilot.id);
        assertTrue(resource(AddonUrl.CINEMETA, AddonManifest.STREAM, "series", pilot.id, Collections.emptyMap()).endsWith("/stream/series/tt0052520%3A1%3A1.json"));
        System.out.println(series.name + ": " + series.episodes.size() + " episodes; S1E1 \"" + pilot.name + "\" → " + pilot.id);
    }

    private static List<MediaSource> archiveStreams(String imdb) throws AddonException {
        List<MediaSource> sources = MediaSource.list("archive", get(resource(BuiltinAddons.INTERNET_ARCHIVE, AddonManifest.STREAM, "movie", imdb, Collections.emptyMap())));
        List<MediaSource> playable = new ArrayList<>();
        for (MediaSource s : sources) if (s.playable()) playable.add(s);
        return playable;
    }

    /** Range requests (Media3 seeks with them), an MP4 header, then FFmpeg reads the codecs and decodes from the middle. */
    private static String verifyPlayable(String url, String name, long minimumSeconds) throws Exception {
        HttpURLConnection range = (HttpURLConnection) new URL(url).openConnection();
        range.setRequestProperty("Range", "bytes=0-65535");
        range.setConnectTimeout(20_000); range.setReadTimeout(20_000);
        assertEquals(206, range.getResponseCode());
        byte[] head = new byte[12];
        try (InputStream in = range.getInputStream()) { int read = 0; while (read < 12) read += in.read(head, read, 12 - read); }
        assertEquals("ftyp", new String(head, 4, 4, StandardCharsets.US_ASCII));
        range.disconnect();
        if (!command("ffprobe", "-version")) { System.out.println("ffprobe unavailable; skipped codec and decode checks"); return "not checked"; }
        String probe = run("ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name,profile,width,height:format=duration", "-of", "json", url);
        JSONObject info = new JSONObject(probe);
        JSONObject video = null;
        for (int i = 0; i < info.getJSONArray("streams").length(); i++) {
            JSONObject s = info.getJSONArray("streams").getJSONObject(i);
            if (s.getString("codec_type").equals("video")) video = s;
        }
        assertNotNull(video);
        assertTrue("A codec every Android TV decodes", video.getString("codec_name").matches("h264|hevc|vp9|mpeg4"));
        assertTrue("Within the 1080p device profile", video.getInt("height") <= 1080);
        assertTrue(Double.parseDouble(info.getJSONObject("format").getString("duration")) >= minimumSeconds);
        File frame = new File(evidence, name + ".jpg");
        run("ffmpeg", "-v", "error", "-y", "-ss", "1800", "-i", url, "-frames:v", "1", "-q:v", "3", frame.getPath());
        assertTrue("Decoded a frame half an hour in", frame.length() > 2000);
        String summary = video.getString("codec_name") + " " + video.optString("profile") + " " + video.getInt("width") + "×" + video.getInt("height")
                + ", " + Math.round(Double.parseDouble(info.getJSONObject("format").getString("duration")) / 60) + " min, frame at 30:00";
        System.out.println("FFmpeg: " + summary + " → " + frame);
        return summary;
    }
    private static boolean command(String... args) {
        try { return new ProcessBuilder(args).redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS); } catch (Exception e) { return false; }
    }
    private static String run(String... args) throws Exception {
        try { return runOnce(args); }
        catch (AssertionError busy) {
            // archive.org answers 5xx now and then under load; one later attempt shows whether the file really fails.
            if (!String.valueOf(busy.getMessage()).contains("5XX")) throw busy;
            Thread.sleep(3000);
            return runOnce(args);
        }
    }
    private static String runOnce(String... args) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(args);
        // FFmpeg reads only http_proxy; use the same proxy as the JVM when one is configured.
        String host = System.getProperty("https.proxyHost"), port = System.getProperty("https.proxyPort");
        if (host != null && port != null) builder.environment().put("http_proxy", "http://" + host + ":" + port);
        Process process = builder.redirectErrorStream(false).start();
        byte[] out = process.getInputStream().readAllBytes();
        assertTrue(args[0] + " timed out", process.waitFor(180, TimeUnit.SECONDS));
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(args[0] + " failed: " + err, 0, process.exitValue());
        return new String(out, StandardCharsets.UTF_8);
    }
}
