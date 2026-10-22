package tv.daniil.stream.ui;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
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
import tv.daniil.stream.R;
import tv.daniil.stream.addon.Title;

/** Builds a {@link Page}'s blocks. Every focusable view is tagged with its key so focus can be restored. */
public final class PageAdapter extends RecyclerView.Adapter<PageAdapter.Holder> {
    public interface Listener {
        void focused(String key);
        /** The Home spotlight changed to this title (null when the page has no artwork). */
        void featured(@Nullable Title title);
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
    private String restore = "";
    private int gutterStart, gutterEnd;
    private Spotlight spotlight;
    @Nullable private Item.Tile featured;
    @Nullable private RecyclerView recycler;
    private int generation;

    public PageAdapter(Context context, Artwork artwork, Listener listener) {
        this.context = context; this.artwork = artwork; this.listener = listener;
        setHasStableIds(true); gutter(48, 48);
    }

    /** Page margins; a side panel already provides the left one. */
    public void gutter(int start, int end) { gutterStart = dp(start); gutterEnd = dp(end); }
    public void set(List<Item> value, String restoreKey) {
        items = new ArrayList<>(value); restore = restoreKey == null ? "" : restoreKey; spotlight = null; featured = null;
        notifyDataSetChanged();
        // Restoration is a one-off: a shelf scrolled back into view later must not take focus again.
        int mine = ++generation;
        if (recycler != null) recycler.postDelayed(() -> { if (mine == generation) restore = ""; }, 1500);
    }
    @Override public void onAttachedToRecyclerView(RecyclerView view) { recycler = view; }
    @Override public void onDetachedFromRecyclerView(RecyclerView view) { recycler = null; }
    private void focused(String key) { if (key.equals(restore)) restore = ""; listener.focused(key); }
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
        boolean fillViewport = item.kind == Item.Kind.KEYBOARD || item.kind == Item.Kind.LOADING;
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
            case INTRO: return intro(item);
            case TITLE: return pageTitle(item);
            case HEADER: return listFollows(item) ? sectionHeader(item.title, item.detail) : header(item.title, item.detail, 20);
            case SHELF: return shelf(item);
            case GRID: return grid(item);
            case ACTIONS: return actions(item, item.centered ? Gravity.CENTER_HORIZONTAL : Gravity.START);
            case CHIPS: return chips(item);
            case LIST: return row(item);
            case SOURCE: return source(item);
            case FIELD: return field(item);
            case KEYBOARD: return keyboard(item);
            case SKELETON: return skeleton(item);
            case MESSAGE: return message(item);
            case NOTICE: return notice(item);
            case TEXT: return text(item);
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
        final TextView title = Tv.oneLine(Tv.bold(context, "", 34));
        final TextView meta = Tv.oneLine(Tv.text(context, "", 14, R.color.tv_text_secondary));
        final TextView description = Tv.lines(Tv.text(context, "", 15, R.color.tv_text), 2);
        String key = "";
        Runnable open;
        Title shown;

        Spotlight() {
            root.setGravity(Gravity.BOTTOM); root.setPadding(gutterStart, dp(4), gutterEnd, dp(2));
            root.setTag("spotlight"); root.setClickable(true);
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
            String facts = metaLine(media), type = kind(media);
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
        List<String> parts = new ArrayList<>();
        if (!title.releaseInfo.isEmpty()) parts.add(title.releaseInfo);
        if (!title.genres.isEmpty()) parts.add(String.join(", ", title.genres.subList(0, Math.min(3, title.genres.size()))));
        if (!title.rating.isEmpty()) parts.add("★ " + title.rating);
        return String.join("  ·  ", parts);
    }

