package tv.stream.app.ui;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import tv.stream.app.addon.Title;

/** What one route shows: its blocks plus the surrounding chrome (title, column width, artwork, first focus). */
public final class Page {
    public final List<Item> items = new ArrayList<>();
    /** Quiet fixed upper-right actions, outside the scrolling content. Refresh stays rightmost. */
    public List<Item.Action> tools = java.util.Collections.emptyList();
    /** Shown above the content; null for tab roots and pages with their own header. */
    @Nullable public String title;
    /** Full-screen artwork behind the page. */
    @Nullable public Title backdrop;
    /** Blur the artwork, as tvOS does behind lists that belong to a title (its sources). */
    public boolean blurBackdrop;
    /** Lists and forms sit in a centred column with open space either side; shelves and grids use the full width. */
    public boolean narrow;
    /** The key that receives focus when no earlier focus is being restored. */
    @Nullable public String initialFocus;
    /** Failed data remains visible on Back, but should get another bounded load when revisited. */
    public boolean reusable = true;

    public Page add(Item item) { items.add(item); return this; }
    public Page addAll(Collection<Item> more) { items.addAll(more); return this; }
    public Page title(String value) { title = value; return this; }
    public Page narrow() { narrow = true; return this; }
    /** A snapshot for progress delivery while a worker continues adding result blocks. */
    public Page copy() {
        Page copy = new Page(); copy.items.addAll(items); copy.title = title; copy.backdrop = backdrop;
        copy.tools = tools; copy.blurBackdrop = blurBackdrop; copy.narrow = narrow; copy.initialFocus = initialFocus;
        copy.reusable = reusable; return copy;
    }
    /** Home starts on its first card so the spotlight and artwork describe something. */
    @Nullable public String firstTile() {
        for (Item item : items) if (!item.tiles.isEmpty()) return item.tiles.get(0).key;
        return null;
    }
}
