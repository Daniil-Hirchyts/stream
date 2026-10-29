package tv.stream.app

import org.junit.Assert.*
import org.junit.Test
import tv.stream.app.ui.Page

class NavigationViewModelTest {
    @Test fun tabsAndBackPreserveFocusedControlAndSearchReturnsToItsOrigin() {
        val model = NavigationViewModel()
        model.root("Browse")
        val browse = model.stack.single()
        browse.focus = "catalogue:films"
        model.push(Route("Catalogue").apply { value = "films" })
        model.pop()
        assertSame(browse, model.stack.single())
        model.search("Browse")
        model.stack.single().value = "Generated"
        model.root(model.searchReturnTab)
        assertSame(browse, model.stack.single())
        assertEquals("catalogue:films", browse.focus)
        assertFalse(model.state.value.searchExpanded)
        model.search("Browse")
        assertEquals("Generated", model.stack.single().value)
    }

    @Test fun savedNavigationRestoresIdentityFocusAndCategoryWithoutActivityActions() {
        val model = NavigationViewModel()
        model.root("Settings")
        model.stack.single().extras["category"] = "settings:subtitles"
        model.push(Route("Add-on").apply { value = "provider"; focus = "remove"; cache = Page() })
        val restored = NavigationViewModel()
        restored.restore(model.save(), "Home")
        assertEquals(listOf("Settings", "Add-on"), restored.stack.map { it.screen })
        assertEquals("settings:subtitles", restored.stack.first().extras["category"])
        assertEquals("provider", restored.stack.last().value)
        assertEquals("remove", restored.stack.last().focus)
        assertNull(restored.stack.last().cache)
        val outgoing = model.stack.last()
        model.detach()
        outgoing.focus = "late-provider-result"
        outgoing.cache = Page()
        assertNull(model.stack.last().cache)
        assertEquals("remove", model.stack.last().focus)
    }
}
