package tv.stream.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import tv.stream.app.ui.Item
import tv.stream.app.ui.Page

class PageSnapshotTest {
    @Test fun partialResultsDetachNestedRenderValuesFromTheWorker() {
        val action = Item.Action("play", Item.Action.Style.PRIMARY, "Play", Runnable {})
        val tile = Item.Tile("film", Item.Tile.Style.POSTER, "Film", "", null, null, Runnable {}).progress(.2f)
        val paneRows = mutableListOf(Item.shelf("films", "Films", "", Item.Tile.Style.POSTER, mutableListOf(tile)))
        val panes = linkedMapOf("settings:addons" to paneRows.toList())
        val category = Item.row("settings:addons", 0, "Add-ons", "", null)
        val source = Page().add(Item.split("settings", listOf(category), panes, "settings:addons", null))
        source.tools = listOf(action)

        val delivered = source.copy()
        tile.progress = .9f
        paneRows[0].title = "Changed"
        category.title = "Changed"
        action.detail = "Changed"
        panes.clear()
        source.items.clear()

        val settings = delivered.items.single()
        assertEquals("Add-ons", settings.children!!.single().title)
        val shelf = settings.panes!!.getValue("settings:addons").single()
        assertEquals("Films", shelf.title)
        assertEquals(.2f, shelf.tiles.single().progress, 0f)
        assertNotSame(tile, shelf.tiles.single())
        assertEquals("", delivered.tools.single().detail)
    }
}
