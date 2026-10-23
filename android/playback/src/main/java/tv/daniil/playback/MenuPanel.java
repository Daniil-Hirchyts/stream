package tv.daniil.playback;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The options drawer, opened by clicking Options or using the remote's Menu button. Pages stack: OK or right
 * opens a page or picks a value, left or Back goes up a level. It keeps its own row focus, so it never competes
 * with the player for view focus.
 */
public final class MenuPanel {
    interface Listener {
        void onMenuToggled(boolean open);
        void onMenuPageChanged();
    }

    /** A page is rebuilt each time it's shown or refreshed, so its values are always current. */
    public interface Page {
        String title();
        List<Item> items();
        /** Style pages show a sample subtitle line while open. */
        default boolean previewsSubtitles() {
            return false;
        }

        static Page of(String title, boolean previewsSubtitles, Supplier<List<Item>> items) {
            return new Page() {
                @Override public String title() { return title; }
                @Override public List<Item> items() { return items.get(); }
                @Override public boolean previewsSubtitles() { return previewsSubtitles; }
            };
        }
    }

    public static final class Item {
        private static final int PAGE = 0, CHOICE = 1, ACTION = 2;

        final String label;
        @Nullable final String value;
        final boolean checked;
        private final int kind;
        private final Runnable action;
        @Nullable private Runnable focusAction;

        private Item(int kind, String label, @Nullable String value, boolean checked, Runnable action) {
            this.kind = kind;
            this.label = label;
            this.value = value;
            this.checked = checked;
            this.action = action;
        }

        /** A row that opens another page, showing its current value; a current row gets the focus first. */
        public static Item page(String label, @Nullable String value, boolean current, Runnable open) {
            return new Item(PAGE, label, value, current, open);
        }

        /** One value of a list; the current one is ticked. */
        public static Item choice(String label, @Nullable String value, boolean checked, Runnable choose) {
            return new Item(CHOICE, label, value, checked, choose);
        }

        public static Item action(String label, @Nullable String value, Runnable run) {
            return new Item(ACTION, label, value, false, run);
        }

        /** Optional immediate feedback while moving through appearance choices, without pressing OK. */
        public Item onFocus(Runnable run) {
            focusAction = run;
            return this;
        }
    }

    private static final class Entry {
        final Page page;
        List<Item> items;
        int focus;

        Entry(Page page) {
            this.page = page;
            items = page.items();
            focus = 0;
            for (int i = 0; i < items.size(); i++) if (items.get(i).checked) focus = i;
        }
    }

    private static final int SLIDE_MS = 220;

    private final Activity activity;
    private final Listener listener;
    private final View panel, back;
    private final TextView title;
    private final ScrollView scroll;
    private final ViewGroup rows;
    private final LayoutInflater inflater;
    private final List<Entry> stack = new ArrayList<>();
    private boolean open;

    MenuPanel(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        panel = activity.findViewById(R.id.menu);
        back = activity.findViewById(R.id.menu_back);
        title = activity.findViewById(R.id.menu_title);
        scroll = activity.findViewById(R.id.menu_scroll);
        rows = activity.findViewById(R.id.menu_rows);
        inflater = activity.getLayoutInflater();
        // Keep a valid focus target in touch mode too. Otherwise Android consumes
        // the first D-pad OK after a pointer click just to restore keyboard focus.
        panel.setFocusableInTouchMode(true);
        Ui.action(back, this::pop);
        Ui.action(activity.findViewById(R.id.menu_go_back), this::pop);
        Ui.action(activity.findViewById(R.id.menu_close), this::close);
    }

    public boolean isOpen() {
        return open;
    }

    boolean previewsSubtitles() {
        return open && !stack.isEmpty() && top().page.previewsSubtitles();
    }

    void open(Page root) {
        stack.clear();
        stack.add(new Entry(root));
        render(true);
        if (open) return;
        open = true;
        panel.animate().cancel();
        panel.setTranslationX(activity.getResources().getDimension(R.dimen.panel_width));
        panel.setAlpha(0f);
        panel.setVisibility(View.VISIBLE);
        panel.requestFocus();
        panel.animate().translationX(0f).alpha(1f).setDuration(SLIDE_MS)
                .setInterpolator(new DecelerateInterpolator()).start();
        listener.onMenuToggled(true);
    }

    public void push(Page page) {
        stack.add(new Entry(page));
        render(true);
        listener.onMenuPageChanged();
    }

    /** Goes up a level, or closes the menu from its first page. */
    public void pop() {
        if (stack.size() <= 1) {
            close();
            return;
        }
        stack.remove(stack.size() - 1);
        refresh();
        listener.onMenuPageChanged();
    }

