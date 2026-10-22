package tv.daniil.stream;

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
    void openTitle(Title title, String video);
    void sources(Title title, String video);
    void play(Title title, String video, String sourceKey);
    void edit(String title, String hint, String initial, Consumer<String> accept);
    void choose(String title, String[] options, Consumer<Integer> accept);
    void alert(String title, String message);
    void openWebsite(String address);
    /** Runs {@code task} on a worker, then rebuilds the current route. */
    void mutate(Callable<?> task);
    /** Runs {@code task} on a worker, then {@code after} on the main thread. */
    void then(Callable<?> task, Runnable after);
    /** Installs the starter add-ons onboarding listed (the viewer's acceptance), then opens Home. */
    void startWatching(java.util.List<String> addons);
    void addAddon();
    void previewAddon(String address);
    void importAddress();
    void importFile();
    void notices();
}