    // Details header: logo or title, facts and description over the title's backdrop.
    private View details(Item item) {
        Title media = item.media;
        LinearLayout root = column(); root.setGravity(Gravity.BOTTOM); root.setMinimumHeight(dp(220));
        root.setPadding(gutterStart, dp(4), gutterEnd, dp(14));
        TextView overline = Tv.oneLine(Tv.heading(context, kind(media), 12.5f, R.color.tv_text_secondary));
        overline.setLetterSpacing(0.14f); overline.setAllCaps(true); root.addView(overline);
        ImageView logo = new ImageView(context);
        logo.setAdjustViewBounds(true); logo.setScaleType(ImageView.ScaleType.FIT_START); logo.setMaxHeight(dp(92)); logo.setMaxWidth(dp(400));
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); logo.setVisibility(View.GONE);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        logoParams.topMargin = dp(10);
        root.addView(logo, logoParams);
        TextView title = Tv.lines(Tv.bold(context, media.name, 36), 2); title.setMaxWidth(dp(640)); title.setPadding(0, dp(8), 0, 0);
        root.addView(title, wrap());
        if (media.logo != null) artwork.fetch(media.logo, Artwork.LOGO, true, bitmap -> { logo.setImageBitmap(bitmap); gone(logo, false); gone(title, true); });
        String facts = metaLine(media);
        if (!media.directors.isEmpty()) facts += (facts.isEmpty() ? "" : "  ·  ") + "Directed by " + String.join(", ", media.directors.subList(0, Math.min(2, media.directors.size())));
        if (!facts.isEmpty()) {
            TextView meta = Tv.oneLine(Tv.text(context, facts, 14, R.color.tv_text_secondary)); meta.setMaxWidth(dp(640)); meta.setPadding(0, dp(12), 0, 0);
            root.addView(meta, wrap());
        }
        if (!media.description.isEmpty()) {
            TextView about = Tv.lines(Tv.text(context, media.description, 15, R.color.tv_text), 3); about.setMaxWidth(dp(560)); about.setPadding(0, dp(8), 0, 0);
            root.addView(about, wrap());
        }
        if (!item.detail.isEmpty()) {
            TextView note = Tv.oneLine(Tv.text(context, item.detail, 13.5f, R.color.tv_warning)); note.setPadding(0, dp(8), 0, 0); root.addView(note);
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
        TextView title = Tv.oneLine(Tv.bold(context, item.title, 30)); title.setPadding(gutterStart, dp(6), gutterEnd, dp(4));
        title.setLayoutParams(fill());
        return title;
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
            text.setSpan(new android.text.style.TypefaceSpan("sans-serif"), start, text.length(), span);
        }
        TextView heading = Tv.oneLine(Tv.heading(context, "", sp, color)); heading.setText(text);
        heading.setContentDescription(detail == null || detail.isEmpty() ? title : title + ", " + detail);
        row.addView(heading);
        row.setLayoutParams(fill());
        return row;
    }

