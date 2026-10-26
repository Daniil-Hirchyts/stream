package tv.daniil.stream;

import androidx.annotation.Nullable;
import android.view.View;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import tv.daniil.stream.addon.Title;

/** What screen actions may do. Called on the main thread; implemented by {@link MainActivity}. */
interface Navigator {
    void push(Route next);
    void root(String screen);
    void search();
    void searchInput(String value);
    void openImports();
    void back();
    /** Rebuilds the current route. */
    void refresh();
    /** Route parameters changed; reuse fresh provider data rather than forcing a network refresh. */
    default void changed() { refresh(); }
    void openTitle(Title title, String video);
    void sources(Title title, String video);
    void play(Title title, String video, String sourceKey);
    void edit(String title, String hint, String initial, Consumer<String> accept);
    void choose(String title, String[] options, Consumer<Integer> accept);
    void options(String title, Consumer<tv.daniil.stream.ui.Tv.Alert> contents);
    default void chooseWithPreview(String title, @Nullable View header, String[] options, int selected,
                                   Consumer<Integer> accept, @Nullable Consumer<Integer> onPreview) {
        choose(title, options, accept);
    }
    default void previewSubtitle(tv.daniil.playback.SubtitleStyle.Setting setting, int index) {}
    void alert(String title, String message);
    void openWebsite(String address);
    /** Runs {@code task} on a worker, then rebuilds the current route. */
    void mutate(Callable<?> task);
    /** Runs {@code task} on a worker, then {@code after} on the main thread. */
    void then(Callable<?> task, Runnable after);
    /** Installs the starter add-ons the store's starter page listed (the viewer's acceptance), then opens Home. */
    void installStarters(java.util.List<String> addons);
    void addAddon();
    void previewAddon(String address);
    void importAddress();
    void importFile();
    void notices();
    default void errorReport() {}
    default void keyboardLanguage(String language) {}
}