    public void close() {
        if (!open) return;
        open = false;
        panel.animate().cancel();
        panel.animate().translationX(panel.getWidth()).alpha(0f).setDuration(SLIDE_MS)
                .withEndAction(() -> panel.setVisibility(View.GONE)).start();
        listener.onMenuToggled(false);
    }

    /** Rebuilds the visible page, for example after tracks or the episode changed. */
    public void refresh() {
        if (!open || stack.isEmpty()) return;
        Entry entry = top();
        entry.items = entry.page.items();
        entry.focus = Math.max(0, Math.min(entry.items.size() - 1, entry.focus));
        render(false);
    }

    boolean onKeyDown(int keyCode) {
        Entry entry = top();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: move(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!entry.items.isEmpty() && entry.items.get(entry.focus).kind == Item.PAGE) activate();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: activate(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_BACK: pop(); return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_CAPTIONS: close(); return true;
            default: return false;
        }
    }

    private Entry top() {
        return stack.get(stack.size() - 1);
    }

    private void move(int delta) {
        Entry entry = top();
        int next = Math.max(0, Math.min(entry.items.size() - 1, entry.focus + delta));
        if (next == entry.focus) return;
        entry.focus = next;
        bindRows();
        reveal(false);
        previewFocused();
    }

    private void previewFocused() {
        Entry entry = top();
        if (!entry.items.isEmpty() && entry.items.get(entry.focus).focusAction != null)
            entry.items.get(entry.focus).focusAction.run();
    }

    private void activate() {
        Entry entry = top();
        if (entry.items.isEmpty()) return;
        entry.items.get(entry.focus).action.run();
    }

    private void render(boolean jump) {
        Entry entry = top();
        title.setText(entry.page.title());
        back.setVisibility(View.VISIBLE);
        while (rows.getChildCount() < entry.items.size()) {
            rows.addView(inflater.inflate(R.layout.row_menu, rows, false));
        }
        while (rows.getChildCount() > entry.items.size()) rows.removeViewAt(rows.getChildCount() - 1);
        bindRows();
        if (jump) scroll.scrollTo(0, 0);
        reveal(jump);
    }

    private void bindRows() {
        Entry entry = top();
        for (int i = 0; i < entry.items.size(); i++) {
            Item item = entry.items.get(i);
            View row = rows.getChildAt(i);
            final int index = i;
            Ui.action(row, () -> {
                // Bind the current entry each render; rows are reused when navigating pages.
                entry.focus = index;
                bindRows();
                item.action.run();
            });
            row.setOnHoverListener((view, event) -> {
                if (item.focusAction != null && event.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER && entry.focus != index) {
                    entry.focus = index;
                    bindRows();
                    previewFocused();
                }
                return false;
            });
            ((TextView) row.findViewById(R.id.row_label)).setText(item.label);
            TextView value = row.findViewById(R.id.row_value);
            value.setText(item.value == null ? "" : item.value);
            value.setVisibility(item.value == null || item.value.isEmpty() ? View.GONE : View.VISIBLE);
            ImageView mark = row.findViewById(R.id.row_icon);
            mark.setImageResource(item.kind == Item.PAGE ? R.drawable.ic_chevron_right : R.drawable.ic_check);
            boolean ticked = item.kind == Item.CHOICE && item.checked;
            mark.setVisibility(item.kind == Item.PAGE || ticked ? View.VISIBLE : View.INVISIBLE);
            mark.setActivated(ticked);
            // Selection reaches the label and icon too, which recolour through their selectors.
            row.setSelected(i == entry.focus);
        }
    }

    /** Scrolls the focused row into view, at once on a new page and smoothly while moving. */
    private void reveal(boolean jump) {
        scroll.post(() -> {
            if (!open || stack.isEmpty()) return;
            View row = rows.getChildAt(top().focus);
            if (row == null) return;
            if (row.getHeight() == 0 || row.isLayoutRequested()) {
                // New rows aren't measured yet; try again after the next layout pass.
                scroll.post(() -> reveal(jump));
                return;
            }
            int margin = row.getHeight();
            int y = scroll.getScrollY(), height = scroll.getHeight();
            int target = y;
            if (row.getTop() - margin < y) target = Math.max(0, row.getTop() - margin);
            else if (row.getBottom() + margin > y + height) target = row.getBottom() + margin - height;
            if (target == y) return;
            if (jump) scroll.scrollTo(0, target);
            else scroll.smoothScrollTo(0, target);
        });
    }
}
