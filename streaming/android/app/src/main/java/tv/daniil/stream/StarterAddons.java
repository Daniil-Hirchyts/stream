package tv.daniil.stream;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import tv.daniil.stream.addon.AddonUrl;
import tv.daniil.stream.addon.BuiltinAddons;

/** The add-ons the store's starter page installs together: catalogues, free classic films and subtitles. */
final class StarterAddons {
    private StarterAddons() {}
    static final String OPENSUBTITLES = "https://opensubtitles-v3.strem.io/manifest.json";
    /** The store route value for the starter page; not an add-on address. */
    static final String PACK = "starter-pack";
    static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(AddonUrl.CINEMETA, BuiltinAddons.INTERNET_ARCHIVE, OPENSUBTITLES));

    /** A short name and what it adds, for the onboarding list; unknown (fixture) addresses show their host. */
    static String[] describe(String url) {
        if (url.equals(AddonUrl.CINEMETA)) return new String[]{"Cinemeta", "Films and series"};
        if (url.equals(BuiltinAddons.INTERNET_ARCHIVE)) return new String[]{"Internet Archive", "Free classic films"};
        if (url.equals(OPENSUBTITLES)) return new String[]{"OpenSubtitles", "Subtitles"};
        return new String[]{android.net.Uri.parse(url).getHost() == null ? url : android.net.Uri.parse(url).getHost(), "Add-on"};
    }
}
