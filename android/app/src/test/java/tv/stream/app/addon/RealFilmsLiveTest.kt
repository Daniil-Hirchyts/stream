package tv.stream.app.addon

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Real films through the app's add-on code and live services: Stremio's add-on directory, Cinemeta, Public Domain
 * Movies, OpenSubtitles and the built-in Internet Archive add-on. Every film here is in the public domain. Runs only
 * with `./gradlew :app:testDebugUnitTest -PliveAddons=true`; FFmpeg checks are skipped when it isn't installed.
 * Required tests stay offline and deterministic.
 */
class RealFilmsLiveTest {
    @Test @Throws(Exception::class) fun storeDirectoryListsInstallableSourceAndSubtitleAddons() {
        val cinemeta = AddonManifest.parse(get(AddonUrl.CINEMETA))
        assertTrue(cinemeta.declares(AddonManifest.ADDON_CATALOG))
        assertTrue(cinemeta.addonCatalogs.stream().anyMatch { c -> c.type.equals("all") && c.id.equals("official") })
        val official = directory("official"); val community = directory("community")
        System.out.println("Official list: " + official.size + " installable add-ons; community list: " + community.size)
        for (l in official) System.out.println("  official  " + l.manifest.name + " — " + l.url)
        val pdm = listing(official, PUBLIC_DOMAIN_MOVIES)
        assertNotNull("Stremio's official list offers Public Domain Movies", pdm)
        assertTrue(pdm!!.sources())
        assertNotNull(listing(official, "OpenSubtitles v3"))
        assertTrue(listing(official, "OpenSubtitles v3")!!.subtitles())
        assertNull("The local streaming-server add-on needs Stremio's desktop server", listing(official, "Local Files (without catalog support)"))
        assertTrue(community.size > 20)
        for (l in community) {
            assertFalse(l.manifest.adult)
            assertTrue(l.url, l.url.startsWith("https://") || l.url.startsWith("http://"))
            assertFalse(l.url, l.url.contains("127.0.0.1"))
        }
        // Every listed manifest URL must be installable by the app's own URL rules.
        for (l in official) AddonUrl(l.url)
    }

    @Test @Throws(Exception::class) fun nightOfTheLivingDeadResolvesFromCinemetaToEverySourceKind() {
        val id = "tt0063350"
        val search: Map<String, String> = Collections.singletonMap("search", "Night of the Living Dead")
        val found: List<Title> = Title.catalog("cinemeta", get(resource(AddonUrl.CINEMETA, "catalog", "movie", "top", search)))
        var film: Title? = null
        for (t in found) if (t.id.equals(id)) film = t
        assertNotNull("Cinemeta search finds the 1968 film", film)
        val details = Title.parse("cinemeta", get(resource(AddonUrl.CINEMETA, "meta", "movie", id, Collections.emptyMap())).optJSONObject("meta"))!!
        assertEquals(id, details.imdbId)
        assertTrue(details.releaseInfo.startsWith("1968"))
        assertNotNull(details.poster)
        System.out.println("Cinemeta: " + details.name + " (" + details.releaseInfo + "), " + details.directors)

        // Public Domain Movies, installed from the store, answers with a torrent and an explicit file.
        val pdm = listing(directory("official"), PUBLIC_DOMAIN_MOVIES)
        val live = AddonManifest.parse(get(pdm!!.url))
        assertTrue(live.handles(AddonManifest.STREAM, "movie", id))
        val torrents: List<MediaSource> = MediaSource.list("pdm", get(resource(pdm.url, AddonManifest.STREAM, "movie", id, Collections.emptyMap())))
        assertFalse("Public Domain Movies has a source for " + id, torrents.isEmpty())
        val torrent = torrents.get(0)
        assertEquals(MediaSource.Kind.TORRENT, torrent.kind)
        assertTrue(torrent.fileIndex >= 0)
        assertTrue("No tracker hints, so playback adds the public trackers", torrent.discovery.isEmpty())
        assertNotNull(torrent.torrentSource().withFallbackTrackers(*MediaSource.PUBLIC_TRACKERS))
        System.out.println("Public Domain Movies: " + torrent.name + " " + torrent.description.replace('\n', ' ') + " infoHash " + torrent.infoHash + " file " + torrent.fileIndex)

        // The built-in Internet Archive add-on answers with direct HTTPS files.
        val direct = archiveStreams(id)
        assertFalse("The Internet Archive holds " + id, direct.isEmpty())
        val best = direct.get(0)
        assertEquals(MediaSource.Kind.HTTP, best.kind)
        assertTrue(best.url, best.url!!.startsWith("https://archive.org/download/"))
        assertTrue(best.videoSize > (100L shl 20))
        System.out.println("Internet Archive: " + best.name + " " + best.description.replace('\n', ' ') + " " + best.url)
        verifyPlayable(best.url!!, "night-of-the-living-dead", 80 * 60L)

        // OpenSubtitles v3 from the store's official list: real English subtitles for this film.
        val subtitles = listing(directory("official"), "OpenSubtitles v3")
        val captions: List<SubtitleSource> = SubtitleSource.list("opensubtitles", get(resource(subtitles!!.url, AddonManifest.SUBTITLES, "movie", id, Collections.emptyMap())))
        var english: SubtitleSource? = null
        for (s in captions) if (s.language.equals("eng")) { english = s; break }
        assertNotNull("English subtitles among " + captions.size, english)
        val text = String(client!!.fetch(english!!.url, 4 shl 20, Cancellation(), AddonClient.deadline()), StandardCharsets.UTF_8)
        assertTrue("SubRip cues", text.contains("-->"))
        System.out.println("OpenSubtitles v3: " + captions.size + " subtitles; English sample has " + java.util.regex.Pattern.compile("-->").split(text).size + " cues")
    }

