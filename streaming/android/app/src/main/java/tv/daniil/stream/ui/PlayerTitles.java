package tv.daniil.stream.ui;

import android.app.Activity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;
import tv.daniil.stream.R;
import tv.daniil.stream.addon.Title;

/** One optional logo request shared by the loading screen and playback header. Never blocks playback. */
public final class PlayerTitles implements AutoCloseable {
    private final Artwork artwork;
    private final ImageView loading, controls;
    private final TextView loadingFallback, controlsFallback;
    private String titleKey = "", logo;

    public PlayerTitles(Activity activity) {
        artwork = new Artwork(new File(activity.getCacheDir(), "artwork"));
        loading = activity.findViewById(R.id.loading_title_logo);
        controls = activity.findViewById(R.id.controls_title_logo);
        loadingFallback = activity.findViewById(R.id.loading_title);
        controlsFallback = activity.findViewById(R.id.controls_title);
    }

    public void bind(Title title) {
        if (!title.key().equals(titleKey)) {
            titleKey = title.key(); logo = null;
            for (ImageView image : new ImageView[]{loading, controls}) { image.setImageDrawable(null); image.setVisibility(View.GONE); }
            loadingFallback.setVisibility(View.VISIBLE); controlsFallback.setVisibility(View.VISIBLE);
        }
        loading.setContentDescription(title.name); controls.setContentDescription(title.name);
        // A thinner metadata response must not discard a valid logo already supplied by the title page.
        if (title.logo == null || title.logo.equals(logo)) return;
        logo = title.logo; String requested = logo, key = titleKey;
        artwork.fetch(requested, Artwork.LOGO, true, bitmap -> {
            if (!key.equals(titleKey) || !requested.equals(logo)) return;
            loading.setImageBitmap(bitmap); controls.setImageBitmap(bitmap);
            loading.setVisibility(View.VISIBLE); controls.setVisibility(View.VISIBLE);
            loadingFallback.setVisibility(View.GONE); controlsFallback.setVisibility(View.GONE);
        });
    }

    @Override public void close() { artwork.close(); }
}
