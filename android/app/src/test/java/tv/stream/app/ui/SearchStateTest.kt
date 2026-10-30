package tv.stream.app.ui

import android.view.KeyEvent
import java.util.function.Consumer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search navigation must remain recoverable before the next Compose frame has moved focus. */
class SearchStateTest {
    private fun item(input: (String) -> Unit = {}): Item = Item.keyboard("keyboard", "Film", Consumer(input)).also { item ->
        item.tiles = (0..2).map { index ->
            Item.Tile("search:$index", Item.Tile.Style.POSTER, "Film $index", "", null, null, Runnable {})
        }
    }

    @Test fun hidingBeforeTheNextFrameSavesTheSelectedFilmInsteadOfTheDepartingKeyboard() {
        val item = item()
        val state = SearchBlocks.State(item) {}
        state.focused("search:2", false)
        state.focused("keyboard:f", false)
        state.keyboard(false)

        // Android may save immediately after the gesture, before the animation's focus handoff runs.
        val saved = state.persistent("keyboard:f")
        assertEquals("search:2", saved)
        val recreated = SearchBlocks.State(item.snapshot()) {}
        assertFalse(recreated.keyboardVisible)
        assertEquals(saved, recreated.persistent(saved))

        state.delivered(saved)
        state.focused("search:1", false)
        assertEquals("A completed handoff must not pin subsequent navigation", "search:1", state.persistent("search:1"))
    }

    @Test fun revealingBeforeTheNextFrameSavesTheRememberedKeyboardKey() {
        val item = item()
        val state = SearchBlocks.State(item) {}
        state.focused("keyboard:f", false)
        state.focused("search:2", false)
        state.keyboard(false)
        state.delivered("search:2")
        state.keyboard(true)

        val saved = state.persistent("search:2")
        assertEquals("keyboard:f", saved)
        val recreated = SearchBlocks.State(item.snapshot()) {}
        assertTrue(recreated.keyboardVisible)
        assertEquals(saved, recreated.persistent(saved))
    }

    @Test fun modeTogglesAndLeftFromEmptyResultsNeverSubmitAnotherProviderQuery() {
        val queries = ArrayList<String>()
        val modes = ArrayList<Boolean>()
        val item = item(queries::add).also { it.tiles = emptyList() }
        val state = SearchBlocks.State(item, modeChanged = { modes.add(it) })
        state.current = { "search-results" }

        state.toggle()
        assertFalse(state.keyboardVisible)
        assertTrue(state.key(KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(state.keyboardVisible)
        assertEquals(listOf(false, true), modes)
        assertTrue("Only editing submits a query; focus and layout stay local", queries.isEmpty())
        assertEquals("Film", state.query)
    }
}