    @Test @Throws(Exception::class) fun builtInCatalogueBrowsesSearchesAndPlaysPublicDomainFilms() {
        val catalogue = resource(BuiltinAddons.INTERNET_ARCHIVE, "catalog", "movie", "feature-films", Collections.emptyMap())
        val first: List<Title> = Title.catalog("archive", get(catalogue))
        assertTrue(first.size >= 10)
        for (t in first) { assertNotNull(t.imdbId); assertNotNull(t.poster) }
        val second: List<Title> = Title.catalog("archive", get(resource(BuiltinAddons.INTERNET_ARCHIVE, "catalog", "movie", "feature-films", Collections.singletonMap("skip", "20"))))
        assertFalse(second.isEmpty())
        assertNotEquals(first.get(0).id, second.get(0).id)
        val searched: List<Title> = Title.catalog("archive", get(resource(BuiltinAddons.INTERNET_ARCHIVE, "catalog", "movie", "feature-films", Collections.singletonMap("search", "night of the living dead"))))
        assertTrue(searched.stream().anyMatch { t -> t.id.equals("tt0063350") })
        System.out.println("Public Domain Films catalogue, first page: " + first.size + " films, e.g. " + first.get(0).name + " (" + first.get(0).releaseInfo + ")")

        var playable = 0
        val table = StringBuilder("film | Internet Archive (direct HTTPS) | decoded by FFmpeg | Public Domain Movies (torrent)\n")
        val pdm = listing(directory("official"), PUBLIC_DOMAIN_MOVIES)
        for (film in FILMS.entries) {
            val direct: List<MediaSource> = try { archiveStreams(film.key) }
            catch (e: AddonException) { table.append(film.value).append(" (").append(film.key).append(") | lookup failed: ").append(e.message).append('\n'); continue }
            val torrents: List<MediaSource> = MediaSource.list("pdm", get(resource(pdm!!.url, AddonManifest.STREAM, "movie", film.key, Collections.emptyMap())))
            var decoded = "—"
            if (!direct.isEmpty()) {
                try { decoded = verifyPlayable(direct.get(0).url!!, film.key, 40 * 60L); playable++ }
                catch (e: AssertionError) { decoded = "FAILED: " + e.message!!.replace('\n', ' ') }
            }
            table.append(film.value).append(" (").append(film.key).append(") | ")
                    .append(if (direct.isEmpty()) "—" else direct.get(0).name + ", " + (direct.get(0).videoSize shr 20) + " MB").append(" | ").append(decoded).append(" | ")
                    .append(if (torrents.isEmpty()) "—" else torrents.get(0).name + " " + torrents.get(0).description.replace('\n', ' ')).append('\n')
        }
        System.out.print(table)
        Files.write(File(evidence, "real-films.txt").toPath(), table.toString().toByteArray(StandardCharsets.UTF_8))
        // The Kid has no Archive copy linked by IMDb ID or Wikidata, and Charade's linked item is withdrawn.
        assertTrue("Most of these public-domain films resolve and decode from the Archive", playable >= FILMS.size - 3)
    }

