package tv.stream.app

import org.junit.Assert.*
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Test
import tv.stream.app.addon.AddonRepository
import tv.stream.app.addon.Cancellation

/** Slow loopback catalogues reproduce a Home load that takes more than one discovery window in total. */
class HomeSchedulingTest : AppHarness() {
    @Test @Throws(Exception::class) fun aSlowCatalogueDoesNotBlockLaterHealthyShelves() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            server.catalogCount = 5; server.homeGateCatalog = "browse"; server.homeGate = CountDownLatch(1)
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            val healthy = CountDownLatch(1)
            services.addons.newRequest().use { request ->
                val pending: Future<AddonRepository.CatalogPage> = app.workers.submit(Callable { services.addons.catalogsPage(services.addons.homeCatalogs(), request
                ) { partial -> if (partial.result.items.size >= 96) healthy.countDown() } })
                try {
                    assertTrue("Four healthy shelves arrive while the first catalogue is still blocked", healthy.await(5, TimeUnit.SECONDS))
                    assertEquals(5, server.homeRequests.get().toLong())
                    server.homeGate!!.countDown()
                    assertTrue(pending.get(5, TimeUnit.SECONDS).result.failures.isEmpty())
                } finally { server.homeGate!!.countDown(); pending.cancel(true) }
            }
        }
    }
    @Test @Throws(Exception::class) fun queuedHomeCataloguesGetTheirOwnNetworkBudgetAndHealthyShelvesArriveProgressively() {
        BrowseRecoveryTest.CatalogServer().use { server ->
            server.catalogCount = 5; server.catalogueDelayMs = 8100
            services.addons.newRequest().use { request ->
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request))
            }
            val progressive = AtomicBoolean(); val premature = AtomicBoolean()
            services.addons.newRequest().use { request ->
                val found = services.addons.catalogsPage(services.addons.homeCatalogs(), request) { partial ->
                    if (partial.loading && !partial.result.items.isEmpty() && partial.result.items.size < 120) progressive.set(true)
                    if (!partial.loading && partial.result.items.size < 120) premature.set(true)
                }
                assertTrue(found.result.failures.toString(), found.result.failures.isEmpty())
                assertEquals("Queue time does not force healthy catalogues into a retry", 1, found.attempt.toLong())
                assertEquals(5, server.homeRequests.get().toLong()); assertEquals(120, found.result.items.size.toLong())
                assertTrue("Early shelves appear before the last wave", progressive.get())
                assertFalse("The later wave is still loading", premature.get())
            }
        }
    }
}
