package tv.stream.app;

import androidx.annotation.Nullable;
import android.view.View;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import tv.stream.app.addon.Title;

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
    /** A one-line text entry; {@code description} explains it under the title (empty for none). */
    void edit(String title, String description, String hint, String initial, Consumer<String> accept);
    default void edit(String title, String hint, String initial, Consumer<String> accept) { edit(title, "", hint, initial, accept); }
    void choose(String title, String[] options, Consumer<Integer> accept);
    void options(String title, Consumer<tv.stream.app.ui.Tv.Alert> contents);
    default void chooseWithPreview(String title, @Nullable View header, String[] options, int selected,
                                   Consumer<Integer> accept, @Nullable Consumer<Integer> onPreview) {
        choose(title, options, accept);
    }
    default void previewSubtitle(tv.stream.playback.SubtitleStyle.Setting setting, int index) {}
    void alert(String title, String message);
    void openWebsite(String address);
    /** Runs {@code task} on a worker, then rebuilds the current route. */
    void mutate(Callable<?> task);
    void mutateEpisode(Callable<?> task, Route route, int season, String episode);
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
