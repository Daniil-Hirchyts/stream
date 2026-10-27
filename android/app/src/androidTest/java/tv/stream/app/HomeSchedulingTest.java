package tv.stream.app;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import tv.stream.app.addon.AddonRepository;
import tv.stream.app.addon.Cancellation;

/** Slow loopback catalogues reproduce a Home load that takes more than one discovery window in total. */
public final class HomeSchedulingTest extends AppHarness {
    @Test public void aSlowCatalogueDoesNotBlockLaterHealthyShelves() throws Exception {
        try (BrowseRecoveryTest.CatalogServer server = new BrowseRecoveryTest.CatalogServer()) {
            server.catalogCount = 5; server.homeGateCatalog = "browse"; server.homeGate = new CountDownLatch(1);
            try (Cancellation request = services.addons.newRequest()) {
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request));
            }
            CountDownLatch healthy = new CountDownLatch(1);
            try (Cancellation request = services.addons.newRequest()) {
                Future<AddonRepository.CatalogPage> pending = app.workers.submit(() -> services.addons.catalogsPage(services.addons.homeCatalogs(), request,
                        partial -> { if (partial.result.items.size() >= 96) healthy.countDown(); }));
                try {
                    assertTrue("Four healthy shelves arrive while the first catalogue is still blocked", healthy.await(5, TimeUnit.SECONDS));
                    assertEquals(5, server.homeRequests.get());
                    server.homeGate.countDown();
                    assertTrue(pending.get(5, TimeUnit.SECONDS).result.failures.isEmpty());
                } finally { server.homeGate.countDown(); pending.cancel(true); }
            }
        }
    }
    @Test public void queuedHomeCataloguesGetTheirOwnNetworkBudgetAndHealthyShelvesArriveProgressively() throws Exception {
        try (BrowseRecoveryTest.CatalogServer server = new BrowseRecoveryTest.CatalogServer()) {
            server.catalogCount = 5; server.catalogueDelayMs = 8100;
            try (Cancellation request = services.addons.newRequest()) {
                services.addons.install(server.url + "/manifest.json", services.addons.preview(server.url + "/manifest.json", request));
            }
            AtomicBoolean progressive = new AtomicBoolean(), premature = new AtomicBoolean();
            try (Cancellation request = services.addons.newRequest()) {
                AddonRepository.CatalogPage found = services.addons.catalogsPage(services.addons.homeCatalogs(), request, partial -> {
                    if (partial.loading && !partial.result.items.isEmpty() && partial.result.items.size() < 120) progressive.set(true);
                    if (!partial.loading && partial.result.items.size() < 120) premature.set(true);
                });
                assertTrue(found.result.failures.toString(), found.result.failures.isEmpty());
                assertEquals("Queue time does not force healthy catalogues into a retry", 1, found.attempt);
                assertEquals(5, server.homeRequests.get()); assertEquals(120, found.result.items.size());
                assertTrue("Early shelves appear before the last wave", progressive.get());
                assertFalse("The later wave is still loading", premature.get());
            }
        }
    }
}
