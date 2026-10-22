package tv.daniil.stream.ui;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import tv.daniil.stream.addon.Title;

/** What one route shows: its blocks plus the surrounding chrome (title, side panel, artwork, first focus). */
public final class Page {
    public final List<Item> items = new ArrayList<>();
    /** Shown above the content; null for tab roots and pages with their own header. */
    @Nullable public String title;
    /** Full-screen artwork behind the page. */
    @Nullable public Title backdrop;
    /** Blur the artwork, as tvOS does behind lists that belong to a title (its sources). */
    public boolean blurBackdrop;
    /** tvOS-style settings layout: a large icon and description on the left, rows on the right. */
    @DrawableRes public int panelIcon;
    public String panelTitle = "", panelText = "";
    /** The key that receives focus when no earlier focus is being restored. */
    @Nullable public String initialFocus;

    public Page add(Item item) { items.add(item); return this; }
    public Page addAll(Collection<Item> more) { items.addAll(more); return this; }
    public Page title(String value) { title = value; return this; }
    public Page panel(@DrawableRes int icon, String heading, String text) { panelIcon = icon; panelTitle = heading; panelText = text; return this; }
    public boolean hasPanel() { return panelIcon != 0; }
    /** Home starts on its first card so the spotlight and artwork describe something. */
    @Nullable public String firstTile() {
        for (Item item : items) if (!item.tiles.isEmpty()) return item.tiles.get(0).key;
        return null;
    }
}
