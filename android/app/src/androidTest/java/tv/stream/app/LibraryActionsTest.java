package tv.stream.app;

import static org.junit.Assert.*;
import org.junit.Test;

/** Library actions follow the saved state, even before a title has been played. */
public final class LibraryActionsTest extends AppHarness {
    @Test public void removeAppearsOnlyAfterSavingTitle() throws Exception {
        readyLibrary(); launch();
        waitUntil("Home", 10000, () -> visible("Generated Film"));
        tap("Generated Film", false);
        waitUntil("Details", 10000, () -> visible("Add favourite"));
        assertFalse("An unsaved title has no empty More menu", visible("More"));
        tap("Add favourite", false);
        waitUntil("Saved title has More", 5000, () -> visible("More"));
        tap("More", false);
        waitUntil("Saved title can be removed", 5000, () -> visible("Remove from library"));
        tap("Remove from library", false);
        tap("Remove", false);
        waitUntil("Library is empty after removal", 5000, () -> visible("Your library is empty"));
    }
}