    // Shelves: a heading over a horizontally scrolling row of tiles.
    private View shelf(Item item) {
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false); root.setClipToPadding(false);
        root.addView(header(item.title, item.detail, 14));
        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new Layout(context, RecyclerView.HORIZONTAL, 96));
        list.setClipToPadding(false); list.setClipChildren(false); list.setItemAnimator(null);
        list.setHasFixedSize(true);
        list.setPadding(gutterStart, dp(12), gutterEnd, dp(14));
        list.setHorizontalScrollBarEnabled(false);
        TileAdapter adapter = new TileAdapter(item.tiles, item.style, item.style == Item.Tile.Style.POSTER ? POSTER_WIDTH : LANDSCAPE_WIDTH);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int inShelf = -1;
        for (int i = 0; i < item.tiles.size(); i++) if (item.tiles.get(i).key.equals(restore)) inShelf = i;
        if (inShelf >= 0) {
            list.scrollToPosition(inShelf);
            String key = restore;
            list.post(() -> { View target = list.findViewWithTag(key); if (target != null) target.requestFocus(); });
        }
        root.setLayoutParams(fill());
        return root;
    }

    /** Equal-width tiles: weights rather than a measured width, which may still be the previous page's. */
    private View grid(Item item) {
        int columns = Integer.parseInt(item.value);
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false); row.setClipToPadding(false); row.setWeightSum(columns);
        row.setPadding(gutterStart, dp(12), gutterEnd, dp(6));
        for (int i = 0; i < item.tiles.size(); i++) {
            View tile = tile(item.tiles.get(i), item.style, 0);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            p.leftMargin = i == 0 ? 0 : dp(GAP / 2f); p.rightMargin = i == columns - 1 ? 0 : dp(GAP / 2f);
            row.addView(tile, p);
        }
        row.setLayoutParams(fill());
        return row;
    }

    private final class TileAdapter extends RecyclerView.Adapter<Holder> {
        final List<Item.Tile> tiles; final Item.Tile.Style style; final int widthDp, gapDp, columns, verticalGapDp; final boolean matchSpan;
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

    private RecyclerView.Adapter<Holder> skeletonCards(int count) {
        return new RecyclerView.Adapter<Holder>() {
            @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
                FrameLayout box = new FrameLayout(context);
                box.setClipChildren(false); box.setClipToPadding(false);
                LinearLayout card = new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL);
                AspectFrame poster = new AspectFrame(context, 1.5f); poster.setBackgroundResource(R.drawable.tv_skeleton);
                card.addView(poster, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                View title = skeletonBar(76, 12);
                LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(dp(76), dp(12));
                titleParams.topMargin = dp(9); card.addView(title, titleParams);
                View subtitle = skeletonBar(48, 10);
                LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(dp(48), dp(10));
                subtitleParams.topMargin = dp(6); card.addView(subtitle, subtitleParams);
                box.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                box.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new Holder(box);
            }
            @Override public void onBindViewHolder(Holder holder, int position) {
                FrameLayout box = (FrameLayout) holder.itemView;
                boolean endOfRow = position % SEARCH_COLUMNS == SEARCH_COLUMNS - 1;
                box.setPadding(0, 0, endOfRow ? 0 : dp(8), dp(16));
            }
            @Override public int getItemCount() { return count; }
        };
    }

    private View skeleton(Item item) {
        LinearLayout root = column(); root.setPadding(gutterStart, dp(18), gutterEnd, dp(30));
        switch (item.value) {
            case "Home":
                root.addView(skeletonHero()); root.addView(skeletonShelf()); root.addView(skeletonShelf()); break;
            case "Library": case "History":
                root.addView(skeletonShelf()); root.addView(skeletonShelf()); break;
            case "Settings": skeletonRows(root, 7); break;
            case "Imports": skeletonRows(root, 4); break;
            case "Add-on": root.addView(skeletonHero()); skeletonRows(root, 3); break;
            case "Add-ons": root.addView(skeletonChips()); skeletonRows(root, 6); break;
            case "Add-on review": skeletonRows(root, 4); break;
            case "Catalogues": skeletonRows(root, 5); break;
            case "Catalogue": root.addView(skeletonChips()); root.addView(skeletonShelf()); break;
            case "Details": root.addView(skeletonHero()); root.addView(skeletonChips()); root.addView(skeletonShelf()); break;
            case "Sources": skeletonRows(root, 5); break;
            case "Developer": skeletonRows(root, 5); break;
            default: return loading();
        }
        root.setLayoutParams(fill()); return root;
    }

    private View skeletonHero() {
        LinearLayout hero = new LinearLayout(context); hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.BOTTOM); hero.setMinimumHeight(dp(164)); hero.setPadding(0, dp(12), 0, dp(22));
        hero.addView(skeletonBar(280, 28)); addSkeletonGap(hero, 12);
        hero.addView(skeletonBar(190, 13)); addSkeletonGap(hero, 10);
        hero.addView(skeletonBar(430, 13));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(12); hero.setLayoutParams(p); return hero;
    }

    private View skeletonShelf() {
        LinearLayout shelf = new LinearLayout(context); shelf.setOrientation(LinearLayout.VERTICAL);
        shelf.setPadding(0, dp(10), 0, dp(16));
        shelf.addView(skeletonBar(170, 18));
        LinearLayout cards = new LinearLayout(context); cards.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams cardsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardsParams.topMargin = dp(12); shelf.addView(cards, cardsParams);
        for (int i = 0; i < 6; i++) {
            LinearLayout card = new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL);
            AspectFrame poster = new AspectFrame(context, 1.5f); poster.setBackgroundResource(R.drawable.tv_skeleton);
            card.addView(poster, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addSkeletonGap(card, 8); card.addView(skeletonBar(82, 11)); addSkeletonGap(card, 5); card.addView(skeletonBar(54, 9));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            cardParams.setMargins(dp(6), 0, dp(6), 0); cards.addView(card, cardParams);
        }
        return shelf;
    }

    private View skeletonChips() {
        LinearLayout chips = new LinearLayout(context); chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setPadding(0, dp(8), 0, dp(18));
        for (int width : new int[]{82, 104, 76, 92}) {
            View chip = skeletonBar(width, 34);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(width), dp(34)); p.setMargins(0, 0, dp(10), 0);
            chips.addView(chip, p);
        }
        return chips;
    }

    private void skeletonRows(LinearLayout root, int count) {
        for (int i = 0; i < count; i++) {
            FrameLayout row = new FrameLayout(context); row.setBackgroundResource(R.drawable.tv_skeleton);
            View icon = skeletonBar(24, 24);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER_VERTICAL | Gravity.START);
            iconParams.leftMargin = dp(22); row.addView(icon, iconParams);
            LinearLayout text = new LinearLayout(context); text.setOrientation(LinearLayout.VERTICAL); text.setGravity(Gravity.CENTER_VERTICAL);
            text.addView(skeletonBar(216, 13));
            LinearLayout.LayoutParams sub = new LinearLayout.LayoutParams(dp(132), dp(9)); sub.topMargin = dp(7); text.addView(skeletonBar(132, 9), sub);
            FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL | Gravity.START);
            textParams.leftMargin = dp(66); row.addView(text, textParams);
            View trailing = skeletonBar(52, 11);
            FrameLayout.LayoutParams trailingParams = new FrameLayout.LayoutParams(dp(52), dp(11), Gravity.CENTER_VERTICAL | Gravity.END);
            trailingParams.rightMargin = dp(42); row.addView(trailing, trailingParams);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
            rowParams.bottomMargin = dp(6); root.addView(row, rowParams);
        }
    }

    private View skeletonBar(int widthDp, int heightDp) {
        View view = new View(context); view.setBackgroundResource(R.drawable.tv_skeleton);
        if (widthDp > 0) view.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp)));
        return view;
    }

    private void addSkeletonGap(LinearLayout parent, int heightDp) {
        parent.addView(new View(context), new LinearLayout.LayoutParams(1, dp(heightDp)));
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
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false); root.setClipToPadding(false);
        root.setContentDescription(tile.subtitle.isEmpty() ? tile.title : tile.title + ". " + tile.subtitle);
        FrameLayout frame = new AspectFrame(context, style == Item.Tile.Style.POSTER ? 1.5f : 0.5625f);
        frame.setBackgroundResource(R.drawable.tv_artwork); frame.setClipToOutline(true);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) { outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10)); }
        });
        TextView placeholder = Tv.lines(Tv.bold(context, tile.title, style == Item.Tile.Style.POSTER ? 15 : 17), 4);
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
        TextView title = Tv.oneLine(Tv.heading(context, tile.title, 13.5f, R.color.tv_caption));
        title.setDuplicateParentStateEnabled(true); title.setPadding(dp(2), dp(9), dp(2), 0);
        root.addView(title, new LinearLayout.LayoutParams(fill, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView subtitle = Tv.oneLine(Tv.text(context, tile.subtitle.isEmpty() ? " " : tile.subtitle, 12, R.color.tv_text_tertiary));
        subtitle.setPadding(dp(2), dp(3), dp(2), 0);
        root.addView(subtitle, new LinearLayout.LayoutParams(fill, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setLayoutParams(new LinearLayout.LayoutParams(width > 0 ? width : fill, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The artwork lifts with a soft shadow; the caption below grows with it and brightens.
        Tv.control(root, tile.key, tile.action, key -> { focused(key); feature(tile); }, Tv.LIFT_TILE, false);
        View.OnFocusChangeListener lift = root.getOnFocusChangeListener();
        root.setOnFocusChangeListener((v, has) -> {
            lift.onFocusChange(v, has);
            frame.animate().translationZ(has ? dp(16) : 0).setDuration(200).start();
        });
        if (tile.key.equals(restore)) root.post(root::requestFocus);
        return root;
    }

    // Buttons, chips and rows.
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
        TextView caption = icons ? Tv.oneLine(Tv.text(context, "", 12.5f, R.color.tv_text_secondary)) : null;
        for (int i = 0; i < item.actions.size(); i++) {
            Item.Action action = item.actions.get(i);
            View button = Tv.button(context, action, this::focused);
            if (i > 0) ((LinearLayout.LayoutParams) button.getLayoutParams()).leftMargin = dp(action.style == Item.Action.Style.TILE ? 18 : 12);
            row.addView(button);
            if (caption != null) {
                View.OnFocusChangeListener focus = button.getOnFocusChangeListener();
                boolean iconOnly = action.style == Item.Action.Style.ICON;
                button.setOnFocusChangeListener((v, has) -> {
                    focus.onFocusChange(v, has);
                    if (has && iconOnly) {
                        caption.setText(action.label);
                        caption.post(() -> caption.setTranslationX(v.getLeft() + v.getWidth() / 2f - caption.getWidth() / 2f - scroll.getScrollX()));
                        caption.animate().alpha(1f).setDuration(160).start();
                    } else if (has || caption.getText().toString().equals(action.label)) caption.animate().alpha(0f).setDuration(120).start();
                });
            }
            if (action.key.equals(restore)) button.post(button::requestFocus);
        }
        scroll.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (caption == null) { scroll.setLayoutParams(fill()); return scroll; }
        LinearLayout column = new LinearLayout(context); column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false); column.setClipToPadding(false);
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        caption.setAlpha(0f); caption.setGravity(Gravity.CENTER); caption.setMinWidth(dp(160));
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        column.addView(caption, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.setLayoutParams(fill());
        return column;
    }
    private View chips(Item item) {
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false); root.setClipToPadding(false);
        if (!item.title.isEmpty()) root.addView(sectionHeader(item.title, ""));
        root.addView(actions(item, Gravity.START));
        root.setLayoutParams(fill());
        return root;
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
        LinearLayout root = column(); root.setGravity(Gravity.CENTER_HORIZONTAL); root.setPadding(gutterStart, dp(52), gutterEnd, dp(12));
        if (item.icon != 0) {
            FrameLayout disc = new FrameLayout(context); disc.setBackgroundResource(R.drawable.tv_icon_disc);
            ImageView icon = Tv.icon(context, item.icon, 30, R.color.tv_text);
            disc.addView(icon, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
            root.addView(disc, new LinearLayout.LayoutParams(dp(64), dp(64)));
        }
        TextView title = Tv.heading(context, item.title, 22, R.color.tv_text); title.setGravity(Gravity.CENTER); title.setPadding(0, dp(18), 0, 0);
        root.addView(title, wrap());
        if (!item.detail.isEmpty()) {
            TextView detail = Tv.text(context, item.detail, 15, R.color.tv_text_secondary); detail.setGravity(Gravity.CENTER);
            detail.setLineSpacing(0, 1.22f); detail.setMaxWidth(dp(520)); detail.setPadding(0, dp(8), 0, dp(8)); root.addView(detail, wrap());
        }
        if (!item.actions.isEmpty()) root.addView(actions(item, Gravity.CENTER_HORIZONTAL));
        root.setLayoutParams(fill());
        return root;
    }
    private View notice(Item item) {
        LinearLayout box = new LinearLayout(context); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL);
        box.setClipChildren(false); box.setClipToPadding(false);
        box.setBackgroundResource(R.drawable.tv_notice);
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
        FrameLayout wrap = new FrameLayout(context); wrap.setClipChildren(false); wrap.setClipToPadding(false);
        wrap.setPadding(gutterStart, dp(14), gutterEnd, dp(6));
        wrap.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.setLayoutParams(fill());
        return wrap;
    }
    private View text(Item item) {
        LinearLayout root = column(); root.setPadding(gutterStart + (inList(item) ? dp(ROW_INSET) : 0), dp(14), gutterEnd, dp(2));
        if (!item.title.isEmpty()) {
            TextView title = Tv.heading(context, item.title, 12, R.color.tv_text_tertiary); title.setLetterSpacing(0.1f); title.setAllCaps(true);
            root.addView(title);
        }
        TextView detail = Tv.text(context, item.detail, 15, R.color.tv_text_secondary); detail.setLineSpacing(0, 1.22f); detail.setPadding(0, dp(5), 0, 0);
        detail.setTextIsSelectable(false); root.addView(detail);
        root.setLayoutParams(fill());
        return root;
    }
    private View keyboard(Item item) {
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL); root.setClipChildren(false); root.setClipToPadding(false);
        root.setPadding(gutterStart, dp(8), gutterEnd, dp(12));

        LinearLayout keyboardPanel = new LinearLayout(context); keyboardPanel.setGravity(Gravity.CENTER);
        keyboardPanel.setClipChildren(false); keyboardPanel.setClipToPadding(false);
        root.addView(keyboardPanel, new LinearLayout.LayoutParams(dp(236), ViewGroup.LayoutParams.MATCH_PARENT));
        keyboardPanel.addView(keyboardKeys(item), wrap());

        View divider = new View(context); divider.setBackgroundColor(context.getColor(R.color.tv_hairline));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(1), dp(300));
        dividerParams.setMargins(dp(10), 0, dp(16), 0); root.addView(divider, dividerParams);

        LinearLayout results = new LinearLayout(context); results.setOrientation(LinearLayout.VERTICAL);
        results.setClipChildren(false); results.setClipToPadding(false);
        results.setPadding(0, dp(8), 0, 0);
        // Results run to the screen's edge, through this block's and the page list's bottom padding (40dp).
        LinearLayout.LayoutParams resultsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        resultsParams.bottomMargin = -dp(12 + 40); resultsParams.gravity = Gravity.TOP;
        root.addView(results, resultsParams);

        LinearLayout heading = new LinearLayout(context); heading.setGravity(Gravity.CENTER_VERTICAL);
        boolean asked = item.loading || !item.tiles.isEmpty() || !item.detail.isEmpty();
        // Before a search there is nothing to head: a quiet prompt stands in for the results.
        TextView label = asked ? Tv.heading(context, "Results", 21, R.color.tv_text)
                : Tv.text(context, "Type a title to search films, series and imports.", 16, R.color.tv_text_secondary);
        heading.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!item.detail.isEmpty()) {
            TextView count = Tv.oneLine(Tv.text(context, item.detail, 14, R.color.tv_text_secondary));
            LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            countParams.leftMargin = dp(12); heading.addView(count, countParams);
        }
        results.addView(heading, wrap());
        if (!item.meta.isEmpty()) {
            TextView warning = Tv.oneLine(Tv.text(context, item.meta, 13, R.color.tv_warning));
            warning.setPadding(0, dp(6), 0, 0); results.addView(warning, wrap());
        }

        if (item.loading || !item.tiles.isEmpty()) {
            RecyclerView list = new RecyclerView(context);
            list.setLayoutManager(new GridLayoutManager(context, SEARCH_COLUMNS)); list.setItemAnimator(null);
            // The bottom padding keeps a focused row inside the TV's safe area while unfocused rows run to the edge.
            list.setPadding(0, dp(8), 0, dp(28));
            list.setVerticalScrollBarEnabled(false); list.setNestedScrollingEnabled(true);
            if (item.loading) list.setAdapter(skeletonCards(6));
            else list.setAdapter(new TileAdapter(item.tiles, Item.Tile.Style.POSTER, 0, 14, true, SEARCH_COLUMNS, 18));
            // Rows scrolled up must not draw over the heading or the search bar; the edge columns keep room to lift.
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            params.leftMargin = params.rightMargin = -dp(12);
            results.addView(Tv.clipped(list, dp(12)), params);
        } else if (!item.detail.isEmpty()) {
            FrameLayout empty = new FrameLayout(context);
            TextView message = Tv.heading(context, item.detail, 20, R.color.tv_text_secondary);
            empty.addView(message, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            results.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            results.addView(new View(context), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        root.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private LinearLayout keyboardKeys(Item item) {
        LinearLayout keys = new LinearLayout(context); keys.setOrientation(LinearLayout.VERTICAL);
        keys.setGravity(Gravity.CENTER_HORIZONTAL); keys.setClipChildren(false); keys.setClipToPadding(false);
        StringBuilder query = new StringBuilder(item.value);
        boolean[] symbols = {false};
        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            keys.removeAllViews();
            String[][] rows = symbols[0]
                    ? new String[][]{{"1", "2", "3", "4", "5"}, {"6", "7", "8", "9", "0"},
                            {"@", "#", "$", "%", "&"}, {"-", "+", "(", ")", "/"},
                            {".", ",", "?", "!", "'"}, {":", ";", "=", "⌫"}}
                    : new String[][]{{"A", "B", "C", "D", "E"}, {"F", "G", "H", "I", "J"},
                            {"K", "L", "M", "N", "O"}, {"P", "Q", "R", "S", "T"},
                            {"U", "V", "W", "X", "Y"}, {"Z", "⌫"}};
            for (String[] row : rows) addKeyboardRow(keys, item, row, query);
            LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER);
            keys.addView(row, wrap());
            addKeyboardButton(row, item, symbols[0] ? "ABC" : "123", symbols[0] ? "Switch to letters" : "Switch to numbers and symbols", 36,
                    () -> {
                        symbols[0] = !symbols[0]; rebuild[0].run();
                        keys.post(() -> {
                            View firstKey = keys.findViewWithTag(item.key + ":" + (symbols[0] ? "1" : "A"));
                            if (firstKey != null) firstKey.requestFocus();
                        });
                    });
            addKeyboardButton(row, item, "Space", "Insert a space", 46, () -> {
                if (query.length() < 120) query.append(' ');
                if (item.keyboardInput != null) item.keyboardInput.accept(query.toString());
            });
            addKeyboardButton(row, item, "Clear", "Clear search", 40, () -> {
                query.setLength(0);
                if (item.keyboardInput != null) item.keyboardInput.accept("");
            });
            addKeyboardButton(row, item, "Search", "Search", 48, () -> {
                if (item.keyboardSubmit != null) item.keyboardSubmit.run();
            });
        };
        rebuild[0].run();
        return keys;
    }

    private void addKeyboardRow(LinearLayout keys, Item item, String[] labels, StringBuilder query) {
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER);
        keys.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        for (String label : labels) {
            boolean backspace = label.equals("⌫");
            addKeyboardButton(row, item, label, backspace ? "Delete last character" : "Type " + label,
                    36, () -> {
                        if (backspace) {
                            if (query.length() > 0) query.deleteCharAt(query.length() - 1);
                        } else if (query.length() < 120) query.append(label);
                        if (item.keyboardInput != null) item.keyboardInput.accept(query.toString());
                    });
        }
    }

    private void addKeyboardButton(LinearLayout row, Item item, String label, String description, int width, Runnable action) {
        TextView key = Tv.heading(context, label, label.length() == 1 ? 14 : 10, R.color.tv_on_glass);
        key.setGravity(Gravity.CENTER);
        key.setSingleLine(true);
        key.setContentDescription(description);
        key.setBackgroundResource(label.equals("Search") ? R.drawable.tv_chip : R.drawable.keycap);
        key.setDefaultFocusHighlightEnabled(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(width), dp(34));
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(key, params);
        Tv.control(key, item.key + ":" + label, action, this::focused, 1.04f, false);
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
        public Layout(Context context, int orientation, int marginDp) { super(context, orientation, false); margin = Tv.dp(context, marginDp); }
        @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect, boolean immediate, boolean focusedChildVisible) {
            // While the focused view fits in the first screenful, keep the page at its top (the Home spotlight stays visible).
            View first = getOrientation() == VERTICAL ? findViewByPosition(0) : null;
            if (first != null) {
                int offset = getPaddingTop() - first.getTop();
                if (child.getTop() + rect.bottom + offset <= getHeight()) {
                    if (offset == 0) return false;
                    if (immediate) parent.scrollBy(0, -offset); else parent.smoothScrollBy(0, -offset);
                    return true;
                }
            }
            Rect grown = new Rect(rect);
            if (getOrientation() == VERTICAL) { grown.top -= margin; grown.bottom += margin; } else { grown.left -= margin; grown.right += margin; }
            return super.requestChildRectangleOnScreen(parent, child, grown, immediate, focusedChildVisible);
        }
        /**
         * As on tvOS, left and right never leave the content: a shelf keeps focus at its ends, and the page refuses a
         * sideways move out of itself (the framework would otherwise jump from the last tile or button up to a tab).
         */
        @Override public View onInterceptFocusSearch(View focused, int direction) {
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
    }
}
