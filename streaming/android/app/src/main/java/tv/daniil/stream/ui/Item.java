package tv.daniil.stream.ui;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import tv.daniil.stream.addon.Title;

/** One block of a page. Screens build these on workers; {@link PageAdapter} turns them into views. */
public final class Item {
    public enum Kind { SPOTLIGHT, DETAILS, INTRO, HEADER, SHELF, GRID, ACTIONS, CHIPS, LIST, SOURCE, FIELD, KEYBOARD, MESSAGE, NOTICE, TEXT, LOADING }

    /** A poster, landscape thumbnail or catalogue card. */
    public static final class Tile {
        public enum Style { POSTER, LANDSCAPE }
        public final String key, title, subtitle;
        public final Style style;
        public final Runnable action;
        @Nullable public final String image;
        /** Shown in the Home spotlight and backdrop while this tile has focus. */
        @Nullable public final Title media;
        /** 0–1 playback progress, or below zero for none. */
        public float progress = -1;
        @DrawableRes public int badge;

        public Tile(String key, Style style, String title, String subtitle, @Nullable String image, @Nullable Title media, Runnable action) {
            this.key = key; this.style = style; this.title = title; this.subtitle = subtitle;
            this.image = image; this.media = media; this.action = action;
        }
        public Tile progress(float value) { progress = value; return this; }
        public Tile badge(@DrawableRes int icon) { badge = icon; return this; }
    }

    /** A focusable button. Icon-only buttons are described by their label. */
    public static final class Action {
        public enum Style { PRIMARY, SECONDARY, ICON, TILE, CHIP }
        public final String key, label;
        public final Style style;
        public final Runnable action;
        @DrawableRes public int icon;
        public String detail = "";
        public boolean selected;

        public Action(String key, Style style, String label, Runnable action) {
            this.key = key; this.style = style; this.label = label; this.action = action;
        }
        public Action icon(@DrawableRes int value) { icon = value; return this; }
        public Action detail(String value) { detail = value; return this; }
        public Action selected(boolean value) { selected = value; return this; }
    }

    public final Kind kind;
    public final String key;
    public String title = "", detail = "", value = "", meta = "";
    @Nullable public Title media;
    @Nullable public Runnable action;
    @DrawableRes public int icon;
    /** Marks unusable sources and other rows that stay selectable but are not the expected choice. */
    public boolean muted;
    /** Centres a row of actions, as on onboarding. */
    public boolean centered;
    public Tile.Style style = Tile.Style.POSTER;
    public List<Tile> tiles = Collections.emptyList();
    public List<Action> actions = Collections.emptyList();
    @Nullable public Consumer<String> keyboardInput;
    @Nullable public Runnable keyboardSubmit;

    private Item(Kind kind, String key) { this.kind = kind; this.key = key; }

    public static Item spotlight(Title first) { Item i = new Item(Kind.SPOTLIGHT, "spotlight"); i.media = first; return i; }
    public static Item details(Title title, String note) { Item i = new Item(Kind.DETAILS, "details"); i.media = title; i.detail = note; return i; }
    public static Item intro(String overline, String title, String detail) {
        Item i = new Item(Kind.INTRO, "intro:" + title); i.meta = overline; i.title = title; i.detail = detail; return i;
    }
    public static Item header(String title) { Item i = new Item(Kind.HEADER, "header:" + title); i.title = title; return i; }
    public static Item header(String title, String detail) { Item i = header(title); i.detail = detail; return i; }
    public static Item shelf(String key, String title, String detail, Tile.Style style, List<Tile> tiles) {
        Item i = new Item(Kind.SHELF, key); i.title = title; i.detail = detail; i.style = style; i.tiles = tiles; return i;
    }
    /** Splits tiles into rows of {@code columns}; every row is a separate item so long grids recycle. */
    public static List<Item> grid(String key, Tile.Style style, int columns, List<Tile> tiles) {
        List<Item> rows = new ArrayList<>();
        for (int start = 0; start < tiles.size(); start += columns) {
            Item i = new Item(Kind.GRID, key + ":" + start); i.style = style; i.value = Integer.toString(columns);
            i.tiles = tiles.subList(start, Math.min(tiles.size(), start + columns)); rows.add(i);
        }
        return rows;
    }
    public static Item actions(String key, Action... actions) { Item i = new Item(Kind.ACTIONS, key); i.actions = list(actions); return i; }
    public static Item actions(String key, List<Action> actions) { Item i = new Item(Kind.ACTIONS, key); i.actions = actions; return i; }
    public Item centered() { centered = true; return this; }
    public static Item chips(String key, String title, List<Action> actions) { Item i = new Item(Kind.CHIPS, key); i.title = title; i.actions = actions; return i; }
    public static Item row(String key, @DrawableRes int icon, String title, String value, Runnable action) {
        Item i = new Item(Kind.LIST, key); i.icon = icon; i.title = title; i.value = value; i.action = action; return i;
    }
    public static Item source(String key, String title, String meta, String detail, String status, boolean usable, Runnable action) {
        Item i = new Item(Kind.SOURCE, key); i.title = title; i.meta = meta; i.detail = detail; i.value = status;
        i.muted = !usable; i.action = action; return i;
    }
    public static Item field(String key, String label, String value, Runnable action) {
        Item i = new Item(Kind.FIELD, key); i.title = label; i.value = value; i.action = action; return i;
    }
    public static Item keyboard(String key, String value, Consumer<String> input, Runnable submit) {
        Item i = new Item(Kind.KEYBOARD, key); i.value = value; i.keyboardInput = input; i.keyboardSubmit = submit; return i;
    }
    /** A centred empty, error or loading state with its next steps. */
    public static Item message(@DrawableRes int icon, String title, String detail, Action... actions) {
        Item i = new Item(Kind.MESSAGE, "message:" + title); i.icon = icon; i.title = title; i.detail = detail; i.actions = list(actions); return i;
    }
    /** An inline explanation inside a page that still has other content. */
    public static Item notice(@DrawableRes int icon, String title, String detail, Action... actions) {
        Item i = new Item(Kind.NOTICE, "notice:" + title); i.icon = icon; i.title = title; i.detail = detail; i.actions = list(actions); return i;
    }
    public static Item text(String title, String detail) { Item i = new Item(Kind.TEXT, "text:" + title); i.title = title; i.detail = detail; return i; }
    public static Item loading() { return new Item(Kind.LOADING, "loading"); }

    private static <T> List<T> list(T[] values) {
        List<T> out = new ArrayList<>(); for (T v : values) out.add(v); return out;
    }
}
