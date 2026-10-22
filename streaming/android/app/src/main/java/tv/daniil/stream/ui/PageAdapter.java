package tv.daniil.stream.ui;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
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

    private static final int POSTER_WIDTH = 112, LANDSCAPE_WIDTH = 240, GAP = 22;
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
        box.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                fillViewport ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View build(Item item) {
        switch (item.kind) {
            case SPOTLIGHT: return spotlight(item);
            case DETAILS: return details(item);
            case INTRO: return intro(item);
            case HEADER: return header(item.title, item.detail, 26);
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
        final TextView title = Tv.oneLine(Tv.bold(context, "", 38));
        final TextView meta = Tv.oneLine(Tv.text(context, "", 15, R.color.tv_text_secondary));
        final TextView description = Tv.lines(Tv.text(context, "", 16, R.color.tv_text), 2);
        String key = "";
        Runnable open;
        Title shown;

        Spotlight() {
            root.setGravity(Gravity.BOTTOM); root.setPadding(gutterStart, dp(4), gutterEnd, dp(6));
            root.setTag("spotlight"); root.setClickable(true);
            root.setOnClickListener(v -> { if (open != null) { focused(key); open.run(); } });
            logo.setAdjustViewBounds(true); logo.setScaleType(ImageView.ScaleType.FIT_START); logo.setMaxHeight(dp(70)); logo.setMaxWidth(dp(400));
            logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            // A fixed height keeps the shelves still while the spotlight follows focus.
            root.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(164)));
            // Wrapped widths: a MATCH_PARENT text view would ignore its maximum width.
            title.setMaxWidth(dp(620)); meta.setMaxWidth(dp(620)); description.setMaxWidth(dp(580));
            root.addView(logo, wrap()); root.addView(title, wrap());
            root.addView(meta, wrap()); root.addView(description, wrap());
            ((LinearLayout.LayoutParams) title.getLayoutParams()).topMargin = dp(4);
            ((LinearLayout.LayoutParams) logo.getLayoutParams()).topMargin = dp(6);
            meta.setPadding(0, dp(8), 0, 0); description.setPadding(0, dp(6), 0, 0);
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
                artwork.fetch(media.logo, Artwork.THUMBNAIL, true, bitmap -> {
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
        LinearLayout root = column(); root.setGravity(Gravity.BOTTOM); root.setMinimumHeight(dp(214));
        root.setPadding(gutterStart, dp(4), gutterEnd, dp(18));
        TextView overline = Tv.oneLine(Tv.heading(context, kind(media), 14, R.color.tv_text_secondary));
        overline.setLetterSpacing(0.12f); overline.setAllCaps(true); root.addView(overline);
        ImageView logo = new ImageView(context);
        logo.setAdjustViewBounds(true); logo.setScaleType(ImageView.ScaleType.FIT_START); logo.setMaxHeight(dp(104)); logo.setMaxWidth(dp(460));
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); logo.setVisibility(View.GONE);
        root.addView(logo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = Tv.lines(Tv.bold(context, media.name, 46), 2); title.setMaxWidth(dp(680)); title.setPadding(0, dp(6), 0, 0);
        root.addView(title, wrap());
        if (media.logo != null) artwork.fetch(media.logo, Artwork.THUMBNAIL, true, bitmap -> { logo.setImageBitmap(bitmap); gone(logo, false); gone(title, true); });
        String facts = metaLine(media);
        if (!media.directors.isEmpty()) facts += (facts.isEmpty() ? "" : "  ·  ") + "Directed by " + String.join(", ", media.directors.subList(0, Math.min(2, media.directors.size())));
        if (!facts.isEmpty()) {
            TextView meta = Tv.oneLine(Tv.text(context, facts, 16, R.color.tv_text_secondary)); meta.setMaxWidth(dp(680)); meta.setPadding(0, dp(12), 0, 0);
            root.addView(meta, wrap());
        }
        if (!media.description.isEmpty()) {
            TextView about = Tv.lines(Tv.text(context, media.description, 17, R.color.tv_text), 4); about.setMaxWidth(dp(600)); about.setPadding(0, dp(10), 0, 0);
            root.addView(about, wrap());
        }
        if (!item.detail.isEmpty()) {
            TextView note = Tv.oneLine(Tv.text(context, item.detail, 15, R.color.tv_warning)); note.setPadding(0, dp(10), 0, 0); root.addView(note);
        }
        root.setLayoutParams(fill());
        return root;
    }

    private View intro(Item item) {
        LinearLayout root = column(); root.setGravity(Gravity.CENTER_HORIZONTAL); root.setPadding(gutterStart, dp(6), gutterEnd, dp(14));
        if (!item.meta.isEmpty()) {
            TextView overline = Tv.heading(context, item.meta, 14, R.color.tv_text_secondary); overline.setLetterSpacing(0.16f); overline.setAllCaps(true);
            overline.setGravity(Gravity.CENTER); root.addView(overline);
        }
        TextView title = Tv.bold(context, item.title, 44); title.setGravity(Gravity.CENTER); title.setPadding(0, dp(10), 0, 0); root.addView(title);
        TextView detail = Tv.text(context, item.detail, 18, R.color.tv_text_secondary); detail.setGravity(Gravity.CENTER);
        detail.setLineSpacing(0, 1.22f); detail.setMaxWidth(dp(680)); detail.setPadding(0, dp(12), 0, 0); root.addView(detail, wrap());
        root.setLayoutParams(fill());
        return root;
    }

    private View header(String title, String detail, int topDp) {
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.BOTTOM);
        row.setPadding(gutterStart, dp(topDp), gutterEnd, dp(2));
        // One line of text, so a heading such as "Generated series · Local fixtures" is never mistaken for a card's title.
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(title);
        if (detail != null && !detail.isEmpty()) {
            int start = text.length();
            text.append("   ").append(detail);
            int span = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
            text.setSpan(new android.text.style.AbsoluteSizeSpan(15, true), start, text.length(), span);
            text.setSpan(new android.text.style.ForegroundColorSpan(context.getColor(R.color.tv_text_tertiary)), start, text.length(), span);
            text.setSpan(new android.text.style.TypefaceSpan("sans-serif"), start, text.length(), span);
        }
        TextView heading = Tv.oneLine(Tv.heading(context, "", 21, R.color.tv_text)); heading.setText(text);
        heading.setContentDescription(detail == null || detail.isEmpty() ? title : title + ", " + detail);
        row.addView(heading);
        row.setLayoutParams(fill());
        return row;
    }

    // Shelves: a heading over a horizontally scrolling row of tiles.
    private View shelf(Item item) {
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false); root.setClipToPadding(false);
        root.addView(header(item.title, item.detail, 10));
        RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new Layout(context, RecyclerView.HORIZONTAL, 96));
        list.setClipToPadding(false); list.setClipChildren(false); list.setItemAnimator(null);
        list.setPadding(gutterStart, dp(12), gutterEnd, dp(10));
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
        row.setPadding(gutterStart, dp(12), gutterEnd, dp(12));
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
        final List<Item.Tile> tiles; final Item.Tile.Style style; final int widthDp, gapDp; final boolean matchSpan;
        TileAdapter(List<Item.Tile> tiles, Item.Tile.Style style, int widthDp) { this(tiles, style, widthDp, GAP, false); }
        TileAdapter(List<Item.Tile> tiles, Item.Tile.Style style, int widthDp, int gapDp) { this(tiles, style, widthDp, gapDp, false); }
        TileAdapter(List<Item.Tile> tiles, Item.Tile.Style style, int widthDp, int gapDp, boolean matchSpan) {
            this.tiles = tiles; this.style = style; this.widthDp = widthDp; this.gapDp = gapDp; this.matchSpan = matchSpan;
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
            box.setPadding(0, 0, position == tiles.size() - 1 ? 0 : dp(gapDp), 0);
            box.addView(tile);
        }
    }

    private RecyclerView.Adapter<Holder> skeletonCards(int count) {
        return new RecyclerView.Adapter<Holder>() {
            @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
                LinearLayout card = new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL);
                AspectFrame poster = new AspectFrame(context, 1.5f); poster.setBackgroundResource(R.drawable.tv_skeleton);
                card.addView(poster, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                View title = skeletonBar(76, 12);
                LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(dp(76), dp(12));
                titleParams.topMargin = dp(9); card.addView(title, titleParams);
                View subtitle = skeletonBar(48, 10);
                LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(dp(48), dp(10));
                subtitleParams.topMargin = dp(6); card.addView(subtitle, subtitleParams);
                return new Holder(card);
            }
            @Override public void onBindViewHolder(Holder holder, int position) { }
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
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
            rowParams.bottomMargin = dp(10); root.addView(row, rowParams);
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
            @Override public void getOutline(View view, Outline outline) { outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12)); }
        });
        TextView placeholder = Tv.lines(Tv.bold(context, tile.title, style == Item.Tile.Style.POSTER ? 17 : 20), 4);
        placeholder.setGravity(Gravity.CENTER); placeholder.setPadding(dp(12), 0, dp(12), 0);
        placeholder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(placeholder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        ImageView image = new ImageView(context); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        artwork.load(image, tile.image);
        if (tile.progress >= 0) {
            LinearLayout track = new LinearLayout(context); track.setBackgroundResource(R.drawable.tv_progress_track);
            View fill = new View(context); fill.setBackgroundResource(R.drawable.tv_progress_fill);
            track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0.02f, Math.min(1f, tile.progress))));
            track.addView(new View(context), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0f, 1f - tile.progress)));
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.BOTTOM);
            p.setMargins(dp(12), 0, dp(12), dp(12)); frame.addView(track, p);
        }
        if (tile.badge != 0) {
            ImageView badge = Tv.icon(context, tile.badge, 30, R.color.tv_text);
            badge.setBackgroundResource(R.drawable.tv_badge); badge.setPadding(dp(6), dp(6), dp(6), dp(6));
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP | Gravity.END);
            p.setMargins(0, dp(8), dp(8), 0); frame.addView(badge, p);
        }
        int fill = ViewGroup.LayoutParams.MATCH_PARENT;
        root.addView(frame, new LinearLayout.LayoutParams(fill, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = Tv.oneLine(Tv.heading(context, tile.title, 15, R.color.tv_caption));
        title.setDuplicateParentStateEnabled(true); title.setPadding(dp(2), dp(10), dp(2), 0);
        root.addView(title, new LinearLayout.LayoutParams(fill, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView subtitle = Tv.oneLine(Tv.text(context, tile.subtitle.isEmpty() ? " " : tile.subtitle, 13, R.color.tv_text_tertiary));
        subtitle.setPadding(dp(2), dp(4), dp(2), 0);
        root.addView(subtitle, new LinearLayout.LayoutParams(fill, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setLayoutParams(new LinearLayout.LayoutParams(width > 0 ? width : fill, ViewGroup.LayoutParams.WRAP_CONTENT));
        Tv.control(root, tile.key, tile.action, key -> { focused(key); feature(tile); }, 1.1f);
        View.OnFocusChangeListener lift = root.getOnFocusChangeListener();
        root.setOnFocusChangeListener((v, has) -> {
            lift.onFocusChange(v, has);
            frame.animate().translationZ(has ? dp(20) : 0).setDuration(180).start();
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
        row.setPadding(gutterStart, dp(12), gutterEnd, dp(12));
        for (int i = 0; i < item.actions.size(); i++) {
            Item.Action action = item.actions.get(i);
            View button = Tv.button(context, action, this::focused);
            if (i > 0) ((LinearLayout.LayoutParams) button.getLayoutParams()).leftMargin = dp(action.style == Item.Action.Style.TILE ? 22 : 14);
            row.addView(button);
            if (action.key.equals(restore)) button.post(button::requestFocus);
        }
        scroll.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setLayoutParams(fill());
        return scroll;
    }
    private View chips(Item item) {
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false); root.setClipToPadding(false);
        if (!item.title.isEmpty()) root.addView(header(item.title, "", 10));
        root.addView(actions(item, Gravity.START));
        root.setLayoutParams(fill());
        return root;
    }

    private View row(Item item) {
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setPadding(dp(20), dp(10), dp(16), dp(10));
        row.setContentDescription(item.value.isEmpty() ? item.title : item.title + ". " + item.value);
        if (item.icon != 0) {
            ImageView icon = Tv.icon(context, item.icon, 24, R.color.tv_on_glass);
            ((LinearLayout.LayoutParams) icon.getLayoutParams()).rightMargin = dp(16); row.addView(icon);
        }
        TextView title = Tv.oneLine(Tv.heading(context, item.title, 18, R.color.tv_on_glass)); title.setDuplicateParentStateEnabled(true);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!item.value.isEmpty()) {
            TextView value = Tv.oneLine(Tv.text(context, item.value, 16, R.color.tv_on_glass_secondary)); value.setDuplicateParentStateEnabled(true);
            value.setMaxWidth(dp(300)); value.setPadding(dp(12), 0, dp(4), 0); row.addView(value);
        }
        if (item.action != null) row.addView(Tv.icon(context, R.drawable.ic_tv_chevron, 22, R.color.tv_on_glass_secondary));
        return wrapRow(item, row, R.drawable.tv_row, 1.03f);
    }
    private View source(Item item) {
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(22), dp(14), dp(18), dp(14));
        row.setContentDescription(item.title + ". " + item.meta + ". " + item.value);
        LinearLayout text = new LinearLayout(context); text.setOrientation(LinearLayout.VERTICAL);
        TextView title = Tv.oneLine(Tv.heading(context, item.title, 18, R.color.tv_on_glass)); title.setDuplicateParentStateEnabled(true); text.addView(title);
        TextView meta = Tv.oneLine(Tv.text(context, item.meta, 14, R.color.tv_on_glass_secondary)); meta.setDuplicateParentStateEnabled(true);
        meta.setPadding(0, dp(6), 0, 0); text.addView(meta);
        if (!item.detail.isEmpty()) {
            TextView detail = Tv.lines(Tv.text(context, item.detail, 14, R.color.tv_on_glass_secondary), 2); detail.setDuplicateParentStateEnabled(true);
            detail.setPadding(0, dp(4), 0, 0); text.addView(detail);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout status = new LinearLayout(context); status.setGravity(Gravity.CENTER_VERTICAL); status.setDuplicateParentStateEnabled(true);
        status.addView(Tv.icon(context, item.muted ? R.drawable.ic_tv_info : R.drawable.ic_tv_check, 20, item.muted ? R.color.tv_on_glass_secondary : R.color.tv_on_glass));
        TextView value = Tv.lines(Tv.text(context, item.value, 14, R.color.tv_on_glass_secondary), 2); value.setDuplicateParentStateEnabled(true);
        value.setMaxWidth(dp(230)); value.setPadding(dp(8), 0, 0, 0); status.addView(value);
        row.addView(status);
        row.setAlpha(item.muted ? 0.62f : 1f);
        return wrapRow(item, row, R.drawable.tv_row, 1.02f);
    }
    private View field(Item item) {
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64)); row.setPadding(dp(22), 0, dp(22), 0);
        row.setContentDescription(item.title + ". " + item.value);
        ImageView icon = Tv.icon(context, R.drawable.ic_tv_search, 26, R.color.tv_on_glass);
        ((LinearLayout.LayoutParams) icon.getLayoutParams()).rightMargin = dp(16); row.addView(icon);
        TextView value = Tv.oneLine(Tv.text(context, item.value, 20, R.color.tv_on_glass_secondary)); value.setDuplicateParentStateEnabled(true);
        row.addView(value);
        return wrapRow(item, row, R.drawable.tv_field, 1.02f);
    }
    private View wrapRow(Item item, LinearLayout row, int background, float lift) {
        FrameLayout box = new FrameLayout(context); box.setClipChildren(false); box.setClipToPadding(false);
        box.setPadding(gutterStart, dp(6), gutterEnd, dp(6));
        GlassView glass = new GlassView(context);
        glass.setBackgroundResource(background);
        glass.setContentDescription(row.getContentDescription());
        row.setContentDescription(null);
        row.setDuplicateParentStateEnabled(true);
        glass.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(glass, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (item.action != null) {
            Tv.control(glass, item.key, item.action, this::focused, lift);
            if (item.key.equals(restore)) glass.post(glass::requestFocus);
        } else glass.setTag(item.key);
        box.setLayoutParams(fill());
        return box;
    }

    // States.
    private View message(Item item) {
        LinearLayout root = column(); root.setGravity(Gravity.CENTER_HORIZONTAL); root.setPadding(gutterStart, dp(44), gutterEnd, dp(12));
        if (item.icon != 0) {
            GlassView disc = new GlassView(context); disc.setBackgroundResource(R.drawable.tv_icon_disc);
            ImageView icon = Tv.icon(context, item.icon, 36, R.color.tv_text);
            disc.addView(icon, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));
            root.addView(disc, new LinearLayout.LayoutParams(dp(76), dp(76)));
        }
        TextView title = Tv.heading(context, item.title, 26, R.color.tv_text); title.setGravity(Gravity.CENTER); title.setPadding(0, dp(20), 0, 0);
        root.addView(title, wrap());
        if (!item.detail.isEmpty()) {
            TextView detail = Tv.text(context, item.detail, 17, R.color.tv_text_secondary); detail.setGravity(Gravity.CENTER);
            detail.setLineSpacing(0, 1.22f); detail.setMaxWidth(dp(600)); detail.setPadding(0, dp(10), 0, dp(6)); root.addView(detail, wrap());
        }
        if (!item.actions.isEmpty()) root.addView(actions(item, Gravity.CENTER_HORIZONTAL));
        root.setLayoutParams(fill());
        return root;
    }
    private View notice(Item item) {
        LinearLayout box = new LinearLayout(context); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL);
        box.setClipChildren(false); box.setClipToPadding(false);
        box.setPadding(dp(22), dp(14), dp(10), dp(14));
        box.addView(Tv.icon(context, item.icon == 0 ? R.drawable.ic_tv_info : item.icon, 28, R.color.tv_text));
        LinearLayout text = new LinearLayout(context); text.setOrientation(LinearLayout.VERTICAL); text.setPadding(dp(18), 0, dp(12), 0);
        text.addView(Tv.heading(context, item.title, 18, R.color.tv_text));
        if (!item.detail.isEmpty()) { TextView detail = Tv.lines(Tv.text(context, item.detail, 15, R.color.tv_text_secondary), 3); detail.setPadding(0, dp(6), 0, 0); text.addView(detail); }
        box.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!item.actions.isEmpty()) {
            Item inline = Item.actions(item.key + ":actions", item.actions);
            View buttons = actions(inline, Gravity.END);
            buttons.setPadding(0, 0, 0, 0);
            ((ViewGroup) ((ViewGroup) buttons).getChildAt(0)).setPadding(0, dp(6), dp(8), dp(6));
            box.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        GlassView glass = new GlassView(context); glass.setBackgroundResource(R.drawable.tv_tile_button);
        glass.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout wrap = new FrameLayout(context); wrap.setClipChildren(false); wrap.setClipToPadding(false);
        wrap.setPadding(gutterStart, dp(12), gutterEnd, dp(8));
        wrap.addView(glass, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.setLayoutParams(fill());
        return wrap;
    }
    private View text(Item item) {
        LinearLayout root = column(); root.setPadding(gutterStart, dp(14), gutterEnd, dp(4));
        if (!item.title.isEmpty()) {
            TextView title = Tv.heading(context, item.title, 13, R.color.tv_text_tertiary); title.setLetterSpacing(0.12f); title.setAllCaps(true);
            root.addView(title);
        }
        TextView detail = Tv.text(context, item.detail, 16, R.color.tv_text_secondary); detail.setLineSpacing(0, 1.22f); detail.setPadding(0, dp(6), 0, 0);
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
        root.addView(keyboardPanel, new LinearLayout.LayoutParams(dp(444), ViewGroup.LayoutParams.MATCH_PARENT));
        keyboardPanel.addView(keyboardKeys(item), wrap());

        View divider = new View(context); divider.setBackgroundColor(context.getColor(R.color.tv_hairline));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(1), dp(290));
        dividerParams.setMargins(dp(14), 0, dp(18), 0); root.addView(divider, dividerParams);

        LinearLayout results = new LinearLayout(context); results.setOrientation(LinearLayout.VERTICAL);
        results.setClipChildren(false); results.setClipToPadding(false);
        results.setPadding(0, dp(8), 0, 0);
        root.addView(results, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout heading = new LinearLayout(context); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = Tv.heading(context, "Results", 21, R.color.tv_text);
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
            list.setLayoutManager(new GridLayoutManager(context, 3)); list.setItemAnimator(null);
            list.setClipChildren(false); list.setClipToPadding(false); list.setPadding(0, dp(8), 0, dp(8));
            list.setVerticalScrollBarEnabled(false); list.setNestedScrollingEnabled(true);
            if (item.loading) list.setAdapter(skeletonCards(6));
            else list.setAdapter(new TileAdapter(item.tiles, Item.Tile.Style.POSTER, 0, 8, true));
            results.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
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
            String[] first = symbols[0] ? new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"}
                    : new String[]{"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"};
            String[] second = symbols[0] ? new String[]{"@", "#", "$", "%", "&", "-", "+", "(", ")"}
                    : new String[]{"A", "S", "D", "F", "G", "H", "J", "K", "L"};
            String[] third = symbols[0] ? new String[]{".", ",", "?", "!", "'", ":", ";", "/"}
                    : new String[]{"Z", "X", "C", "V", "B", "N", "M"};
            addKeyboardRow(keys, item, first, query);
            addKeyboardRow(keys, item, second, query);
            String[] last = new String[third.length + 1];
            System.arraycopy(third, 0, last, 0, third.length); last[last.length - 1] = "⌫";
            addKeyboardRow(keys, item, last, query);
            LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER);
            keys.addView(row, wrap());
            addKeyboardButton(row, item, symbols[0] ? "ABC" : "123", symbols[0] ? "Switch to letters" : "Switch to numbers and symbols", 50,
                    () -> {
                        symbols[0] = !symbols[0]; rebuild[0].run();
                        keys.post(() -> {
                            View firstKey = keys.findViewWithTag(item.key + ":" + (symbols[0] ? "1" : "Q"));
                            if (firstKey != null) firstKey.requestFocus();
                        });
                    });
            addKeyboardButton(row, item, "Space", "Insert a space", 72, () -> {
                if (query.length() < 120) query.append(' ');
                if (item.keyboardInput != null) item.keyboardInput.accept(query.toString());
            });
            addKeyboardButton(row, item, "Clear", "Clear search", 58, () -> {
                query.setLength(0);
                if (item.keyboardInput != null) item.keyboardInput.accept("");
            });
            addKeyboardButton(row, item, "Search", "Search", 72, () -> {
                if (item.keyboardSubmit != null) item.keyboardSubmit.run();
            });
        };
        rebuild[0].run();
        return keys;
    }

    private void addKeyboardRow(LinearLayout keys, Item item, String[] labels, StringBuilder query) {
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER);
        keys.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        for (String label : labels) {
            boolean backspace = label.equals("⌫");
            addKeyboardButton(row, item, label, backspace ? "Delete last character" : "Type " + label,
                    backspace ? 54 : 38, () -> {
                        if (backspace) {
                            if (query.length() > 0) query.deleteCharAt(query.length() - 1);
                        } else if (query.length() < 120) query.append(label);
                        if (item.keyboardInput != null) item.keyboardInput.accept(query.toString());
                    });
        }
    }

    private void addKeyboardButton(LinearLayout row, Item item, String label, String description, int width, Runnable action) {
        TextView key = Tv.heading(context, label, label.length() == 1 ? 16 : 12, R.color.tv_on_glass);
        key.setGravity(Gravity.CENTER);
        key.setSingleLine(true);
        key.setContentDescription(description);
        key.setBackgroundResource(label.equals("Search") ? R.drawable.tv_chip : R.drawable.tv_button);
        key.setDefaultFocusHighlightEnabled(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(width), dp(40));
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
        root.addView(spinner, new LinearLayout.LayoutParams(dp(56), dp(56)));
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
    }
}
