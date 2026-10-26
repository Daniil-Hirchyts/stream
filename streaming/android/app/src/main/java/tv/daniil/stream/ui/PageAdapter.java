package tv.daniil.stream.ui;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import tv.daniil.stream.R;
import tv.daniil.stream.addon.Title;

/** Builds a {@link Page}'s blocks. Every focusable view is tagged with its key so focus can be restored. */
public final class PageAdapter extends RecyclerView.Adapter<PageAdapter.Holder> {
    public interface Listener {
        void focused(String key);
        /** The Home spotlight changed to this title (null when the page has no artwork). */
        void featured(@Nullable Title title);
        default void searchKeyboardChanged(boolean visible) {}
    }
    static final class Holder extends RecyclerView.ViewHolder { Holder(View view) { super(view); } }

    /** The Home spotlight: tall enough to clear the floating tab bar above its text. */
    public static final int SPOTLIGHT_HEIGHT = 248;
    private static final int POSTER_WIDTH = 120, LANDSCAPE_WIDTH = 216, GAP = 20, SEARCH_COLUMNS = 5;
    /** Where a list row's label starts inside its platter. */
    private static final int ROW_INSET = 16;
    private final Context context;
    private final Artwork artwork;
    private final Listener listener;
    private List<Item> items = Collections.emptyList();
    private List<List<Object>> snapshots = Collections.emptyList();
    private String restore = "";
    private int gutterStart, gutterEnd;
    private Spotlight spotlight;
    @Nullable private Item.Tile featured;
    @Nullable private RecyclerView recycler;
    private int generation;
    private int shownResults;
    private final java.util.Map<String, android.os.Parcelable> childScroll = new java.util.HashMap<>();
    private final java.util.Map<RecyclerView, String> scrollKeys = new java.util.WeakHashMap<>();
    public java.util.Map<String, android.os.Parcelable> scrollPositions() {
        for (java.util.Map.Entry<RecyclerView, String> entry : scrollKeys.entrySet())
            if (entry.getKey().isAttachedToWindow()) childScroll.put(entry.getValue(), entry.getKey().getLayoutManager().onSaveInstanceState());
        return new java.util.HashMap<>(childScroll);
    }
    public void restoreScrollPositions(java.util.Map<String, android.os.Parcelable> positions) {
        scrollKeys.clear(); childScroll.clear(); childScroll.putAll(positions);
    }
    private void retainScroll(RecyclerView list, String key) {
        String previous = scrollKeys.put(list, key);
        if (previous != null) childScroll.put(previous, list.getLayoutManager().onSaveInstanceState());
        android.os.Parcelable state = childScroll.get(key);
        if (state != null) list.getLayoutManager().onRestoreInstanceState(state);
        else if (previous != null) list.scrollToPosition(0);
        if (previous != null) return;
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) {
                String active = scrollKeys.get(view);
                if (active != null) childScroll.put(active, view.getLayoutManager().onSaveInstanceState());
            }
        });
    }
    /** The live Search results column, if Search is showing. */
    @Nullable private LinearLayout searchResults;
    @Nullable private StringBuilder keyboardQuery;

    public PageAdapter(Context context, Artwork artwork, Listener listener) {
        this.context = context; this.artwork = artwork; this.listener = listener;
        setHasStableIds(true); gutter(48, 48);
    }

    /** Page margins; a side panel already provides the left one. */
    public void gutter(int start, int end) { gutterStart = dp(start); gutterEnd = dp(end); }
    /** Shows {@code value}; true when only Search's results changed in place, so focus is already where it belongs. */
    public boolean set(List<Item> value, String restoreKey) {
        // A new search answer replaces only the results: rebinding would rebuild the keyboard and drop its focus.
        if (value.size() == 1 && items.size() == 1 && value.get(0).kind == Item.Kind.KEYBOARD && items.get(0).kind == Item.Kind.KEYBOARD
                && value.get(0).key.equals(items.get(0).key) && value.get(0).keyboardLanguages.equals(items.get(0).keyboardLanguages)
                && searchResults != null && searchResults.isAttachedToWindow()) {
            Item shown = items.get(0), next = value.get(0);
            boolean unchanged = snapshots.get(0).equals(snapshot(next));
            shown.tiles = next.tiles; shown.loading = next.loading; shown.detail = next.detail; shown.meta = next.meta; shown.actions = next.actions;
            shown.value = next.value;
            if (keyboardQuery != null && !keyboardQuery.toString().equals(next.value)) { keyboardQuery.setLength(0); keyboardQuery.append(next.value); }
            restore = restoreKey == null ? "" : restoreKey;
            if (!unchanged) results(searchResults, shown);
            snapshots = Collections.singletonList(snapshot(shown));
            // A results card may have been replaced. Let the page restore it after the nested grid lays out;
            // typing on the unchanged keyboard can keep its current focus immediately.
            View focused = searchResults.findFocus();
            if (unchanged && focused != null && java.util.Objects.equals(restoreKey, focused.getTag())) return true;
            return restoreKey == null || restoreKey.isEmpty() || restoreKey.startsWith(shown.key + ":");
        }
        if (value.size() != 1 || value.get(0).kind != Item.Kind.KEYBOARD) { searchPane = null; searchResults = null; keyboardQuery = null; }
        List<Item> previous = items;
        List<List<Object>> before = snapshots, after = new ArrayList<>();
        for (Item item : value) after.add(snapshot(item));
        androidx.recyclerview.widget.DiffUtil.DiffResult diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(new androidx.recyclerview.widget.DiffUtil.Callback() {
            @Override public int getOldListSize() { return previous.size(); }
            @Override public int getNewListSize() { return value.size(); }
            @Override public boolean areItemsTheSame(int old, int next) {
                return previous.get(old).kind == value.get(next).kind && previous.get(old).key.equals(value.get(next).key);
            }
            @Override public boolean areContentsTheSame(int old, int next) { return before.get(old).equals(after.get(next)); }
        }, false);
        items = new ArrayList<>(value); snapshots = after;
        restore = restoreKey == null ? "" : restoreKey;
        diff.dispatchUpdatesTo(this);
        // Restoration is a one-off: a shelf scrolled back into view later must not take focus again.
        int mine = ++generation;
        if (recycler != null) recycler.postDelayed(() -> { if (mine == generation) restore = ""; }, 1500);
        return false;
    }
    /** Capture values now: preferences and page blocks can be mutated before the next render. */
    private static List<Object> snapshot(Item item) {
        List<Object> state = new ArrayList<>(java.util.Arrays.asList(item.kind, item.key, item.title, item.detail, item.value,
                item.meta, item.icon, item.statusIcon, item.muted, item.centered, item.style, item.loading,
                item.media == null ? null : item.media.cacheJson(), item.keyboardLanguages, item.keyboardLanguage));
        for (Item.Action a : item.actions) state.add(java.util.Arrays.asList(a.key, a.label, a.text, a.detail, a.icon, a.style, a.selected));
        for (Item.Tile t : item.tiles) state.add(java.util.Arrays.asList(t.key, t.title, t.subtitle, t.image, t.style, t.episodeNumber, t.synopsis, t.releaseDate,
                t.progress, t.badge, t.description, t.block, t.colorIndex, t.media == null ? null : t.media.cacheJson()));
        if (item.children != null) for (Item child : item.children) state.add(snapshot(child));
        if (item.panes != null) for (Map.Entry<String, List<Item>> pane : item.panes.entrySet()) {
            state.add(pane.getKey()); for (Item row : pane.getValue()) state.add(snapshot(row));
        }
        if (item.playerPreferences != null) state.add(new java.util.HashMap<>(item.playerPreferences.getAll()));
        return state;
    }
    @Override public void onAttachedToRecyclerView(RecyclerView view) { recycler = view; }
    @Override public void onDetachedFromRecyclerView(RecyclerView view) { recycler = null; }
    private void focused(String key) {
        if (key.equals(restore)) restore = "";
        listener.focused(key);
        SearchPane pane = searchPane;
        if (pane != null && pane.isAttachedToWindow()) {
            if (key.startsWith("keyboard:")) pane.lastKeyboard = key;
            else if (pane.item.tiles.stream().anyMatch(tile -> tile.key.equals(key))) {
                pane.lastResult = key;
                if (!pane.changing) pane.keyboard(false, true, false);
            }
        }
    }
    /** Index of the block containing {@code key}, or -1. */
    public int position(String key) {
        for (int i = 0; i < items.size(); i++) if (contains(items.get(i), key)) return i;
        return -1;
    }
    private static boolean contains(Item item, String key) {
        if (item.key.equals(key)) return true;
        if (item.kind == Item.Kind.KEYBOARD && key.startsWith(item.key + ":")) return true;
        for (Item.Tile t : item.tiles) if (t.key.equals(key)) return true;
        for (Item.Action a : item.actions) if (a.key.equals(key)) return true;
        if (item.panes != null) for (List<Item> pane : item.panes.values())
            for (Item child : pane) if (contains(child, key)) return true;
        return false;
    }
    @Override public int getItemCount() { return items.size(); }
    @Override public int getItemViewType(int position) { return items.get(position).kind.ordinal(); }
    @Override public long getItemId(int position) {
        long hash = 0xcbf29ce484222325L;
        for (char c : items.get(position).key.toCharArray()) hash = (hash ^ c) * 0x100000001b3L;
        return hash;
    }
    @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
        FrameLayout box = new FrameLayout(context);
        box.setClipChildren(false); box.setClipToPadding(false);
        box.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new Holder(box);
    }
    @Override public void onBindViewHolder(Holder holder, int position) {
        FrameLayout box = (FrameLayout) holder.itemView;
        box.removeAllViews();
        Item item = items.get(position);
        boolean fillViewport = item.kind == Item.Kind.KEYBOARD || item.kind == Item.Kind.SPLIT || item.kind == Item.Kind.LOADING;
        box.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                fillViewport ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT));
        View content = build(item);
        // A block with a fixed height (the Home spotlight) keeps it; everything else wraps its content.
        ViewGroup.LayoutParams own = content.getLayoutParams();
        int height = fillViewport ? ViewGroup.LayoutParams.MATCH_PARENT : own != null && own.height > 0 ? own.height : ViewGroup.LayoutParams.WRAP_CONTENT;
        box.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
    }

    private View build(Item item) {
        switch (item.kind) {
            case SPOTLIGHT: return spotlight(item);
            case DETAILS: return details(item);
            case SERIES_HEADER: return seriesHeader(item);
            case INTRO: return intro(item);
            case TITLE: return pageTitle(item);
            case CATALOGUE_HEADER: return catalogueHeader(item);
            case PAGINATION: return pagination(item);
            case HEADER: return listFollows(item) ? sectionHeader(item.title, item.detail) : header(item.title, item.detail, 20);
            case SHELF: return shelf(item);
            case GRID: return grid(item);
            case ACTIONS: return actions(item, item.centered ? Gravity.CENTER_HORIZONTAL : Gravity.START);
            case CHIPS: return chips(item);
            case EPISODES: return episodes(item);
            case LIST: return row(item);
            case SOURCE: return source(item);
            case FIELD: return field(item);
            case KEYBOARD: return keyboard(item);
            case SPLIT: return split(item);
            case SKELETON: return skeleton(item);
            case MESSAGE: return message(item);
            case NOTICE: return notice(item);
            case TEXT: return text(item);
            case SUBTITLE_PREVIEW: return subtitlePreview(item);
            case LOADING: return loading();
            default: return loading();
        }
    }

    /** Whether a heading introduces list rows (a grey section header) rather than artwork (a shelf header). */
    private boolean listFollows(Item header) {
        int index = items.indexOf(header);
        if (index < 0 || index + 1 >= items.size()) return false;
        Item.Kind next = items.get(index + 1).kind;
        return next == Item.Kind.LIST || next == Item.Kind.SOURCE || next == Item.Kind.TEXT || next == Item.Kind.NOTICE || next == Item.Kind.ACTIONS;
    }
    /** Text set among list rows starts where the rows' labels do; elsewhere it aligns with the page edge. */
    private boolean inList(Item text) {
        for (int i = items.indexOf(text) - 1; i >= 0; i--) {
            Item before = items.get(i);
            if (before.kind == Item.Kind.TEXT) continue;
            return before.kind == Item.Kind.LIST || before.kind == Item.Kind.SOURCE || (before.kind == Item.Kind.HEADER && listFollows(before));
        }
        return false;
    }
    private LinearLayout column() {
        LinearLayout column = new LinearLayout(context); column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false); column.setClipToPadding(false);
        column.setPadding(gutterStart, 0, gutterEnd, 0);
        return column;
    }
    private static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private static FrameLayout.LayoutParams fill() { return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private int dp(float value) { return Tv.dp(context, value); }
    private void gone(View view, boolean gone) { view.setVisibility(gone ? View.GONE : View.VISIBLE); }

    // Spotlight: the focused Home card's title, artwork and description. Clicking opens that card.
    private final class Spotlight {
        final LinearLayout root = column();
        final ImageView logo = new ImageView(context);
        final TextView title = Tv.onArtwork(Tv.oneLine(Tv.bold(context, "", 34)));
        final TextView meta = Tv.onArtwork(Tv.oneLine(Tv.text(context, "", 14, R.color.tv_text_on_artwork)));
        final TextView description = Tv.onArtwork(Tv.lines(Tv.text(context, "", 15, R.color.tv_text), 2));
        String key = "";
        Runnable open;
        Title shown;

        Spotlight() {
            root.setGravity(Gravity.BOTTOM); root.setPadding(gutterStart, dp(4), gutterEnd, dp(2));
            // A touch shortcut to the focused card. Clickable views take focus by default; this one must not, or Up
            // from the first shelf lands on it invisibly.
            root.setTag("spotlight"); root.setClickable(true); root.setFocusable(false);
            root.setOnClickListener(v -> { if (open != null) { focused(key); open.run(); } });
            logo.setAdjustViewBounds(true); logo.setScaleType(ImageView.ScaleType.FIT_START); logo.setMaxHeight(dp(72)); logo.setMaxWidth(dp(360));
            logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            // A fixed height keeps the shelves still while the spotlight follows focus; the top clears the tab bar.
            root.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(SPOTLIGHT_HEIGHT)));
            // Wrapped widths: a MATCH_PARENT text view would ignore its maximum width.
            title.setMaxWidth(dp(600)); meta.setMaxWidth(dp(600)); description.setMaxWidth(dp(520));
            root.addView(logo, wrap()); root.addView(title, wrap());
            root.addView(meta, wrap()); root.addView(description, wrap());
            ((LinearLayout.LayoutParams) title.getLayoutParams()).topMargin = dp(4);
            ((LinearLayout.LayoutParams) logo.getLayoutParams()).topMargin = dp(6);
            meta.setPadding(0, dp(10), 0, 0); description.setPadding(0, dp(8), 0, 0);
        }
        void show(@Nullable Title media, String tileKey, Runnable action) {
            key = tileKey; open = action;
            if (media == null || media == shown) return;
            shown = media;
            root.setContentDescription(media.name);
            title.setText(media.name); gone(title, false);
            String facts = metaLine(media, false), type = kind(media);
            meta.setText(type.isEmpty() ? facts : facts.isEmpty() ? type : type + "  ·  " + facts); gone(meta, meta.getText().length() == 0);
            description.setText(media.description); gone(description, media.description.isEmpty());
            logo.setImageDrawable(null); gone(logo, true);
            if (media.logo != null) {
                Title wanted = media;
                artwork.fetch(media.logo, Artwork.LOGO, true, bitmap -> {
                    if (shown != wanted) return;
                    logo.setImageBitmap(bitmap); gone(logo, false); gone(title, true);
                });
            }
            listener.featured(media);
        }
    }
    private View spotlight(Item item) {
        spotlight = new Spotlight();
        Item.Tile first = featured;
        if (first == null) for (Item i : items) if (!i.tiles.isEmpty()) { first = i.tiles.get(0); break; }
        if (first != null) spotlight.show(first.media, first.key, first.action);
        else if (item.media != null) spotlight.show(item.media, "", null);
        return spotlight.root;
    }
    private void feature(Item.Tile tile) {
        if (spotlight == null || tile.media == null) return;
        featured = tile; spotlight.show(tile.media, tile.key, tile.action);
    }

    static String kind(Title title) {
        switch (title.type) {
            case "movie": return "Film";
            case "series": return "Series";
            case "channel": return "Channel";
            case "tv": return "Live TV";
            default: return title.type.isEmpty() ? "" : Character.toUpperCase(title.type.charAt(0)) + title.type.substring(1);
        }
    }
    public static String metaLine(Title title) {
        return metaLine(title, true);
    }
    private static String metaLine(Title title, boolean year) {
        List<String> parts = new ArrayList<>();
        if (year && !title.releaseInfo.isEmpty()) parts.add(title.releaseInfo);
        if (!title.genres.isEmpty()) parts.add(String.join(", ", title.genres.subList(0, Math.min(3, title.genres.size()))));
        if (!title.rating.isEmpty()) parts.add("★ " + title.rating);
        return String.join("  ·  ", parts);
    }

    // Details header: logo or title, facts and description over the title's backdrop.
    private View details(Item item) {
        Title media = item.media;
        LinearLayout root = column(); root.setGravity(Gravity.BOTTOM); root.setMinimumHeight(dp(220));
        root.setPadding(gutterStart, dp(4), gutterEnd, dp(14));
        TextView overline = Tv.onArtwork(Tv.oneLine(Tv.heading(context, kind(media), 12.5f, R.color.tv_text_on_artwork)));
        overline.setLetterSpacing(0.14f); overline.setAllCaps(true); root.addView(overline);
        ImageView logo = new ImageView(context);
        logo.setAdjustViewBounds(true); logo.setScaleType(ImageView.ScaleType.FIT_START); logo.setMaxHeight(dp(92)); logo.setMaxWidth(dp(400));
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); logo.setVisibility(View.GONE);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logoParams.topMargin = dp(10);
        root.addView(logo, logoParams);
        TextView title = Tv.onArtwork(Tv.lines(Tv.bold(context, media.name, 36), 2)); title.setMaxWidth(dp(640)); title.setPadding(0, dp(8), 0, 0);
        root.addView(title, wrap());
        if (media.logo != null) artwork.fetch(media.logo, Artwork.LOGO, true, bitmap -> { logo.setImageBitmap(bitmap); gone(logo, false); gone(title, true); });
        String facts = metaLine(media);
        if (!media.directors.isEmpty()) facts += (facts.isEmpty() ? "" : "  ·  ") + "Directed by " + String.join(", ", media.directors.subList(0, Math.min(2, media.directors.size())));
        if (!facts.isEmpty()) {
            TextView meta = Tv.onArtwork(Tv.oneLine(Tv.text(context, facts, 14, R.color.tv_text_on_artwork))); meta.setMaxWidth(dp(640)); meta.setPadding(0, dp(12), 0, 0);
            root.addView(meta, wrap());
        }
        if (!media.description.isEmpty()) {
            TextView about = Tv.onArtwork(Tv.lines(Tv.text(context, media.description, 15, R.color.tv_text), 3)); about.setMaxWidth(dp(560)); about.setPadding(0, dp(8), 0, 0);
            root.addView(about, wrap());
        }
        if (!item.detail.isEmpty()) {
            TextView note = Tv.onArtwork(Tv.oneLine(Tv.text(context, item.detail, 13.5f, R.color.tv_warning))); note.setPadding(0, dp(8), 0, 0); root.addView(note);
        }
        root.setLayoutParams(fill());
        return root;
    }

    private View intro(Item item) {
        LinearLayout root = column(); root.setGravity(Gravity.CENTER_HORIZONTAL); root.setPadding(gutterStart, dp(28), gutterEnd, dp(18));
        if (!item.meta.isEmpty()) {
            TextView overline = Tv.heading(context, item.meta, 13, R.color.tv_text_secondary); overline.setLetterSpacing(0.16f); overline.setAllCaps(true);
            overline.setGravity(Gravity.CENTER); root.addView(overline);
        }
        TextView title = Tv.bold(context, item.title, 38); title.setGravity(Gravity.CENTER); title.setPadding(0, dp(10), 0, 0); root.addView(title);
        TextView detail = Tv.text(context, item.detail, 16, R.color.tv_text_secondary); detail.setGravity(Gravity.CENTER);
        detail.setLineSpacing(0, 1.22f); detail.setMaxWidth(dp(620)); detail.setPadding(0, dp(12), 0, 0); root.addView(detail, wrap());
        root.setLayoutParams(fill());
        return root;
    }

    private View pageTitle(Item item) {
        TextView title = Tv.onArtwork(Tv.oneLine(Tv.bold(context, item.title, 30))); title.setPadding(gutterStart, dp(6), gutterEnd, dp(4));
        title.setTag(item.key);
        title.setLayoutParams(fill());
        return title;
    }
    private View seriesHeader(Item item) {
        FrameLayout root = new FrameLayout(context); root.setTag(item.key);
        root.setPadding(gutterStart, dp(12), gutterEnd, dp(16));
        root.setContentDescription(item.media.name);
        TextView fallback = Tv.onArtwork(Tv.oneLine(Tv.bold(context, item.media.name, 30)));
        root.addView(fallback, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL));
        ImageView logo = new ImageView(context); logo.setTag("series-logo");
        logo.setAdjustViewBounds(true); logo.setMaxWidth(dp(360)); logo.setMaxHeight(dp(52));
        logo.setScaleType(ImageView.ScaleType.FIT_START); logo.setVisibility(View.GONE);
        logo.setContentDescription(item.media.name);
        root.addView(logo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL));
        if (item.media.logo != null) artwork.fetch(item.media.logo, Artwork.LOGO, true, bitmap -> {
            logo.setImageBitmap(bitmap); logo.setVisibility(View.VISIBLE); fallback.setVisibility(View.GONE);
            logo.setAlpha(0f); logo.animate().alpha(1f).setDuration(160).start();
        });
        root.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)));
        return root;
    }
    /** A shelf or grid heading: white, above artwork. */
    private View header(String title, String detail, int topDp) { return header(title, detail, topDp, 17, R.color.tv_text); }
    /** A list section heading, as in tvOS Settings: smaller and grey, so the rows below lead. */
    private View sectionHeader(String title, String detail) { return header(title, detail, 22, 14, R.color.tv_text_secondary); }
    private View header(String title, String detail, int topDp, float sp, int color) {
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.BOTTOM);
        // List headings line up with the labels inside the rows below them, as in tvOS Settings.
        row.setPadding(gutterStart + (color == R.color.tv_text ? 0 : dp(ROW_INSET)), dp(topDp), gutterEnd, dp(2));
        // One line of text, so a heading such as "Generated series · Local fixtures" is never mistaken for a card's title.
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(title);
        if (detail != null && !detail.isEmpty()) {
            int start = text.length();
            text.append("   ").append(detail);
            int span = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
            text.setSpan(new android.text.style.AbsoluteSizeSpan(13, true), start, text.length(), span);
            text.setSpan(new android.text.style.ForegroundColorSpan(context.getColor(R.color.tv_text_tertiary)), start, text.length(), span);
            Typeface regular = Tv.Font.REGULAR.get(context);
            text.setSpan(new android.text.style.MetricAffectingSpan() {
                @Override public void updateMeasureState(android.text.TextPaint paint) { paint.setTypeface(regular); }
                @Override public void updateDrawState(android.text.TextPaint paint) { paint.setTypeface(regular); }
            }, start, text.length(), span);
        }
        TextView heading = Tv.onArtwork(Tv.oneLine(Tv.heading(context, "", sp, color))); heading.setText(text);
        heading.setContentDescription(detail == null || detail.isEmpty() ? title : title + ", " + detail);
        row.addView(heading);
        row.setLayoutParams(fill());
        return row;
    }

    // Shelves: a heading over a horizontally scrolling row of tiles.
    private View shelf(Item item) { return shelf(item, null); }
    private View shelf(Item item, @Nullable View season) {
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false); root.setClipToPadding(false);
        if (!item.title.isEmpty() || !item.detail.isEmpty()) root.addView(header(item.title, item.detail, 14));
        RecyclerView list = new RecyclerView(context);
        Layout layout = new Layout(context, RecyclerView.HORIZONTAL, 96); layout.navigationTarget(season);
        list.setLayoutManager(layout);
        list.setClipToPadding(false); list.setClipChildren(false); list.setItemAnimator(null);
        list.setHasFixedSize(true);
        list.setPadding(gutterStart, dp(season == null ? 12 : 16), gutterEnd, dp(14));
        list.setHorizontalScrollBarEnabled(false);
        TileAdapter adapter = new TileAdapter(item.tiles, item.style, item.style == Item.Tile.Style.POSTER ? POSTER_WIDTH : LANDSCAPE_WIDTH);
        list.setAdapter(adapter);
        retainScroll(list, item.key);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int inShelf = -1;
        for (int i = 0; i < item.tiles.size(); i++) if (item.tiles.get(i).key.equals(restore)) inShelf = i;
        if (inShelf >= 0) {
            if (!childScroll.containsKey(item.key)) list.scrollToPosition(inShelf);
            String key = restore;
            list.post(() -> { View target = list.findViewWithTag(key); if (target != null) target.requestFocus(); });
        }
        root.setLayoutParams(fill());
        return root;
    }

    /**
     * Equal-width tiles: weights rather than a measured width, which may still be the previous page's. Each column is
     * an equal slot that gives up the same share of the gaps, so a short last row lines up with the full rows above.
     */
    private View grid(Item item) {
        int columns = Integer.parseInt(item.value);
        List<View> tiles = new ArrayList<>();
        for (Item.Tile tile : item.tiles) tiles.add(tile(tile, item.style, 0));
        return gridRow(tiles, columns);
    }
    private View gridRow(List<View> tiles, int columns) {
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false); row.setClipToPadding(false); row.setWeightSum(columns);
        row.setPadding(gutterStart, dp(12), gutterEnd, dp(6));
        int gap = dp(GAP);
        for (int i = 0; i < tiles.size(); i++) {
            FrameLayout slot = new FrameLayout(context); slot.setClipChildren(false); slot.setClipToPadding(false);
            slot.setPadding(gap * i / columns, 0, gap - gap * (i + 1) / columns, 0);
            slot.addView(tiles.get(i), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(slot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        row.setLayoutParams(fill());
        return row;
    }

    private final class TileAdapter extends RecyclerView.Adapter<Holder> {
        final List<Item.Tile> tiles; final Item.Tile.Style style; final int widthDp, gapDp, verticalGapDp; int columns; final boolean matchSpan;
        TileAdapter(List<Item.Tile> tiles, Item.Tile.Style style, int widthDp) { this(tiles, style, widthDp, GAP, false); }
        TileAdapter(List<Item.Tile> tiles, Item.Tile.Style style, int widthDp, int gapDp) { this(tiles, style, widthDp, gapDp, false); }
        TileAdapter(List<Item.Tile> tiles, Item.Tile.Style style, int widthDp, int gapDp, boolean matchSpan) {
            this(tiles, style, widthDp, gapDp, matchSpan, 0, 0);
        }
        TileAdapter(List<Item.Tile> tiles, Item.Tile.Style style, int widthDp, int gapDp, boolean matchSpan, int columns, int verticalGapDp) {
            this.tiles = tiles; this.style = style; this.widthDp = widthDp; this.gapDp = gapDp; this.matchSpan = matchSpan;
            this.columns = columns; this.verticalGapDp = verticalGapDp;
        }
        @Override public int getItemCount() { return tiles.size(); }
        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            FrameLayout box = new FrameLayout(context); box.setClipChildren(false); box.setClipToPadding(false);
            box.setLayoutParams(new RecyclerView.LayoutParams(matchSpan ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(box);
        }
        @Override public void onBindViewHolder(Holder holder, int position) {
            FrameLayout box = (FrameLayout) holder.itemView; box.removeAllViews();
            View tile = tile(tiles.get(position), style, dp(widthDp));
            if (columns > 0) {
                // Each column gives up the same share of the gaps, so every tile in a grid has the same width.
                int column = position % columns, gap = dp(gapDp);
                box.setPadding(gap * column / columns, 0, gap - gap * (column + 1) / columns, dp(verticalGapDp));
            } else box.setPadding(0, 0, position == tiles.size() - 1 ? 0 : dp(gapDp), dp(verticalGapDp));
            box.addView(tile);
        }
    }

    // Skeletons: each page's loading state, built from the same blocks as the page, with the same sizes and spacing.
    // A bar stands where each line of text will be, inside an empty text view of that line's size, so the page
    // replaces it without anything moving.

    /** Search results while they load: the results grid's own columns and gaps. */
    private RecyclerView.Adapter<Holder> skeletonCards(int count, int columns) {
        return new RecyclerView.Adapter<Holder>() {
            @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
                FrameLayout box = new FrameLayout(context);
                box.setClipChildren(false); box.setClipToPadding(false);
                box.addView(skeletonTile(Item.Tile.Style.POSTER, 0), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                box.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new Holder(box);
            }
            @Override public void onBindViewHolder(Holder holder, int position) {
                int column = position % columns, gap = dp(14);
                holder.itemView.setPadding(gap * column / columns, 0, gap - gap * (column + 1) / columns, dp(18));
            }
            @Override public int getItemCount() { return count; }
        };
    }

    private View skeleton(Item item) {
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false); root.setClipToPadding(false);
        Item.Tile.Style poster = Item.Tile.Style.POSTER;
        switch (item.value) {
            case "Home":
                root.addView(skeletonSpotlight()); root.addView(skeletonShelf(poster)); root.addView(skeletonShelf(poster)); break;
            case "Home results":
                root.addView(skeletonShelf(poster)); root.addView(skeletonShelf(poster)); break;
            case "Add-ons":
                root.addView(skeletonTitle(180)); root.addView(skeletonActions(true, 46, 84, 106, 102, 88));
                root.addView(skeletonHeading(14, 22, ROW_INSET, 56));
                for (int i = 0; i < 5; i++) root.addView(skeletonSource(new int[]{132, 104, 150, 118, 140}[i], new int[]{200, 230, 210, 180, 220}[i], i == 0 ? 64 : 0));
                break;
            case "Add-on review":
                root.addView(skeletonTitle(200)); root.addView(skeletonText(false, 0, 600, 600, 340)); root.addView(skeletonText(false, 0, 230, 150));
                root.addView(skeletonActions(false, 148)); root.addView(skeletonText(false, 0, 330)); break;
            case "Catalogue results":
                root.addView(skeletonGrid(poster, 6)); root.addView(skeletonGrid(poster, 6)); break;
            case "Source results":
                for (int i = 0; i < 4; i++) root.addView(skeletonSource(170, 150, 0)); break;
            case "Store results":
                for (int i = 0; i < 4; i++) root.addView(skeletonSource(170, 150, 0)); break;
            case "Catalogue":
                root.addView(skeletonTitle(170)); root.addView(skeletonGrid(poster, 6)); root.addView(skeletonGrid(poster, 6)); break;
            default: return loading();
        }
        root.setLayoutParams(fill());
        return root;
    }

    /** A bar for each line of {@code text}: the view keeps its real size and spacing, its words hidden. */
    private View skeletonLines(TextView text, int... widthsDp) {
        FrameLayout box = new FrameLayout(context);
        StringBuilder blank = new StringBuilder(" ");
        for (int i = 1; i < widthsDp.length; i++) blank.append("\n ");
        text.setText(blank); text.setMaxLines(Math.max(1, widthsDp.length));
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Each bar is centred on its line's capital letters: half a cap height above the baseline. The first line's
        // baseline sits below the font's own top padding.
        android.graphics.Paint.FontMetricsInt metrics = text.getPaint().getFontMetricsInt();
        int bar = Math.max(dp(6), Math.round(text.getTextSize() * 0.62f)), line = text.getLineHeight();
        int baseline = text.getPaddingTop() - (text.getIncludeFontPadding() ? metrics.top : metrics.ascent);
        for (int i = 0; i < widthsDp.length; i++) {
            if (widthsDp[i] <= 0) continue;
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(widthsDp[i]), bar);
            p.leftMargin = text.getPaddingLeft();
            p.topMargin = baseline + i * line - Math.round(text.getTextSize() * 0.36f) - bar / 2;
            box.addView(skeletonBar(R.drawable.tv_skeleton), p);
        }
        return box;
    }

    private View skeletonBar(int background) {
        View view = new View(context); view.setBackgroundResource(background);
        return view;
    }

    private View skeletonTitle(int widthDp) {
        View title = skeletonLines(Tv.bold(context, "", 30), widthDp);
        title.setPadding(gutterStart, dp(6), gutterEnd, dp(4)); title.setLayoutParams(fill());
        return title;
    }

    /** A shelf or grid heading (17 sp, white) or a list section heading (14 sp, inset to the rows' labels). */
    private View skeletonHeading(float sp, int topDp, int insetDp, int widthDp) {
        View heading = skeletonLines(Tv.heading(context, "", sp, R.color.tv_text), widthDp);
        heading.setPadding(gutterStart + dp(insetDp), dp(topDp), gutterEnd, dp(2)); heading.setLayoutParams(fill());
        return heading;
    }

    private View skeletonText(boolean inList, int overlineDp, int... widthsDp) {
        LinearLayout root = column(); root.setPadding(gutterStart + (inList ? dp(ROW_INSET) : 0), dp(14), gutterEnd, dp(2));
        if (overlineDp > 0) root.addView(skeletonLines(Tv.heading(context, "", 12, R.color.tv_text_tertiary), overlineDp));
        TextView detail = Tv.text(context, "", 15, R.color.tv_text_secondary); detail.setLineSpacing(0, 1.22f); detail.setPadding(0, dp(5), 0, 0);
        root.addView(skeletonLines(detail, widthsDp));
        root.setLayoutParams(fill());
        return root;
    }

    /** A two-line row (a source or a listed add-on), with its status at the end. */
    private View skeletonSource(int titleDp, int metaDp, int statusDp) {
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ROW_INSET), dp(10), dp(14), dp(10));
        LinearLayout text = new LinearLayout(context); text.setOrientation(LinearLayout.VERTICAL);
        text.addView(skeletonLines(Tv.text(context, "", 16, R.color.tv_on_glass), titleDp));
        TextView meta = Tv.text(context, "", 13, R.color.tv_on_glass_secondary); meta.setPadding(0, dp(5), 0, 0);
        text.addView(skeletonLines(meta, metaDp));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (statusDp > 0) row.addView(skeletonLines(Tv.text(context, "", 13, R.color.tv_on_glass_secondary), statusDp));
        FrameLayout box = new FrameLayout(context); box.setPadding(gutterStart, dp(2), gutterEnd, dp(2));
        box.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.setLayoutParams(fill());
        return box;
    }

    /** Buttons (44 dp) or filter chips (36 dp capsules) at their usual widths; -1 is a round icon button. */
    private View skeletonActions(boolean chips, int... widthsDp) {
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(gutterStart, dp(10), gutterEnd, dp(10));
        int height = dp(chips ? 36 : 44);
        for (int i = 0; i < widthsDp.length; i++) {
            boolean round = widthsDp[i] < 0;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(round ? height : dp(widthsDp[i]), height);
            if (i > 0) p.leftMargin = dp(12);
            row.addView(skeletonBar(round ? R.drawable.tv_skeleton_round : chips ? R.drawable.tv_skeleton_capsule : R.drawable.tv_skeleton), p);
        }
        row.setLayoutParams(fill());
        return row;
    }

    /** Home's spotlight: its fixed height, with the title, facts and description at the bottom. */
    private View skeletonSpotlight() {
        LinearLayout root = column(); root.setGravity(Gravity.BOTTOM); root.setPadding(gutterStart, dp(4), gutterEnd, dp(2));
        TextView title = Tv.bold(context, "", 34); title.setPadding(0, dp(4), 0, 0);
        TextView meta = Tv.text(context, "", 14, R.color.tv_text_on_artwork); meta.setPadding(0, dp(10), 0, 0);
        TextView description = Tv.text(context, "", 15, R.color.tv_text); description.setPadding(0, dp(8), 0, 0);
        root.addView(skeletonLines(title, 300)); root.addView(skeletonLines(meta, 250)); root.addView(skeletonLines(description, 500, 380));
        root.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(SPOTLIGHT_HEIGHT)));
        return root;
    }

    private View skeletonTile(Item.Tile.Style style, int widthDp) {
        LinearLayout tile = new LinearLayout(context); tile.setOrientation(LinearLayout.VERTICAL);
        AspectFrame frame = new AspectFrame(context, style == Item.Tile.Style.POSTER ? 1.5f : 0.5625f);
        frame.setBackgroundResource(R.drawable.tv_skeleton);
        tile.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = Tv.heading(context, "", 13.5f, R.color.tv_caption); title.setPadding(dp(2), dp(9), dp(2), 0);
        TextView subtitle = Tv.text(context, "", 12, R.color.tv_text_secondary); subtitle.setPadding(dp(2), dp(3), dp(2), 0);
        boolean poster = style == Item.Tile.Style.POSTER;
        tile.addView(skeletonLines(title, poster ? 84 : 132));
        if (!poster) tile.addView(skeletonLines(subtitle, 90));
        tile.setLayoutParams(new LinearLayout.LayoutParams(widthDp > 0 ? dp(widthDp) : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tile;
    }

    /** A shelf: heading, then tiles at the shelf's width and gap, running off the edge as a real shelf does. */
    private View skeletonShelf(Item.Tile.Style style) {
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.addView(skeletonHeading(17, 14, 0, 150));
        LinearLayout tiles = new LinearLayout(context); tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.setPadding(gutterStart, dp(12), 0, dp(14));
        int width = style == Item.Tile.Style.POSTER ? POSTER_WIDTH : LANDSCAPE_WIDTH;
        for (int i = 0; i < (style == Item.Tile.Style.POSTER ? 8 : 5); i++) {
            View tile = skeletonTile(style, width);
            if (i > 0) ((LinearLayout.LayoutParams) tile.getLayoutParams()).leftMargin = dp(GAP);
            tiles.addView(tile);
        }
        HorizontalScrollView clip = new HorizontalScrollView(context); clip.setHorizontalScrollBarEnabled(false); clip.setFocusable(false);
        clip.addView(tiles);
        root.addView(clip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setLayoutParams(fill());
        return root;
    }

    /** One full grid row, as {@link #grid} lays it out. */
    private View skeletonGrid(Item.Tile.Style style, int columns) {
        List<View> tiles = new ArrayList<>();
        for (int i = 0; i < columns; i++) tiles.add(skeletonTile(style, 0));
        return gridRow(tiles, columns);
    }

    /** Keeps artwork at its aspect ratio for whatever width the tile receives. */
    private static final class AspectFrame extends FrameLayout {
        private final float ratio;
        AspectFrame(Context context, float ratio) { super(context); this.ratio = ratio; }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.round(width * ratio), MeasureSpec.EXACTLY));
        }
    }

    /**
     * A rounded image (or a titled placeholder) with a one-line title and subtitle that never clip.
     * {@code width} is in pixels; zero lets the parent assign it (grid rows).
     */
    private View tile(Item.Tile tile, Item.Tile.Style style, int width) {
        if (tile.block) return block(tile, width);
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false); root.setClipToPadding(false);
        root.setContentDescription(tile.description != null ? tile.description : tile.subtitle.isEmpty() ? tile.title : tile.title + ". " + tile.subtitle);
        FrameLayout frame = new AspectFrame(context, style == Item.Tile.Style.POSTER ? 1.5f : 0.5625f);
        root.setTag(R.id.popup_anchor, frame);
        frame.setBackgroundResource(R.drawable.tv_artwork); frame.setClipToOutline(true);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), context.getResources().getDimension(R.dimen.tv_radius_control));
            }
        });
        TextView placeholder = Tv.lines(Tv.bold(context, tile.episodeNumber >= 0 ? "" : tile.title, style == Item.Tile.Style.POSTER ? 15 : 17), 4);
        placeholder.setGravity(Gravity.CENTER); placeholder.setPadding(dp(10), 0, dp(10), 0);
        placeholder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(placeholder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        ImageView image = new ImageView(context); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        artwork.load(image, tile.image);
        if (tile.progress >= 0) {
            // The artwork darkens under the bar so it stays legible on light frames.
            View shade = new View(context); shade.setBackgroundResource(R.drawable.tv_tile_shade);
            frame.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36), Gravity.BOTTOM));
            LinearLayout track = new LinearLayout(context); track.setBackgroundResource(R.drawable.tv_progress_track);
            View fill = new View(context); fill.setBackgroundResource(R.drawable.tv_progress_fill);
            track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0.02f, Math.min(1f, tile.progress))));
            track.addView(new View(context), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0f, 1f - tile.progress)));
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.BOTTOM);
            p.setMargins(dp(10), 0, dp(10), dp(10)); frame.addView(track, p);
        }
        if (tile.badge != 0) {
            ImageView badge = Tv.icon(context, tile.badge, 26, R.color.tv_text);
            badge.setBackgroundResource(R.drawable.tv_badge); badge.setPadding(dp(5), dp(5), dp(5), dp(5));
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.END);
            p.setMargins(0, dp(7), dp(7), 0); frame.addView(badge, p);
        }
        int fill = ViewGroup.LayoutParams.MATCH_PARENT;
        root.addView(frame, new LinearLayout.LayoutParams(fill, ViewGroup.LayoutParams.WRAP_CONTENT));
        List<View> captions = new ArrayList<>();
        if (tile.episodeNumber >= 0) {
            TextView number = Tv.onArtwork(Tv.oneLine(Tv.text(context, "Episode " + tile.episodeNumber, 12, R.color.tv_text_secondary)));
            number.setTag("episode-number");
            LinearLayout episode = new LinearLayout(context); episode.setGravity(Gravity.CENTER_VERTICAL);
            episode.setClipChildren(false); episode.setClipToPadding(false); episode.setPadding(dp(2), dp(9), dp(2), 0);
            episode.addView(number, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            root.addView(episode, new LinearLayout.LayoutParams(fill, ViewGroup.LayoutParams.WRAP_CONTENT)); captions.add(episode);
        }
        TextView title = Tv.onArtwork(Tv.lines(Tv.heading(context, tile.title, tile.episodeNumber >= 0 ? 15 : 13.5f, R.color.tv_caption), tile.episodeNumber >= 0 ? 2 : 1));
        title.setTag("tile-title"); title.setDuplicateParentStateEnabled(true); title.setPadding(dp(2), dp(tile.episodeNumber >= 0 ? 3 : 9), dp(2), 0);
        root.addView(title, new LinearLayout.LayoutParams(fill, ViewGroup.LayoutParams.WRAP_CONTENT));
        captions.add(title);
        String detail = tile.episodeNumber >= 0 ? tile.synopsis : tile.subtitle;
        if (!detail.isEmpty()) {
            TextView subtitle = Tv.onArtwork(Tv.lines(Tv.text(context, detail, 12, R.color.tv_text_secondary), tile.episodeNumber >= 0 ? 3 : 1));
            subtitle.setTag(tile.episodeNumber >= 0 ? "episode-description" : "tile-detail"); subtitle.setPadding(dp(2), dp(4), dp(2), 0);
            root.addView(subtitle, new LinearLayout.LayoutParams(fill, ViewGroup.LayoutParams.WRAP_CONTENT)); captions.add(subtitle);
        }
        if (tile.episodeNumber >= 0 && !tile.releaseDate.isEmpty()) {
            TextView date = Tv.onArtwork(Tv.oneLine(Tv.text(context, tile.releaseDate, 11.5f, R.color.tv_text_tertiary)));
            date.setTag("episode-release-date"); date.setContentDescription("Release date, " + tile.releaseDate);
            date.setPadding(dp(2), dp(7), dp(2), 0);
            root.addView(date, new LinearLayout.LayoutParams(fill, ViewGroup.LayoutParams.WRAP_CONTENT)); captions.add(date);
        }
        root.setLayoutParams(new LinearLayout.LayoutParams(width > 0 ? width : fill, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Only the artwork grows, rimmed in white over a deep shadow; the caption keeps its size and moves down with
        // the artwork's lower edge, so nothing around the card shifts or rescales.
        frame.setDuplicateParentStateEnabled(true);
        frame.setForeground(context.getDrawable(R.drawable.tv_focus_rim));
        Tv.control(root, tile.key, tile.action, key -> { focused(key); feature(tile); }, 1f, false);
        View.OnFocusChangeListener focus = root.getOnFocusChangeListener();
        root.setOnFocusChangeListener((v, has) -> {
            focus.onFocusChange(v, has);
            float scale = has ? Tv.LIFT_TILE : 1f;
            frame.animate().cancel();
            frame.animate().scaleX(scale).scaleY(scale).translationZ(has ? dp(18) : 0).setDuration(has ? 200 : 160).start();
            float drop = frame.getHeight() * (scale - 1f) / 2f;
            for (View caption : captions) caption.animate().translationY(drop).setDuration(has ? 200 : 160).start();
        });
        if (tile.key.equals(restore)) root.post(root::requestFocus);
        return root;
    }

    /** A category block: a large centred name over a restrained colour wash, with room for focus growth. */
    private View block(Item.Tile tile, int width) {
        FrameLayout frame = new AspectFrame(context, BLOCK_RATIO);
        frame.setBackground(new CategoryBackground(tile.colorIndex)); frame.setClipToOutline(true);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), context.getResources().getDimension(R.dimen.tv_radius_control));
            }
        });
        frame.setContentDescription(tile.description != null ? tile.description : tile.title);
        LinearLayout text = new LinearLayout(context); text.setOrientation(LinearLayout.VERTICAL); text.setGravity(Gravity.CENTER);
        text.setPadding(dp(12), 0, dp(12), 0); text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView name = Tv.lines(Tv.bold(context, tile.title, 22), 2); name.setGravity(Gravity.CENTER); text.addView(name, wrap());
        if (!tile.subtitle.isEmpty()) {
            TextView detail = Tv.oneLine(Tv.text(context, tile.subtitle, 12, R.color.tv_text_secondary)); detail.setGravity(Gravity.CENTER);
            detail.setPadding(0, dp(4), 0, 0); text.addView(detail, wrap());
        }
        frame.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        frame.setForeground(context.getDrawable(R.drawable.tv_focus_rim));
        frame.setLayoutParams(new LinearLayout.LayoutParams(width > 0 ? width : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Tv.control(frame, tile.key, tile.action, this::focused, 1f, false);
        View.OnFocusChangeListener focus = frame.getOnFocusChangeListener();
        frame.setOnFocusChangeListener((v, has) -> {
            focus.onFocusChange(v, has);
            float scale = has ? Tv.LIFT_TILE : 1f;
            v.animate().cancel();
            v.animate().scaleX(scale).scaleY(scale).translationZ(has ? dp(18) : 0).setDuration(has ? 200 : 160).start();
        });
        if (tile.key.equals(restore)) frame.post(frame::requestFocus);
        return frame;
    }
    /** Height to width of a category block. */
    private static final float BLOCK_RATIO = 0.5f;
    // Buttons, chips and rows.
    private View catalogueHeader(Item item) {
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false); root.setClipToPadding(false);
        root.setPadding(gutterStart, dp(8), gutterEnd, dp(14));
        LinearLayout line = new LinearLayout(context); line.setGravity(Gravity.CENTER_VERTICAL);
        line.setClipChildren(false); line.setClipToPadding(false);
        LinearLayout titles = new LinearLayout(context); titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(0, 0, dp(20), 0);
        TextView title = Tv.lines(Tv.bold(context, item.title, 27), 2); titles.addView(title, fill());
        TextView kind = Tv.oneLine(Tv.text(context, item.detail, 13, R.color.tv_text_secondary));
        kind.setPadding(0, dp(4), 0, 0); titles.addView(kind, fill());
        line.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(line);
        root.setLayoutParams(fill()); return root;
    }

    /** One compact footer; no paging controls between filters and posters. */
    private View pagination(Item item) {
        LinearLayout root = new LinearLayout(context); root.setGravity(Gravity.CENTER);
        root.setClipChildren(false); root.setClipToPadding(false);
        root.setPadding(gutterStart, dp(18), gutterEnd, dp(20));
        LinearLayout capsule = new LinearLayout(context); capsule.setGravity(Gravity.CENTER_VERTICAL);
        capsule.setPadding(dp(4), dp(4), dp(4), dp(4));
        capsule.setBackgroundResource(R.drawable.tv_pagination);
        capsule.setClipChildren(false); capsule.setClipToPadding(false);
        Item.Action previous = null, next = null;
        for (Item.Action action : item.actions) {
            if (action.key.startsWith("previous-page:")) previous = action; else next = action;
        }
        for (int position = 0; position < 3; position++) {
            if (position == 1) {
                TextView status = Tv.heading(context, item.title, 14, R.color.tv_text_secondary);
                status.setGravity(Gravity.CENTER);
                capsule.addView(status, new LinearLayout.LayoutParams(dp(92), dp(40)));
            } else {
                capsule.addView(pageButton(position == 0 ? previous : next, position == 0), new LinearLayout.LayoutParams(dp(40), dp(40)));
            }
        }
        root.addView(capsule); restoreControls(root, item);
        root.setLayoutParams(fill()); return root;
    }

    /** A round page arrow: a chevron in a circle, dimmed and inert where there is no page to go to. */
    private View pageButton(@androidx.annotation.Nullable Item.Action action, boolean previous) {
        FrameLayout button = new FrameLayout(context);
        button.setBackgroundResource(R.drawable.tv_button_round);
        ImageView chevron = Tv.icon(context, R.drawable.ic_tv_chevron, 18, action == null ? R.color.tv_text_tertiary : R.color.tv_on_glass);
        chevron.setDuplicateParentStateEnabled(true);
        if (previous) chevron.setRotation(180);
        button.addView(chevron, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        if (action == null) { button.setAlpha(0.5f); button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return button; }
        button.setContentDescription(action.label); button.setTooltipText(action.label);
        Tv.control(button, action.key, action.action, this::focused, Tv.LIFT_ICON, false);
        return button;
    }

    private void restoreControls(ViewGroup root, Item item) {
        for (Item.Action action : item.actions) if (action.key.equals(restore)) {
            View control = root.findViewWithTag(action.key);
            if (control != null) control.post(control::requestFocus);
        }
    }

    private View actions(Item item, int gravity) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false); scroll.setClipChildren(false); scroll.setClipToPadding(false);
        scroll.setFillViewport(gravity == Gravity.CENTER_HORIZONTAL);
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(gravity | Gravity.CENTER_VERTICAL);
        row.setClipChildren(false); row.setClipToPadding(false);
        row.setPadding(gutterStart, dp(10), gutterEnd, dp(10));
        boolean icons = false;
        for (Item.Action action : item.actions) icons |= action.style == Item.Action.Style.ICON;
        // tvOS names an icon-only button beneath it while it has focus.
        FocusCaption caption = icons ? new FocusCaption(context) : null;
        for (int i = 0; i < item.actions.size(); i++) {
            Item.Action action = item.actions.get(i);
            View button = Tv.button(context, action, this::focused);
            if (i > 0) ((LinearLayout.LayoutParams) button.getLayoutParams()).leftMargin = dp(12);
            row.addView(button);
            if (caption != null) {
                View.OnFocusChangeListener focus = button.getOnFocusChangeListener();
                boolean iconOnly = action.style == Item.Action.Style.ICON;
                button.setOnFocusChangeListener((v, has) -> {
                    focus.onFocusChange(v, has);
                    if (has && iconOnly) {
                        caption.show(action.label, v, scroll);
                    } else caption.hide(action.label);
                });
            }
            if (action.key.equals(restore)) button.post(button::requestFocus);
        }
        scroll.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (caption == null) { scroll.setLayoutParams(fill()); return scroll; }
        LinearLayout column = new LinearLayout(context); column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false); column.setClipToPadding(false);
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(caption);
        column.setLayoutParams(fill());
        return column;
    }
    private View chips(Item item) {
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false); root.setClipToPadding(false);
        // Chips are buttons, not rows: their heading lines up with the page edge, like a shelf's.
        if (!item.title.isEmpty()) root.addView(header(item.title, "", 14));
        root.addView(actions(item, Gravity.START));
        root.setLayoutParams(fill());
        return root;
    }

    /** Focus changes just the episode shelf; the season strip stays attached and keeps its focus. */
    private View episodes(Item item) {
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false); root.setClipToPadding(false);
        HorizontalScrollView seasons = new HorizontalScrollView(context);
        seasons.setHorizontalScrollBarEnabled(false); seasons.setClipChildren(false); seasons.setClipToPadding(false);
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false); row.setClipToPadding(false); row.setPadding(gutterStart, dp(14), gutterEnd, dp(22));
        seasons.addView(row); root.addView(seasons);
        FrameLayout shelf = new FrameLayout(context); shelf.setClipChildren(false); shelf.setClipToPadding(false);
        root.addView(shelf, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Map<String, View> buttons = new java.util.LinkedHashMap<>();
        String[] selected = {""};
        java.util.function.Consumer<String> select = key -> {
            if (key.equals(selected[0])) return;
            int index = position(item.key);
            Item current = index >= 0 ? items.get(index) : item;
            List<Item> pane = current.panes.get(key);
            if (pane == null || pane.isEmpty()) return;
            selected[0] = key; current.value = key;
            for (Item.Action action : current.actions) {
                action.selected = action.key.equals(key);
                View button = buttons.get(action.key); if (button != null) button.setActivated(action.selected);
            }
            shelf.removeAllViews(); shelf.addView(shelf(pane.get(0), buttons.get(key)));
            // Keep the existing strip when the surrounding overview contracts into episode browsing.
            if (index >= 0) snapshots.set(index, snapshot(current));
            if (current.keyboardInput != null) current.keyboardInput.accept(key);
        };
        for (Item.Action action : item.actions) {
            View button = Tv.button(context, action, this::focused);
            if (row.getChildCount() > 0) ((LinearLayout.LayoutParams) button.getLayoutParams()).leftMargin = dp(12);
            View.OnFocusChangeListener focus = button.getOnFocusChangeListener();
            button.setOnFocusChangeListener((view, has) -> {
                if (has) select.accept(action.key);
                focus.onFocusChange(view, has);
            });
            button.setOnHoverListener((view, event) -> {
                if ((event.getActionMasked() == android.view.MotionEvent.ACTION_HOVER_ENTER
                        || event.getActionMasked() == android.view.MotionEvent.ACTION_HOVER_MOVE) && !view.hasFocus()) view.requestFocus();
                return false;
            });
            button.setOnClickListener(view -> {
                select.accept(action.key); focused(action.key); Sounds.click();
                View first = firstFocusable(shelf); if (first != null) first.requestFocus();
            });
            button.setOnKeyListener((view, code, event) -> {
                if (code != android.view.KeyEvent.KEYCODE_DPAD_DOWN) return false;
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    View first = firstFocusable(shelf); if (first != null) first.requestFocus();
                }
                return true;
            });
            buttons.put(action.key, button); row.addView(button);
            if (action.key.equals(restore)) button.post(button::requestFocus);
        }
        select.accept(item.value);
        root.setLayoutParams(fill()); return root;
    }

    private View subtitlePreview(Item item) {
        FrameLayout box = new FrameLayout(context);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setPadding(gutterStart, dp(4), gutterEnd, dp(14));

        SubtitlePreviewView preview = new SubtitlePreviewView(context);
        if (item.playerPreferences != null) {
            preview.updateFrom(item.playerPreferences);
        }
        preview.setTag(item.key);
        box.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(92)));
        box.setLayoutParams(fill());
        return box;
    }

    private View row(Item item) {
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(46));
        row.setPadding(dp(ROW_INSET), dp(8), dp(14), dp(8));
        row.setContentDescription(item.value.isEmpty() ? item.title : item.title + ". " + item.value);
        if (item.icon != 0) {
            ImageView icon = Tv.icon(context, item.icon, 21, R.color.tv_on_glass);
            ((LinearLayout.LayoutParams) icon.getLayoutParams()).rightMargin = dp(14); row.addView(icon);
        }
        TextView title = Tv.oneLine(Tv.text(context, item.title, 16, R.color.tv_on_glass)); title.setDuplicateParentStateEnabled(true);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!item.value.isEmpty()) {
            TextView value = Tv.oneLine(Tv.text(context, item.value, 15, R.color.tv_on_glass_secondary)); value.setDuplicateParentStateEnabled(true);
            value.setMaxWidth(dp(280)); value.setPadding(dp(12), 0, dp(4), 0); row.addView(value);
        }
        if (item.action != null) row.addView(Tv.icon(context, R.drawable.ic_tv_chevron, 18, R.color.tv_on_glass_secondary));
        return wrapRow(item, row, R.drawable.tv_row, Tv.LIFT_ROW);
    }
    private View source(Item item) {
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ROW_INSET), dp(10), dp(14), dp(10));
        row.setContentDescription(item.title + ". " + item.meta + ". " + item.value);
        LinearLayout text = new LinearLayout(context); text.setOrientation(LinearLayout.VERTICAL); text.setDuplicateParentStateEnabled(true);
        TextView title = Tv.oneLine(Tv.text(context, item.title, 16, R.color.tv_on_glass)); title.setDuplicateParentStateEnabled(true); text.addView(title);
        TextView meta = Tv.oneLine(Tv.text(context, item.meta, 13, R.color.tv_on_glass_secondary)); meta.setDuplicateParentStateEnabled(true);
        meta.setPadding(0, dp(5), 0, 0); text.addView(meta);
        if (!item.detail.isEmpty()) {
            TextView detail = Tv.oneLine(Tv.text(context, item.detail, 13, R.color.tv_on_glass_secondary)); detail.setDuplicateParentStateEnabled(true);
            detail.setPadding(0, dp(3), 0, 0); text.addView(detail);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout status = new LinearLayout(context); status.setGravity(Gravity.CENTER_VERTICAL); status.setDuplicateParentStateEnabled(true);
        int statusIcon = item.statusIcon != 0 ? item.statusIcon : item.muted ? R.drawable.ic_tv_info : R.drawable.ic_tv_check;
        if (statusIcon > 0) status.addView(Tv.icon(context, statusIcon, 18, item.muted ? R.color.tv_on_glass_secondary : R.color.tv_on_glass));
        TextView value = Tv.lines(Tv.text(context, item.value, 13, R.color.tv_on_glass_secondary), 2); value.setDuplicateParentStateEnabled(true);
        value.setMaxWidth(dp(200)); value.setPadding(statusIcon > 0 ? dp(7) : 0, 0, 0, 0); status.addView(value);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.leftMargin = dp(16);
        row.addView(status, statusParams);
        row.setAlpha(item.muted ? 0.55f : 1f);
        return wrapRow(item, row, R.drawable.tv_row, Tv.LIFT_ROW);
    }
    private View field(Item item) {
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48)); row.setPadding(dp(18), 0, dp(18), 0);
        row.setContentDescription(item.title + ". " + item.value);
        ImageView icon = Tv.icon(context, R.drawable.ic_tv_search, 22, R.color.tv_on_glass);
        ((LinearLayout.LayoutParams) icon.getLayoutParams()).rightMargin = dp(14); row.addView(icon);
        TextView value = Tv.oneLine(Tv.text(context, item.value, 17, R.color.tv_on_glass_secondary)); value.setDuplicateParentStateEnabled(true);
        row.addView(value);
        return wrapRow(item, row, R.drawable.tv_field, Tv.LIFT_ROW);
    }
    /** A focusable platter around a row: transparent until focused, then white with dark text (no live blur). */
    private View wrapRow(Item item, LinearLayout row, int background, float lift) {
        FrameLayout box = new FrameLayout(context); box.setClipChildren(false); box.setClipToPadding(false);
        box.setPadding(gutterStart, dp(2), gutterEnd, dp(2));
        FrameLayout platter = new FrameLayout(context);
        platter.setBackgroundResource(background);
        platter.setContentDescription(row.getContentDescription());
        row.setContentDescription(null);
        row.setDuplicateParentStateEnabled(true);
        platter.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(platter, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (item.action != null) {
            Tv.control(platter, item.key, item.action, this::focused, lift);
            if (item.key.equals(restore)) platter.post(platter::requestFocus);
        } else platter.setTag(item.key);
        box.setLayoutParams(fill());
        return box;
    }

    // States.
    private View message(Item item) {
        boolean quiet = item.icon == 0 && item.actions.isEmpty();
        LinearLayout root = column(); root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(gutterStart, dp(quiet ? 100 : 52), gutterEnd, dp(12));
        if (item.icon != 0) {
            FrameLayout disc = new FrameLayout(context); disc.setBackgroundResource(R.drawable.tv_icon_disc);
            ImageView icon = Tv.icon(context, item.icon, 30, R.color.tv_text);
            disc.addView(icon, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
            root.addView(disc, new LinearLayout.LayoutParams(dp(64), dp(64)));
        }
        TextView title = quiet ? Tv.text(context, item.title, 20, R.color.tv_text_secondary)
                : Tv.heading(context, item.title, 22, R.color.tv_text);
        title.setGravity(Gravity.CENTER); title.setPadding(0, quiet ? 0 : dp(18), 0, 0);
        root.addView(title, wrap());
        if (!item.detail.isEmpty()) {
            TextView detail = Tv.text(context, item.detail, quiet ? 14 : 15, quiet ? R.color.tv_text_tertiary : R.color.tv_text_secondary); detail.setGravity(Gravity.CENTER);
            detail.setLineSpacing(0, 1.22f); detail.setMaxWidth(dp(quiet ? 400 : 520)); detail.setPadding(0, dp(8), 0, dp(8)); root.addView(detail, wrap());
        }
        if (!item.actions.isEmpty()) root.addView(actions(item, Gravity.CENTER_HORIZONTAL));
        root.setLayoutParams(fill());
        return root;
    }
    private View notice(Item item) {
        LinearLayout box = new LinearLayout(context); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL);
        box.setClipChildren(false); box.setClipToPadding(false);
        box.setPadding(dp(18), dp(12), dp(8), dp(12));
        box.addView(Tv.icon(context, item.icon == 0 ? R.drawable.ic_tv_info : item.icon, 22, R.color.tv_text));
        LinearLayout text = new LinearLayout(context); text.setOrientation(LinearLayout.VERTICAL); text.setPadding(dp(14), 0, dp(12), 0);
        text.addView(Tv.heading(context, item.title, 15.5f, R.color.tv_text));
        if (!item.detail.isEmpty()) { TextView detail = Tv.lines(Tv.text(context, item.detail, 13.5f, R.color.tv_text_secondary), 3); detail.setPadding(0, dp(4), 0, 0); text.addView(detail); }
        box.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!item.actions.isEmpty()) {
            Item inline = Item.actions(item.key + ":actions", item.actions);
            View buttons = actions(inline, Gravity.END);
            buttons.setPadding(0, 0, 0, 0);
            ((ViewGroup) ((ViewGroup) buttons).getChildAt(0)).setPadding(0, dp(4), dp(8), dp(4));
            box.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        // The notice is glass; its buttons lift outside it, so they sit on the same blur rather than inside its clip.
        FrameLayout glass = GlassView.surface(context); glass.setBackgroundResource(R.drawable.tv_notice);
        FrameLayout wrap = new FrameLayout(context); wrap.setClipChildren(false); wrap.setClipToPadding(false);
        wrap.setPadding(gutterStart, dp(14), gutterEnd, dp(6));
        wrap.addView(glass, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.setLayoutParams(fill());
        return wrap;
    }
    private View text(Item item) {
        LinearLayout root = column(); root.setPadding(gutterStart + (inList(item) ? dp(ROW_INSET) : 0), dp(14), gutterEnd, dp(2));
        if (!item.title.isEmpty()) {
            TextView title = Tv.onArtwork(Tv.heading(context, item.title, 12, R.color.tv_text_tertiary)); title.setLetterSpacing(0.1f); title.setAllCaps(true);
            root.addView(title);
        }
        TextView detail = Tv.onArtwork(Tv.text(context, item.detail, 15, R.color.tv_text_secondary)); detail.setLineSpacing(0, 1.22f); detail.setPadding(0, dp(5), 0, 0);
        detail.setTextIsSelectable(false); root.addView(detail);
        root.setLayoutParams(fill());
        return root;
    }
    @Nullable private SearchPane searchPane;
    /** Left from the grid's leading column brings the editing column back. */
    public boolean searchKey(int code) {
        return searchPane != null && searchPane.isAttachedToWindow() && searchPane.key(code);
    }
    public void toggleSearchKeyboard() {
        if (searchPane != null && searchPane.isAttachedToWindow()) searchPane.keyboard(!searchPane.visible, true, true);
    }
    private View keyboard(Item item) {
        SearchPane root = new SearchPane(item); searchPane = root;
        root.setOrientation(LinearLayout.HORIZONTAL); root.setGravity(Gravity.CENTER_VERTICAL);
        root.setClipChildren(false); root.setClipToPadding(false); root.setTag("search-layout");
        root.setPadding(gutterStart, dp(8), gutterEnd, dp(12));
        LinearLayout editor = new LinearLayout(context); editor.setGravity(Gravity.CENTER_VERTICAL);
        editor.setClipChildren(false); editor.setClipToPadding(false); editor.setTag("search-keyboard");
        LinearLayout keyboardPanel = new LinearLayout(context); keyboardPanel.setGravity(Gravity.CENTER);
        keyboardPanel.setClipChildren(false); keyboardPanel.setClipToPadding(false);
        editor.addView(keyboardPanel, new LinearLayout.LayoutParams(keyboardWidth() + dp(12), ViewGroup.LayoutParams.MATCH_PARENT));
        keyboardPanel.addView(keyboardKeys(item), new LinearLayout.LayoutParams(keyboardWidth(), ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(context); divider.setBackgroundColor(context.getColor(R.color.tv_hairline));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(1), dp(300));
        dividerParams.setMargins(dp(10), 0, dp(16), 0); editor.addView(divider, dividerParams);
        root.addView(editor, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.editor = editor; editor.setVisibility(root.visible ? View.VISIBLE : View.GONE);

        LinearLayout results = new LinearLayout(context); results.setOrientation(LinearLayout.VERTICAL);
        results.setClipChildren(false); results.setClipToPadding(false);
        results.setPadding(0, dp(8), 0, 0);
        // Keep the results viewport inside the visible page, including its bottom safe area.
        LinearLayout.LayoutParams resultsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        resultsParams.gravity = Gravity.TOP;
        root.addView(results, resultsParams);
        root.results = results; results.setTag("search-results"); results.setFocusableInTouchMode(true);
        searchResults = results;
        results(results, item);
        root.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listener.searchKeyboardChanged(root.visible);
        return root;
    }
    /** The layout changes in place: one query, one results grid and no provider work during a mode switch. */
    private final class SearchPane extends LinearLayout {
        final Item item;
        LinearLayout editor, results;
        RecyclerView grid;
        boolean visible, changing;
        int transition;
        String lastKeyboard, lastResult = "";
        SearchPane(Item item) {
            super(context); this.item = item; visible = item.keyboardVisible;
            lastKeyboard = item.key + ":" + KeyboardLanguages.alphabet(item.keyboardLanguage).charAt(0);
        }
        boolean key(int code) {
            if (grid == null) {
                if (visible && code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && editor.hasFocus()) {
                    View current = findFocus();
                    if (current != null && current.getParent() instanceof ViewGroup) {
                        ViewGroup row = (ViewGroup) current.getParent();
                        View retry = results.findViewWithTag("reload-search");
                        if (row.getChildAt(row.getChildCount() - 1) == current && retry != null) { retry.requestFocus(); return true; }
                    }
                }
                if (visible && results.hasFocus() && code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) { focusKeyboard(); return true; }
                if (!visible && hasFocus() && code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) { keyboard(true, true, true); return true; }
                return false;
            }
            View current = findFocus();
            if (visible && code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && current != null && current.getParent() instanceof ViewGroup) {
                ViewGroup row = (ViewGroup) current.getParent();
                if (current.getTag() instanceof String && String.valueOf(current.getTag()).startsWith("keyboard:")
                        && row.getChildAt(row.getChildCount() - 1) == current) {
                    View result = lastResult.isEmpty() ? null : results.findViewWithTag(lastResult);
                    if (result == null) result = firstFocusable(grid);
                    if (result != null && result != results) { result.requestFocus(); return true; }
                }
            }
            if (code != android.view.KeyEvent.KEYCODE_DPAD_LEFT || visible) return false;
            View focused = findFocus(), cell = focused == null ? null : grid.findContainingItemView(focused);
            if (cell == null) return false;
            int index = grid.getChildAdapterPosition(cell), columns = ((GridLayoutManager) grid.getLayoutManager()).getSpanCount();
            if (index < 0 || index % columns != 0) return false;
            keyboard(true, true, true); return true;
        }
        void keyboard(boolean show, boolean animate, boolean focusAfter) {
            if (visible == show) { if (show && focusAfter) focusKeyboard(); return; }
            visible = show; item.keyboardVisible = show;
            listener.searchKeyboardChanged(show);
            int mine = ++transition; changing = true;
            // Focus notifications can arrive during RecyclerView layout. Mutate its span count on the next frame.
            post(() -> {
                if (mine != transition || searchPane != this || !isAttachedToWindow()) return;
                android.transition.TransitionManager.endTransitions(this);
                View focused = findFocus();
                String target = focused != null && focused.getTag() instanceof String && !String.valueOf(focused.getTag()).startsWith("keyboard:")
                        ? (String) focused.getTag() : lastResult;
                GridLayoutManager layout = grid == null ? null : (GridLayoutManager) grid.getLayoutManager();
                View cell = focused == null || grid == null ? null : grid.findContainingItemView(focused);
                if (cell == null && grid != null && !target.isEmpty()) { View old = grid.findViewWithTag(target); if (old != null) cell = grid.findContainingItemView(old); }
                int anchor = cell == null ? layout == null ? 0 : Math.max(0, layout.findFirstVisibleItemPosition()) : grid.getChildAdapterPosition(cell);
                int offset = cell == null ? 0 : cell.getTop() - grid.getPaddingTop();
                boolean motion = animate && isLaidOut() && android.animation.ValueAnimator.areAnimatorsEnabled();
                if (motion) {
                    android.transition.TransitionSet change = new android.transition.TransitionSet();
                    change.addTransition(new android.transition.ChangeBounds().addTarget(results));
                    change.addTransition(new android.transition.Fade().addTarget(editor));
                    change.setDuration(260); change.setInterpolator(new android.view.animation.DecelerateInterpolator());
                    change.addListener(new android.transition.Transition.TransitionListener() {
                        @Override public void onTransitionEnd(android.transition.Transition t) { finish(mine, show, focusAfter, target, anchor); }
                        @Override public void onTransitionCancel(android.transition.Transition t) { finish(mine, show, focusAfter, target, anchor); }
                        @Override public void onTransitionStart(android.transition.Transition t) {}
                        @Override public void onTransitionPause(android.transition.Transition t) {}
                        @Override public void onTransitionResume(android.transition.Transition t) {}
                    });
                    android.transition.TransitionManager.beginDelayedTransition(this, change);
                }
                if (focusAfter || cell != null) results.requestFocus();
                results.setClipChildren(true);
                editor.setVisibility(show ? View.VISIBLE : View.GONE);
                if (layout != null) {
                    int columns = show ? SEARCH_COLUMNS : 6;
                    layout.setSpanCount(columns);
                    if (grid.getAdapter() instanceof TileAdapter) {
                        ((TileAdapter) grid.getAdapter()).columns = columns;
                        grid.getAdapter().notifyDataSetChanged();
                    } else grid.setAdapter(skeletonCards(columns * 2, columns));
                    layout.scrollToPositionWithOffset(anchor, offset);
                }
                if (!motion) post(() -> finish(mine, show, focusAfter, target, anchor));
            });
        }
        void finish(int mine, boolean show, boolean focusAfter, String target, int anchor) {
            if (mine != transition || searchPane != this || !isAttachedToWindow()) return;
            results.setClipChildren(false); changing = false;
            if (!hasFocus()) return; // Respect a newer move to Back or the search header during the animation.
            if (!show && grid != null && grid.findContainingItemView(findFocus()) != null) return;
            if (show && focusAfter) { focusKeyboard(); return; }
            if (show) return;
            if (grid == null) {
                View retry = results.findViewWithTag("reload-search"); if (retry != null) retry.requestFocus();
                return;
            }
            View restored = target.isEmpty() ? null : results.findViewWithTag(target);
            if (restored == null && grid != null) {
                RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(anchor);
                if (holder != null) restored = firstFocusable(holder.itemView);
            }
            if (restored != null) restored.requestFocus();
        }
        void focusKeyboard() {
            View key = editor.findViewWithTag(lastKeyboard);
            if (key == null) key = firstFocusable(editor);
            if (key != null) key.requestFocus();
        }
    }
    /** Search's results column, rebuilt alone as the query changes so the keyboard (and its focus) stays as it is. */
    private int resultsGeneration;
    private void results(LinearLayout results, Item item) {
        int mine = ++resultsGeneration;
        resultsNow(results, item, !item.loading);
        if (item.loading) results.postDelayed(() -> {
            if (mine == resultsGeneration && results == searchResults && results.isAttachedToWindow() && item.loading)
                resultsNow(results, item, true);
        }, 160);
    }
    private void resultsNow(LinearLayout results, Item item, boolean skeletons) {
        if (searchPane != null && searchPane.grid != null) {
            RecyclerView previous = searchPane.grid;
            String key = scrollKeys.get(previous);
            if (key != null) childScroll.put(key, previous.getLayoutManager().onSaveInstanceState());
            searchPane.grid = null;
        }
        results.removeAllViews();
        // A soft tap when titles appear where there were none, not on every keystroke that keeps them.
        if (!item.loading) {
            if (!item.tiles.isEmpty() && shownResults == 0) Sounds.play(Sounds.Sound.RESULTS);
            shownResults = item.tiles.size();
        }
        // No heading: the grid speaks for itself. A provider problem gets one quiet line.
        if (!item.meta.isEmpty()) {
            TextView warning = Tv.lines(Tv.text(context, item.meta, 13, R.color.tv_warning), 3);
            warning.setPadding(0, dp(6), 0, dp(4)); results.addView(warning, wrap());
        }
        if (!item.actions.isEmpty()) {
            LinearLayout actions = new LinearLayout(context); actions.setGravity(Gravity.START);
            for (Item.Action action : item.actions) actions.addView(Tv.button(context, action, this::focused));
            LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2); spacing.topMargin = dp(8); spacing.bottomMargin = dp(8);
            results.addView(actions, spacing);
        }

        if (item.loading && skeletons || !item.tiles.isEmpty()) {
            int columns = searchPane != null && !searchPane.visible ? 6 : SEARCH_COLUMNS;
            RecyclerView list = new RecyclerView(context);
            list.setLayoutManager(new GridLayoutManager(context, columns)); list.setItemAnimator(null);
            if (searchPane != null) searchPane.grid = list;
            // The bottom padding keeps a focused row inside the TV's safe area while unfocused rows run to the edge.
            list.setPadding(0, dp(18), 0, dp(28));
            list.setVerticalScrollBarEnabled(false); list.setNestedScrollingEnabled(false);
            // Rows fade out at the column's top and bottom edges, as the page fades under the tab bar, rather than being cut.
            list.setVerticalFadingEdgeEnabled(false);
            if (item.loading && item.tiles.isEmpty()) list.setAdapter(skeletonCards(columns * 2, columns));
            else list.setAdapter(new TileAdapter(item.tiles, Item.Tile.Style.POSTER, 0, 14, true, columns, 18));
            retainScroll(list, "search:" + item.value);
            // Rows scrolled up must not draw over the heading or the search bar; the edge columns keep room to lift.
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            params.leftMargin = params.rightMargin = -dp(12);
            results.addView(Tv.faded(list, dp(12), 32), params);
            boolean restoringSelection = item.tiles.stream().anyMatch(tile -> tile.key.equals(restore));
            if (!restoringSelection && !item.loading && searchPane != null && !searchPane.visible && results.isFocused())
                list.post(() -> {
                    // A newer provider update can replace this grid before its posted focus runs.
                    if (!list.isAttachedToWindow() || searchPane == null || searchPane.grid != list || !results.isFocused()) return;
                    View first = firstFocusable(list); if (first != null) first.requestFocus();
                });
        } else {
            // Centred in the visible part of the column (it runs 52 dp past the screen): a quiet prompt before a
            // search, or "No results" after one.
            LinearLayout empty = new LinearLayout(context); empty.setOrientation(LinearLayout.VERTICAL); empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, 0, 0, dp(12 + 40));
            TextView message = Tv.text(context, !item.detail.isEmpty() ? item.detail : item.loading ? "Searching…" : "Search films and series", 18, R.color.tv_text_secondary);
            message.setGravity(Gravity.CENTER);
            empty.addView(message, wrap());
            results.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
    }

    /**
     * Settings as a split view: categories on the left; the focused category's rows on the right, changing as focus
     * moves down the categories. Right from a category goes to the first row of its pane; Left from a pane returns
     * to the selected category, which stays lit while its pane is in use.
     */
    private View split(Item item) {
        java.util.Map<String, List<Item>> panes = item.panes;
        List<Item> categories = item.children;
        String chosen = item.value;
        for (Map.Entry<String, List<Item>> pane : panes.entrySet()) for (Item row : pane.getValue()) if (row.key.equals(restore)) chosen = pane.getKey();
        if (panes.containsKey(restore)) chosen = restore;
        if (!panes.containsKey(chosen)) chosen = categories.get(0).key;
        String[] selected = {chosen};
        String restoreTarget = restore;
        boolean[] restoringPane = {!restoreTarget.isEmpty()};

        SplitView root = new SplitView(context);
        root.setOrientation(LinearLayout.HORIZONTAL); root.setClipChildren(false); root.setClipToPadding(false);
        root.setPadding(gutterStart, dp(4), gutterEnd, 0);
        RecyclerView left = new RecyclerView(context), right = new RecyclerView(context);
        PageAdapter pane = new PageAdapter(context, artwork, listener);
        pane.gutter(28, 0);
        PageAdapter list = new PageAdapter(context, artwork, new Listener() {
            @Override public void focused(String key) {
                // Replacing a changed settings pane can give the first category transient framework focus.
                // Restore the actual row before accepting category changes, or the pane jumps back to Add-ons.
                if (restoringPane[0] && !key.equals(restoreTarget)) return;
                listener.focused(key);
                if (!panes.containsKey(key) || key.equals(selected[0])) return;
                View before = left.findViewWithTag(selected[0]);
                if (before != null) before.setActivated(false);
                selected[0] = key;
                View now = left.findViewWithTag(key);
                if (now != null) now.setActivated(true);
                pane.set(panes.get(key), "");
                retainScroll(right, item.key + ":pane:" + key);
                if (item.keyboardInput != null) item.keyboardInput.accept(key);
            }
            @Override public void featured(@Nullable Title title) { listener.featured(title); }
        });
        list.gutter(0, 0);
        // OK or a tap on a category moves into its pane, once the pane shows that category's rows (a tap selects and
        // opens in one step, so the new rows may not be laid out yet).
        Runnable enter = () -> { View first = firstFocusable(right); if (first != null) first.requestFocus(); };
        for (Item category : categories) category.action = () -> {
            if (!right.hasPendingAdapterUpdates() && !right.isLayoutRequested()) { enter.run(); return; }
            right.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() { right.getViewTreeObserver().removeOnGlobalLayoutListener(this); enter.run(); }
            });
        };
        for (RecyclerView column : new RecyclerView[]{left, right}) {
            column.setLayoutManager(new LinearLayoutManager(context)); column.setItemAnimator(null);
            column.setClipChildren(false); column.setClipToPadding(false); column.setVerticalScrollBarEnabled(false);
        }
        left.setAdapter(list); right.setAdapter(pane);
        list.set(categories, panes.containsKey(restore) ? restore : "");
        pane.set(panes.get(chosen), restore);
        retainScroll(left, item.key + ":categories");
        retainScroll(right, item.key + ":pane:" + chosen);
        left.post(() -> { View lit = left.findViewWithTag(selected[0]); if (lit != null) lit.setActivated(true); });
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                root.removeOnLayoutChangeListener(this);
                if (restoringPane[0]) {
                    View target = root.findViewWithTag(restoreTarget);
                    if (target != null) target.requestFocus();
                    restoringPane[0] = false;
                }
            }
        });
        root.addView(left, new LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.MATCH_PARENT));
        // The pane scrolls inside its own clip; the page around it stays still.
        right.setPadding(0, 0, 0, dp(28));
        LinearLayout.LayoutParams paneParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        paneParams.leftMargin = -dp(12); paneParams.rightMargin = -dp(12); paneParams.bottomMargin = -dp(40);
        root.addView(Tv.clipped(right, dp(12)), paneParams);
        root.categories = left; root.pane = right; root.selected = () -> left.findViewWithTag(selected[0]);
        root.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }
    @Nullable private static View firstFocusable(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view.isFocusable() && view.getTag() instanceof String) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = firstFocusable(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
    /** Steers sideways focus between a split view's two columns (see {@link #split}). */
    private static final class SplitView extends LinearLayout {
        View categories, pane;
        java.util.function.Supplier<View> selected;
        SplitView(Context context) { super(context); }
        @Override public View focusSearch(View focused, int direction) {
            View next = super.focusSearch(focused, direction);
            if (direction == View.FOCUS_RIGHT && within(categories, focused)) {
                View first = firstFocusable(pane);
                return first != null ? first : focused;
            }
            if (direction == View.FOCUS_LEFT && within(pane, focused) && (next == null || within(categories, next))) {
                View lit = selected.get();
                if (lit != null) return lit;
            }
            return next;
        }
        private static boolean within(View parent, View view) {
            for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) if (v == parent) return true;
            return false;
        }
    }

    /** Letters, digits/punctuation and language-specific accents, normally six characters to a row. */
    private static final String[] KEY_PAGES = {
            "abcdefghijklmnopqrstuvwxyz",
            "1234567890-':&!?.,()#+@/\"_",
            "àáâäãåçèéêëíîïñóôöõøùúûüßæ"};
    private static final String[] KEY_PAGE_NAMES = {"abc", "123", "àéü"};
    private static final int KEY_COLUMNS = 6, KEY = 34, KEY_GAP = 4;
    /** The keyboard page survives the re-render each keystroke causes, so symbols can be typed one after another. */
    private int keyboardPage;

    /**
     * Fill short character rows with Space/Delete. If only one cell remains, spread the five letters evenly
     * and give the commands their own row. Every offered language and character page fills both edges.
     */
    private LinearLayout keyboardKeys(Item item) {
        LinearLayout keys = new LinearLayout(context); keys.setOrientation(LinearLayout.VERTICAL); keys.setTag("keyboard-keys");
        keys.setClipChildren(false); keys.setClipToPadding(false);
        StringBuilder query = new StringBuilder(item.value);
        keyboardQuery = query;
        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            keys.removeAllViews();
            String characters = keyboardCharacters(item, keyboardPage);
            LinearLayout row = null;
            for (int i = 0; i < characters.length(); i++) {
                if (i % KEY_COLUMNS == 0) row = keyboardRow(keys);
                String character = characters.substring(i, i + 1);
                addKeyboardButton(row, item, character, "Type " + character, 1, () -> type(item, query, character));
            }
            int used = characters.length() % KEY_COLUMNS;
            if (used == KEY_COLUMNS - 1) {
                // Five letters cannot share a row with two usable commands. Their backgrounds fill
                // the row evenly, without an invisible sixth key or a large blank tail.
                for (int i = 0; i < row.getChildCount(); i++) {
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) row.getChildAt(i).getLayoutParams();
                    params.width = 0; params.weight = 1;
                }
            }
            int remaining = KEY_COLUMNS - used;
            if (used == 0 || remaining < 2) { row = keyboardRow(keys); remaining = KEY_COLUMNS; }
            addKeyboardButton(row, item, "Space", "Insert a space", (remaining + 1) / 2, () -> type(item, query, " "));
            addKeyboardButton(row, item, "⌫", "Delete last character", remaining / 2, () -> {
                if (query.length() > 0) query.deleteCharAt(query.length() - 1);
                if (item.keyboardInput != null) item.keyboardInput.accept(query.toString());
            });
            row = keyboardRow(keys);
            for (int other = 0; other < KEY_PAGES.length; other++) if (other != keyboardPage) {
                int target = other;
                String name = other == 0 ? KeyboardLanguages.alphabet(item.keyboardLanguage).substring(0, 3) : KEY_PAGE_NAMES[other];
                addKeyboardButton(row, item, name, "Show " + name + " keys", 2, () -> {
                    keyboardPage = target; rebuild[0].run();
                    keys.post(() -> {
                        View first = keys.findViewWithTag(item.key + ":" + keyboardCharacters(item, target).charAt(0));
                        if (first != null) first.requestFocus();
                    });
                });
            }
            addKeyboardButton(row, item, "Clear", "Clear search", 2, () -> {
                query.setLength(0);
                if (item.keyboardInput != null) item.keyboardInput.accept("");
            });
            row = keyboardRow(keys);
            addKeyboardButton(row, item, KeyboardLanguages.name(item.keyboardLanguage), "Change keyboard language", 6, () -> {
                if (item.keyboardLanguages.size() == 1) { if (item.action != null) item.action.run(); return; }
                int next = (item.keyboardLanguages.indexOf(item.keyboardLanguage) + 1) % item.keyboardLanguages.size();
                item.keyboardLanguage = item.keyboardLanguages.get(next); keyboardPage = 0;
                if (item.keyboardLanguageChanged != null) item.keyboardLanguageChanged.accept(item.keyboardLanguage);
                rebuild[0].run(); keys.post(() -> { View target = keys.findViewWithTag(item.key + ":Language"); if (target != null) target.requestFocus(); });
            });
        };
        rebuild[0].run();
        return keys;
    }
    private int keyboardWidth() { return KEY_COLUMNS * dp(KEY) + (KEY_COLUMNS - 1) * dp(KEY_GAP); }
    private static String keyboardCharacters(Item item, int page) {
        return page == 0 ? KeyboardLanguages.alphabet(item.keyboardLanguage)
                : page == 2 ? KeyboardLanguages.accents(item.keyboardLanguage) : KEY_PAGES[page];
    }
    private LinearLayout keyboardRow(LinearLayout keys) {
        LinearLayout row = new LinearLayout(context); row.setClipChildren(false); row.setClipToPadding(false);
        // Align the key backgrounds, not the baselines of the smaller Space/Delete labels.
        row.setBaselineAligned(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(keyboardWidth(), dp(KEY));
        if (keys.getChildCount() > 0) params.topMargin = dp(KEY_GAP);
        keys.addView(row, params);
        return row;
    }
    private static void type(Item item, StringBuilder query, String character) {
        if (query.length() < 120) query.append(character);
        if (item.keyboardInput != null) item.keyboardInput.accept(query.toString());
    }
    private void addKeyboardButton(LinearLayout row, Item item, String label, String description, int cells, Runnable action) {
        TextView key = Tv.heading(context, label, label.length() == 1 ? 16 : 12, R.color.tv_on_glass);
        key.setGravity(Gravity.CENTER); key.setSingleLine(true); key.setIncludeFontPadding(false);
        key.setContentDescription(description);
        key.setPadding(0, 0, 0, 0);
        if (description.equals("Change keyboard language")) {
            android.graphics.drawable.Drawable glyph = context.getDrawable(R.drawable.ic_tv_language).mutate();
            glyph.setTintList(context.getColorStateList(R.color.tv_on_glass)); glyph.setBounds(0, 0, dp(15), dp(15));
            key.setCompoundDrawables(glyph, null, null, null); key.setCompoundDrawablePadding(dp(6)); key.setPadding(dp(12), 0, dp(10), 0);
        }
        key.setBackgroundResource(R.drawable.tv_keyboard_key);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(cells * dp(KEY) + (cells - 1) * dp(KEY_GAP), dp(KEY));
        if (row.getChildCount() > 0) params.setMarginStart(dp(KEY_GAP));
        row.addView(key, params);
        // Letters and Space tap; Delete and Clear fall. Page switches use the plain select.
        boolean erase = description.startsWith("Delete") || description.startsWith("Clear");
        boolean typed = cells == 1 || label.equals("Space");
        Runnable press = erase ? () -> { Sounds.play(Sounds.Sound.DELETE); action.run(); }
                : typed ? () -> { Sounds.at(Sounds.Sound.KEY, key); action.run(); } : action;
        // The white focus fill keeps keys on their grid, including wide Space/Delete keys.
        Tv.control(key, item.key + ":" + (description.equals("Change keyboard language") ? "Language" : label), press, this::focused, 1f, false);
        if (key.getTag() != null && key.getTag().equals(restore)) key.post(key::requestFocus);
    }

    private View loading() {
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER);
        ProgressBar spinner = new ProgressBar(context);
        spinner.setIndeterminateDrawable(context.getDrawable(R.drawable.tv_loading_indicator));
        spinner.setContentDescription("Loading");
        root.addView(spinner, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return root;
    }

    /** Scrolls so a focused child keeps some room around it, rather than stopping at the very edge. */
    public static final class Layout extends LinearLayoutManager {
        private final int margin;
        @Nullable private View navigationTarget;
        public void navigationTarget(@Nullable View target) { navigationTarget = target; }
        /** Each block's height as last measured, so the distance to the top is known after the top has scrolled away. */
        private final android.util.SparseIntArray heights = new android.util.SparseIntArray();
        public Layout(Context context, int orientation, int marginDp) { super(context, orientation, false); margin = Tv.dp(context, marginDp); }
        @Override public void measureChildWithMargins(View child, int widthUsed, int heightUsed) {
            super.measureChildWithMargins(child, widthUsed, heightUsed);
            RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) child.getLayoutParams();
            heights.put(getPosition(child), getDecoratedMeasuredHeight(child) + params.topMargin + params.bottomMargin);
        }
        @Override public void onItemsChanged(RecyclerView view) { heights.clear(); }
        @Override public void onItemsAdded(RecyclerView view, int start, int count) { heights.clear(); }
        @Override public void onItemsRemoved(RecyclerView view, int start, int count) { heights.clear(); }
        @Override public void onItemsUpdated(RecyclerView view, int start, int count) { heights.clear(); }
        @Override public void onItemsMoved(RecyclerView view, int from, int to, int count) { heights.clear(); }
        /** How far the page is scrolled from its top, or -1 when a block above the screen was never measured. */
        private int scrolled() {
            View top = null; int topPosition = Integer.MAX_VALUE;
            for (int i = 0; i < getChildCount(); i++) { View c = getChildAt(i); int p = getPosition(c); if (p < topPosition) { topPosition = p; top = c; } }
            if (top == null) return -1;
            int offset = getPaddingTop() - getDecoratedTop(top);
            for (int p = 0; p < topPosition; p++) { int h = heights.get(p, -1); if (h < 0) return -1; offset += h; }
            return offset;
        }
        @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect, boolean immediate, boolean focusedChildVisible) {
            // A block that fills the page (Search's keyboard and results, Settings) scrolls inside itself, never the page.
            if (getOrientation() == VERTICAL && child.getHeight() >= getHeight() - getPaddingTop() - getPaddingBottom()) return false;
            // Pointer/remote focus must not move a control which is already fully visible. In particular,
            // applying the extra focus margin at the pagination footer caused a jump on press/hover.
            if (getOrientation() == VERTICAL && child.getTop() + rect.top >= getPaddingTop()
                    && child.getTop() + rect.bottom <= getHeight()) return false;
            // While the focused view fits in the first screenful, return the page to its very top (the Home spotlight
            // shows again), even when the top blocks have scrolled away and been recycled.
            int offset = getOrientation() == VERTICAL ? scrolled() : -1;
            if (offset >= 0) {
                if (child.getTop() + rect.bottom + offset <= getHeight()) {
                    if (offset == 0) return false;
                    if (immediate) parent.scrollBy(0, -offset); else parent.smoothScrollBy(0, -offset);
                    return true;
                }
            }
            // Focus zoom is allowed into the shelf's unclipped gutters. Use the stable item bounds, not the
            // animated artwork rectangle, and keep an already visible card still for both remote and pointer input.
            if (getOrientation() == HORIZONTAL && getDecoratedLeft(child) >= getPaddingLeft()
                    && getDecoratedRight(child) <= getWidth() - getPaddingRight()) return false;
            Rect grown = new Rect(rect);
            if (getOrientation() == VERTICAL) { grown.top -= margin; grown.bottom += margin; } else { grown.left -= margin; grown.right += margin; }
            return super.requestChildRectangleOnScreen(parent, child, grown, immediate, focusedChildVisible);
        }
        /**
         * As on tvOS, left and right never leave the content: a shelf keeps focus at its ends, and the page refuses a
         * sideways move out of itself (the framework would otherwise jump from the last tile or button up to a tab).
         */
        @Override public View onInterceptFocusSearch(View focused, int direction) {
            if (direction == View.FOCUS_UP && getOrientation() == HORIZONTAL
                    && navigationTarget != null && navigationTarget.isShown()) return navigationTarget;
            if (direction == View.FOCUS_UP && getOrientation() == VERTICAL) return upFromTop(focused);
            if (direction != View.FOCUS_LEFT && direction != View.FOCUS_RIGHT) return null;
            View item = findContainingItemView(focused);
            if (item == null) return null;
            if (getOrientation() == HORIZONTAL) {
                boolean forward = (direction == View.FOCUS_RIGHT) != (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
                int position = getPosition(item);
                return (forward && position == getItemCount() - 1) || (!forward && position == 0) ? focused : null;
            }
            View next = FocusFinder.getInstance().findNextFocus((ViewGroup) focused.getRootView(), focused, direction);
            return next != null && findContainingItemView(next) == null ? focused : null;
        }
        /**
         * Up from the first control of a page goes on to the tab bar. Without this the list would answer with the item
         * above (the Home spotlight, a title), which can't take focus, and only scroll: the remote could never leave.
         */
        @Nullable private View upFromTop(View focused) {
            View item = findContainingItemView(focused);
            if (item == null) return null;
            ViewGroup list = (ViewGroup) item.getParent();
            if (FocusFinder.getInstance().findNextFocus(list, focused, View.FOCUS_UP) != null) return null;
            for (int position = getPosition(item) - 1; position >= 0; position--) {
                View above = findViewByPosition(position);
                if (above == null || above.hasFocusable()) return null; // the list scrolls to it as usual
            }
            // Enter navigation deterministically, rather than whichever button is geometrically nearest.
            if (navigationTarget != null && navigationTarget.isShown()) return navigationTarget;
            View next = FocusFinder.getInstance().findNextFocus((ViewGroup) focused.getRootView(), focused, View.FOCUS_UP);
            return next != null && findContainingItemView(next) == null ? next : null;
        }
    }
}
