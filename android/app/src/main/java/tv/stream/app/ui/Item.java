package tv.stream.app.ui;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import tv.stream.app.addon.Title;

/** One block of a page. Screens build these on workers; {@link PageAdapter} turns them into views. */
public final class Item {
    public enum Kind { SPOTLIGHT, DETAILS, INTRO, TITLE, HEADER, SHELF, GRID, ACTIONS, CHIPS, LIST, SOURCE, FIELD, KEYBOARD, SPLIT, SKELETON, MESSAGE, NOTICE, TEXT, LOADING, SUBTITLE_PREVIEW, CATALOGUE_HEADER, PAGINATION, EPISODES, SERIES_HEADER }

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
        public int episodeNumber = -1;
        public String synopsis = "";
        public String releaseDate = "";
        @DrawableRes public int badge;
        /** A short word over the artwork's corner, such as "New". */
        public String flag = "";
        /** What the Home spotlight leads its facts with while this tile is featured ("New season · S2 E1"). */
        public String headline = "";
        /** The accessible label when it should differ from "title. subtitle" (a catalogue card is named by its catalogue). */
        @Nullable public String description;

        public Tile(String key, Style style, String title, String subtitle, @Nullable String image, @Nullable Title media, Runnable action) {
            this.key = key; this.style = style; this.title = title; this.subtitle = subtitle;
            this.image = image; this.media = media; this.action = action;
        }
        public Tile progress(float value) { progress = value; return this; }
        public Tile badge(@DrawableRes int icon) { badge = icon; return this; }
        public Tile flag(String text) { flag = text; return this; }
        public Tile headline(String text) { headline = text; return this; }
        public Tile description(String value) { description = value; return this; }
        public Tile episode(int number, String overview) { episodeNumber = number; synopsis = overview; return this; }
        public Tile released(String date) { releaseDate = date; return this; }
        /** A plain block with its title inside and no artwork or caption (a Browse category). */
        public boolean block;
        public int colorIndex;
        public Tile block() { block = true; return this; }
    }

    /** A focusable button. Icon-only buttons are described by their label. */
    public static final class Action {
        public enum Style { PRIMARY, SECONDARY, ICON, CHIP }
        public final String key, label;
        public final Style style;
        public final Runnable action;
        @DrawableRes public int icon;
        public String detail = "";
        /** The visible label when it differs from the accessible one ("Favourite" for "Add favourite"). */
        @Nullable public String text;
        public boolean selected;

        public Action(String key, Style style, String label, Runnable action) {
            this.key = key; this.style = style; this.label = label; this.action = action;
        }
        public Action icon(@DrawableRes int value) { icon = value; return this; }
        public Action detail(String value) { detail = value; return this; }
        public Action text(String value) { text = value; return this; }
        public String shown() { return text != null ? text : label; }
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
    /** A source row's status icon: 0 for the default (a check, or info when muted), -1 for none. */
    public int statusIcon;
    /** Centres a row of actions, as on onboarding. */
    public boolean centered;
    public Tile.Style style = Tile.Style.POSTER;
    public List<Tile> tiles = Collections.emptyList();
    public List<Action> actions = Collections.emptyList();
    @Nullable public Consumer<String> keyboardInput;
    public List<String> keyboardLanguages = Collections.singletonList("en");
    public String keyboardLanguage = "en";
    public boolean keyboardVisible = true;
    @Nullable public Consumer<String> keyboardLanguageChanged;
    public boolean loading;

    private Item(Kind kind, String key) { this.kind = kind; this.key = key; }

    public static Item spotlight(Title first) { Item i = new Item(Kind.SPOTLIGHT, "spotlight"); i.media = first; return i; }
    public static Item details(Title title, String note) { Item i = new Item(Kind.DETAILS, "details"); i.media = title; i.detail = note; return i; }
    public static Item seriesHeader(Title title) { Item i = new Item(Kind.SERIES_HEADER, "series-header"); i.media = title; return i; }
    /** Seasons select on focus; each pane is its own episode shelf, with independent horizontal scroll. */
    public static Item episodes(List<Action> seasons, Map<String, List<Item>> panes, String selected, Consumer<String> select) {
        Item i = new Item(Kind.EPISODES, "episode-browser"); i.actions = seasons; i.panes = panes;
        i.value = selected; i.keyboardInput = select; return i;
    }
    public static Item intro(String overline, String title, String detail) {
        Item i = new Item(Kind.INTRO, "intro:" + title); i.meta = overline; i.title = title; i.detail = detail; return i;
    }
    /** A pushed page's large title, scrolling with its content. */
    public static Item pageTitle(String title) { Item i = new Item(Kind.TITLE, "title:" + title); i.title = title; return i; }
    public static Item header(String title) { Item i = new Item(Kind.HEADER, "header:" + title); i.title = title; return i; }
    public static Item header(String title, String detail) { Item i = header(title); i.detail = detail; return i; }
    public static Item catalogueHeader(String title, String detail) {
        Item i = new Item(Kind.CATALOGUE_HEADER, "catalogue-header"); i.title = title; i.detail = detail;
        return i;
    }
    public static Item pagination(String key, String status, List<Action> actions) {
        Item i = new Item(Kind.PAGINATION, key); i.title = status; i.actions = actions; return i;
    }
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
    public Item status(int icon) { statusIcon = icon; return this; }
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
    /**
     * Categories on the left, the focused one's rows on the right (Settings). {@code categories} are rows keyed like
     * {@code panes}; {@code selected} is the category shown first, and {@code select} hears each change.
     */
    public static Item split(String key, List<Item> categories, java.util.Map<String, List<Item>> panes, String selected, Consumer<String> select) {
        Item i = new Item(Kind.SPLIT, key); i.children = categories; i.panes = panes; i.value = selected; i.keyboardInput = select; return i;
    }
    @Nullable public List<Item> children;
    @Nullable public java.util.Map<String, List<Item>> panes;
    @Nullable public android.content.SharedPreferences playerPreferences;
    public static Item subtitlePreview(String key, android.content.SharedPreferences prefs) {
        Item i = new Item(Kind.SUBTITLE_PREVIEW, key);
        i.playerPreferences = prefs;
        return i;
    }
    public static Item keyboard(String key, String value, Consumer<String> input) {
        Item i = new Item(Kind.KEYBOARD, key); i.value = value; i.keyboardInput = input; return i;
    }
    public static Item skeleton(String screen) { Item i = new Item(Kind.SKELETON, "skeleton:" + screen); i.value = screen; return i; }
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