    @Test @Throws(Exception::class) fun seriesKeepCinemetaEpisodeIdsForSourceRequests() {
        val series = Title.parse("cinemeta", get(resource(AddonUrl.CINEMETA, "meta", "series", "tt0052520", Collections.emptyMap())).optJSONObject("meta"))!!
        assertEquals("series", series.type)
        assertTrue(series.episodes.size > 100)
        var pilot: Title.Episode? = null
        for (e in series.episodes) if (e.season == 1 && e.number == 1) pilot = e
        assertNotNull(pilot)
        assertEquals("tt0052520:1:1", pilot!!.id)
        assertTrue(resource(AddonUrl.CINEMETA, AddonManifest.STREAM, "series", pilot.id, Collections.emptyMap()).endsWith("/stream/series/tt0052520%3A1%3A1.json"))
        System.out.println(series.name + ": " + series.episodes.size + " episodes; S1E1 \"" + pilot.name + "\" → " + pilot.id)
    }

    companion object {
        /** IMDb ID → title. All are public-domain feature films. */
        private val FILMS: MutableMap<String, String> = LinkedHashMap()
        init {
            FILMS.put("tt0063350", "Night of the Living Dead")
            FILMS.put("tt0012349", "The Kid")
            FILMS.put("tt0013442", "Nosferatu")
            FILMS.put("tt0017925", "The General")
            FILMS.put("tt0032599", "His Girl Friday")
            FILMS.put("tt0056923", "Charade")
            FILMS.put("tt0058700", "The Last Man on Earth")
            FILMS.put("tt0051744", "House on Haunted Hill")
        }
        private const val PUBLIC_DOMAIN_MOVIES = "Public Domain Movies"
        private var client: AddonClient? = null
        private var evidence: File? = null

        @BeforeClass @JvmStatic @Throws(Exception::class) fun live() {
            assumeTrue("Live add-on tests run with -PliveAddons=true", java.lang.Boolean.getBoolean("dtv.live"))
            client = AddonClient()
            evidence = File(System.getProperty("dtv.evidence", "build/live-evidence"))
            Files.createDirectories(evidence!!.toPath())
        }
        @AfterClass @JvmStatic fun close() { if (client != null) client!!.close() }
        @Throws(AddonException::class)
        private fun get(address: String): JSONObject { return client!!.json(address, Cancellation(), AddonClient.deadline()) }
        @Throws(AddonException::class)
        private fun resource(manifest: String, resource: String, type: String, id: String, extras: Map<String, String>): String {
            return AddonUrl(manifest).resource(resource, type, id, extras)
        }
        @Throws(Exception::class)
        private fun directory(list: String): List<AddonListing> {
            val address = resource(AddonUrl.CINEMETA, AddonManifest.ADDON_CATALOG, "all", list, Collections.emptyMap())
            return AddonListing.parse(list, address, get(address))
        }
        private fun listing(listings: List<AddonListing>, name: String): AddonListing? {
            for (l in listings) if (l.manifest.name.equals(name)) return l
            return null
        }

        @Throws(AddonException::class)
        private fun archiveStreams(imdb: String): List<MediaSource> {
            val sources: List<MediaSource> = MediaSource.list("archive", get(resource(BuiltinAddons.INTERNET_ARCHIVE, AddonManifest.STREAM, "movie", imdb, Collections.emptyMap())))
            val playable: MutableList<MediaSource> = ArrayList()
            for (s in sources) if (s.playable()) playable.add(s)
            return playable
        }

        /** Range requests (Media3 seeks with them), an MP4 header, then FFmpeg reads the codecs and decodes from the middle. */
        @Throws(Exception::class)
        private fun verifyPlayable(url: String, name: String, minimumSeconds: Long): String {
            val range = URL(url).openConnection() as HttpURLConnection
            range.setRequestProperty("Range", "bytes=0-65535")
            range.setConnectTimeout(20_000); range.setReadTimeout(20_000)
            assertEquals(206, range.getResponseCode())
            val head = ByteArray(12)
            range.getInputStream().use { `in`: InputStream -> var read = 0; while (read < 12) read += `in`.read(head, read, 12 - read) }
            assertEquals("ftyp", String(head, 4, 4, StandardCharsets.US_ASCII))
            range.disconnect()
            if (!command("ffprobe", "-version")) { System.out.println("ffprobe unavailable; skipped codec and decode checks"); return "not checked" }
            val probe = run("ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name,profile,width,height:format=duration", "-of", "json", url)
            val info = JSONObject(probe)
            var video: JSONObject? = null
            for (i in 0 until info.getJSONArray("streams").length()) {
                val s = info.getJSONArray("streams").getJSONObject(i)
                if (s.getString("codec_type").equals("video")) video = s
            }
            assertNotNull(video)
            assertTrue("A codec every Android TV decodes", video!!.getString("codec_name").matches(Regex("h264|hevc|vp9|mpeg4")))
            assertTrue("Within the 1080p device profile", video.getInt("height") <= 1080)
            assertTrue(java.lang.Double.parseDouble(info.getJSONObject("format").getString("duration")) >= minimumSeconds)
            val frame = File(evidence, name + ".jpg")
            run("ffmpeg", "-v", "error", "-y", "-ss", "1800", "-i", url, "-frames:v", "1", "-q:v", "3", frame.getPath())
            assertTrue("Decoded a frame half an hour in", frame.length() > 2000)
            val summary = (video.getString("codec_name") + " " + video.optString("profile") + " " + video.getInt("width") + "×" + video.getInt("height")
                    + ", " + Math.round(java.lang.Double.parseDouble(info.getJSONObject("format").getString("duration")) / 60) + " min, frame at 30:00")
            System.out.println("FFmpeg: " + summary + " → " + frame)
            return summary
        }
        private fun command(vararg args: String): Boolean {
            try { return ProcessBuilder(*args).redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS) } catch (e: Exception) { return false }
        }
        @Throws(Exception::class)
        private fun run(vararg args: String): String {
            try { return runOnce(*args) }
            catch (busy: AssertionError) {
                // archive.org answers 5xx now and then under load; one later attempt shows whether the file really fails.
                if (!busy.message.toString().contains("5XX")) throw busy
                Thread.sleep(3000)
                return runOnce(*args)
            }
        }
        @Throws(Exception::class)
        private fun runOnce(vararg args: String): String {
            val builder = ProcessBuilder(*args)
            // FFmpeg reads only http_proxy; use the same proxy as the JVM when one is configured.
            val host: String? = System.getProperty("https.proxyHost"); val port: String? = System.getProperty("https.proxyPort")
            if (host != null && port != null) builder.environment().put("http_proxy", "http://" + host + ":" + port)
            val process = builder.redirectErrorStream(false).start()
            val out = process.getInputStream().readAllBytes()
            assertTrue(args[0] + " timed out", process.waitFor(180, TimeUnit.SECONDS))
            val err = String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
            assertEquals(args[0] + " failed: " + err, 0, process.exitValue())
            return String(out, StandardCharsets.UTF_8)
        }
    }
}
