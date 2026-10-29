package tv.stream.app

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.stream.app.addon.AddonManifest
import tv.stream.app.addon.MediaSource
import tv.stream.app.addon.Title
import tv.stream.playback.PlaybackRequest
import tv.stream.playback.SubtitleDownloader
import tv.stream.playback.VideoQualities

/** Requests and provider results can cross workers without carrying their producer's mutable containers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KotlinModelSnapshotsTest {
    @Test fun queuedPlaybackRetainsItsHeadersTracksAndStartPosition() {
        val headers = linkedMapOf("Referer" to "https://media.example/accepted")
        val names = mutableListOf("Original")
        val subtitles = mutableListOf(SubtitleDownloader.Source("https://media.example/subtitle.srt", "", "eng", "English"))
        val request = PlaybackRequest(
            Uri.parse("https://media.example/film.mkv"), "Film", headers = headers,
            audioNames = names, subtitles = subtitles, positionMs = -500,
        )
        // The provider/next episode reuses its containers after this request was queued.
        headers["Referer"] = "https://media.example/next"
        names[0] = "Next voice-over"
        subtitles.clear()
        assertEquals("https://media.example/accepted", request.headers["Referer"])
        assertEquals(request.headers, request.subtitleHeaders)
        assertEquals(listOf("Original"), request.audioNames)
        assertEquals("English", request.subtitles.single().label)
        assertEquals(0L, request.positionMs)
        assertEquals("https://media.example/film.mkv", request.mediaIdentity)
        assertThrows(UnsupportedOperationException::class.java) { (request.headers as MutableMap<String, String>)["Referer"] = "changed" }
    }

    @Test fun explicitEmptySubtitleHeadersDoNotInheritMediaCredentials() {
        val request = PlaybackRequest.Builder(Uri.parse("https://media.example/film"), "Film")
            .headers(mapOf("Authorization" to "media-only"))
            .subtitleHeaders(emptyMap()).position(120_000).build()
        assertTrue(request.subtitleHeaders.isEmpty())
        assertEquals("media-only", request.headers["Authorization"])
        assertEquals(120_000L, request.positionMs)
    }

    @Test fun sourceLocationsRetainProtocolRejectionPrecedence() {
        fun source(json: JSONObject) = MediaSource.list("provider", JSONObject().put("streams", JSONArray().put(json))).single()
        val browser = source(JSONObject().put("url", "https://media.example/film").put("externalUrl", "https://media.example/watch"))
        assertEquals(MediaSource.Location.Unsupported("Opens an external browser"), browser.location)
        assertFalse(browser.playable())
        assertEquals(MediaSource.Kind.UNSUPPORTED, browser.kind)
        val hash = "a".repeat(40)
        val torrent = source(JSONObject().put("infoHash", hash).put("url", "https://media.example/film"))
        assertEquals(MediaSource.Location.Torrent(hash), torrent.location)
        assertEquals(MediaSource.Kind.TORRENT, torrent.kind)
        assertEquals("", torrent.reason)
        val invalid = source(JSONObject().put("infoHash", hash).put("fileIdx", -1))
        assertEquals(MediaSource.Location.Unsupported("Invalid torrent file index"), invalid.location)
    }

    @Test fun enrichingMetadataKeepsInlineSourceOwnershipAndNeverCachesPlayableAddresses() {
        val stream = JSONObject().put("url", "https://media.example/private/film.mkv")
            .put("behaviorHints", JSONObject().put("proxyHeaders", JSONObject()
                .put("request", JSONObject().put("Authorization", "private"))))
        val episode = JSONObject().put("id", "episode-1").put("title", "First")
            .put("season", 1).put("episode", 1).put("streams", JSONArray().put(stream))
            .put("thumbnail", "https://media.example/private/thumbnail.jpg")
        val raw = JSONObject().put("id", "tt1234567").put("type", "series").put("name", "A &amp; B")
            .put("genres", JSONArray().put("Drama")).put("videos", JSONArray().put(episode))
            .put("poster", "https://media.example/private/poster.jpg")
        val metadata = requireNotNull(Title.parse("metadata-provider", raw))
        val title = metadata.withProvider("catalogue-provider")
        raw.put("name", "Changed").getJSONArray("genres").put("Changed")
        assertEquals("catalogue-provider", title.provider)
        assertEquals("metadata-provider", title.episodes.single().streams.single().provider)
        assertEquals("A & B", title.name)
        assertEquals(listOf("Drama"), title.genres)
        assertSame(title, title.withProvider("catalogue-provider"))
        assertFalse(title.toString().contains("private"))
        assertFalse(title.episodes.single().toString().contains("private"))
        val cached = JSONObject(title.cacheJson())
        assertFalse(cached.getJSONArray("videos").getJSONObject(0).has("streams"))
        assertFalse(title.cacheJson().contains("film.mkv"))
        assertFalse(title.cacheJson().contains("Authorization"))
        assertTrue(requireNotNull(Title.parse(title.provider, JSONObject(title.cacheJson()))).episodes.single().streams.isEmpty())
    }

    @Test fun catalogueCapabilitiesDoNotChangeWhenTheirInputListsAreReused() {
        val options = mutableListOf("Drama")
        val extras = mutableListOf(AddonManifest.Extra("genre", false, options))
        val catalog = AddonManifest.Catalog("movie", "featured", "Featured", extras)
        options.add("Comedy")
        extras.add(AddonManifest.Extra("search", true, emptyList()))
        assertTrue(catalog.listable())
        assertFalse(catalog.searchable())
        assertEquals(listOf("Drama"), catalog.extra("genre")!!.options)
    }

    @Test fun qualityOptionsGroupAdaptiveTracksAndKeepCroppedFilmResolution() {
        val formats = arrayOf(
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1920).setHeight(800).build(),
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1280).setHeight(720).build(),
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1920).setHeight(1080).build(),
        )
        val group = TrackGroup(*formats)
        val tracks = Tracks(listOf(Tracks.Group(group, true, IntArray(3) { C.FORMAT_HANDLED }, booleanArrayOf(true, false, false))))
        val options = VideoQualities.from(tracks)
        assertEquals(listOf("1080p", "720p"), options.map { it.label })
        assertEquals(listOf(0, 2), options[0].indices)
        assertThrows(UnsupportedOperationException::class.java) { (options[0].indices as MutableList<Int>).add(1) }
        assertSame(group, options[0].group)
    }
}
