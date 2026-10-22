package tv.daniil.stream.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import tv.daniil.stream.R;
import tv.daniil.stream.addon.Title;

/** Recycled TV rows/cards; every action shares real click, touch, keyboard and accessibility behavior. */
public final class Rows extends RecyclerView.Adapter<Rows.Holder> {
    public static final class Row {
        public final String key, label, detail;
        public final Runnable action;
        public final Title hero;
        public final List<Row> cards;
        private Row(String key, String label, String detail, Runnable action, Title hero, List<Row> cards) {
            this.key = key; this.label = label; this.detail = detail; this.action = action; this.hero = hero; this.cards = cards;
        }
        public static Row text(String label, String detail) { return new Row("text:" + label, label, detail, null, null, null); }
        public static Row action(String key, String label, String detail, Runnable action) { return new Row(key, label, detail, action, null, null); }
        public static Row hero(Title title) { return new Row("hero", title.name, title.description, null, title, null); }
        public static Row card(String key, Title title, String detail, Runnable action) { return new Row(key, title.name, detail, action, title, null); }
        public static Row carousel(String key, String label, List<Row> cards) { return new Row(key, label, "", null, null, cards); }
    }
    static final class Holder extends RecyclerView.ViewHolder { Holder(View view) { super(view); } }
    private final Context context;
    private final Artwork artwork;
    private final Consumer<String> focus;
    private final boolean cards;
    private List<Row> rows = Collections.emptyList();
    private String restoredFocus = "";
    public Rows(Context context, Artwork artwork, Consumer<String> focus) { this(context, artwork, focus, false); }
    private Rows(Context context, Artwork artwork, Consumer<String> focus, boolean cards) {
        this.context = context; this.artwork = artwork; this.focus = focus; this.cards = cards; setHasStableIds(true);
    }
    public void set(List<Row> value) { rows = new ArrayList<>(value); notifyDataSetChanged(); }
    public void restoreFocus(String key) { restoredFocus = key; }
    public int position(String key) {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i); if (row.key.equals(key)) return i;
            if (row.cards != null) for (Row card : row.cards) if (card.key.equals(key)) return i;
        }
        return -1;
    }
    @Override public int getItemCount() { return rows.size(); }
    @Override public long getItemId(int position) {
        long hash = 0xcbf29ce484222325L; for (char c : rows.get(position).key.toCharArray()) hash = (hash ^ c) * 0x100000001b3L; return hash;
    }
    @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
        LinearLayout box = new LinearLayout(context); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        RecyclerView.LayoutParams p = new RecyclerView.LayoutParams(cards ? dp(150) : -1, cards ? dp(220) : -2);
        p.setMargins(0, 0, dp(10), dp(10)); box.setLayoutParams(p);
        return new Holder(box);
    }
    @Override public void onBindViewHolder(Holder holder, int position) {
        Row row = rows.get(position); LinearLayout box = (LinearLayout) holder.itemView;
        box.removeAllViews(); box.setTag(row.key); box.setFocusable(row.action != null); box.setClickable(row.action != null);
        box.setFocusableInTouchMode(row.action != null);
        box.setOnTouchListener(row.action == null ? null : (v, event) -> { if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) v.requestFocus(); return false; });
        box.setBackgroundResource(row.action != null ? R.drawable.tv_focus : android.R.color.transparent);
        box.setMinimumHeight(dp(56)); box.setContentDescription(row.label + (row.detail.isEmpty() ? "" : ". " + row.detail));
        box.setOnClickListener(row.action == null ? null : v -> { focus.accept(row.key); row.action.run(); });
        box.setOnFocusChangeListener((v, focused) -> { if (focused) focus.accept(row.key); });
        if (row.hero != null && (cards || row.hero.background != null || row.hero.poster != null)) {
            ImageView image = new ImageView(context); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            image.setBackgroundColor(0xff142029); box.addView(image, new LinearLayout.LayoutParams(-1, dp(cards ? 142 : 180)));
            artwork.load(image, cards ? row.hero.poster : row.hero.background == null ? row.hero.poster : row.hero.background);
        }
        TextView label = text(context, row.label, cards ? 16 : row.action == null ? 23 : 19, 0xfff4f7f6);
        label.setMaxLines(cards ? 2 : 3); box.addView(label);
        if (!row.detail.isEmpty()) { TextView detail = text(context, row.detail, cards ? 12 : 15, 0xffa4b7bf); detail.setMaxLines(cards ? 1 : 12); box.addView(detail); }
        if (row.cards != null) {
            RecyclerView list = new RecyclerView(context); list.setLayoutManager(new LinearLayoutManager(context, RecyclerView.HORIZONTAL, false));
            Rows adapter = new Rows(context, artwork, focus, true); adapter.set(row.cards); list.setAdapter(adapter);
            list.setClipToPadding(false); box.addView(list, new LinearLayout.LayoutParams(-1, dp(232)));
            int positionInRow = adapter.position(restoredFocus);
            if (positionInRow >= 0) {
                list.scrollToPosition(positionInRow);
                String key = restoredFocus; list.post(() -> { View target = list.findViewWithTag(key); if (target != null) target.requestFocus(); });
            }
        }
    }
    public int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    public static TextView text(Context context, String text, int size, int color) {
        TextView view = new TextView(context); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, 4, 0, 4); return view;
    }
    public static TextView button(Context context, String label, Runnable action) {
        TextView view = text(context, label, 17, Color.WHITE); view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(16, 10, 10, 10); view.setMinHeight(Math.round(48 * context.getResources().getDisplayMetrics().density));
        view.setFocusable(true); view.setClickable(true); view.setContentDescription(label); view.setBackgroundResource(R.drawable.tv_focus);
        view.setFocusableInTouchMode(true);
        view.setOnTouchListener((v, event) -> { if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) v.requestFocus(); return false; });
        view.setOnClickListener(v -> action.run()); return view;
    }
}
