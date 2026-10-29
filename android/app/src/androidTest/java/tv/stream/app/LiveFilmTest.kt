package tv.stream.app

import org.junit.Assert.*
import android.content.Context
import org.json.JSONObject
import tv.stream.app.addon.Title
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real public-domain films over the internet, installed through the store as a viewer would. Not part of the
 * required suites: `./simulator.sh live-film` runs it on the verified emulator. The torrent case depends
 * on the public swarm having peers at the time.
 */
@RunWith(AndroidJUnit4::class)
class LiveFilmTest : AppHarness() {
    @Before fun internet() {
        context.getSharedPreferences("instrumentation", Context.MODE_PRIVATE).edit().putBoolean("localOnly", false)
                .putBoolean("publicNetworkOnly", true)
                .remove("peer").remove("cacheBytes").remove("addonDirectory").commit()
        app.resetServices(); services = app.services()
    }
    /** Installs through the store's real rows and review page, then opens the film's details from that provider. */
    @Throws(Exception::class)
    private fun installAndOpen(addon: String, imdb: String, name: String) {
        tap("Settings", true); tap("Add-on store", false); tap(addon, false); tap("Install", true)
        waitUntil("$addon installed", 20_000) { services.store.addons().stream().anyMatch { a -> a.manifest.name == addon } }
        val provider = services.store.addons().stream().filter { a -> a.manifest.name == addon }.findFirst().get().id
        val film = Title.parse(provider, JSONObject().put("id", imdb).put("type", "movie").put("name", name))!!
        browse!!.onActivity { activity -> activity.openTitle(film, "") }
    }

    @Test @Throws(Exception::class) fun internetArchiveFilmPlaysFromTheStore() {
        launch(); installAndOpen("Internet Archive", "tt0063350", "Night of the Living Dead"); tap("Play", true); chooseFirstSource()
        waitUntil("Night of the Living Dead plays from archive.org", 120_000
        ) { state() != null && state()!!.renderedFirstFrame && state()!!.playing && state()!!.positionMs >= 5000 }
        val activity = watch()
        instrumentation.runOnMainSync { AppFlowTest.session(activity).seekTo((30 * 60_000).toLong()) }
        waitUntil("seek half an hour in", 90_000) { state()!!.playing && state()!!.positionMs >= 30 * 60_000 + 3000 }
    }

    @Test @Throws(Exception::class) fun publicDomainMoviesTorrentPlaysFromTheStore() {
        launch(); installAndOpen("Public Domain Movies", "tt0063350", "Night of the Living Dead"); tap("Play", true); chooseFirstSource()
        waitUntil("Night of the Living Dead plays from the public swarm", 300_000
        ) { state() != null && state()!!.renderedFirstFrame && state()!!.playing && state()!!.positionMs >= 5000 }
        assertTrue(diagnostics().toString(), diagnostics().optInt("peers") > 0)
    }
}
