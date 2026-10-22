package tv.daniil.stream;

import android.os.Parcelable;
import androidx.annotation.Nullable;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import tv.daniil.stream.addon.Title;

/** One entry of the browse back stack, saved across activity recreation. */
final class Route {
    final String screen;
    /** The key of the focused view, restored when returning to this route. */
    String focus = "";
    /** Screen-specific: search text, add-on id or catalogue identity. */
    String value = "";
    boolean searchSubmitted;
    /** Selected provider video (episode) ID on Details and Sources. */
    String video = "";
    @Nullable Title title;
    int season = -1, page;
    @Nullable Parcelable scroll;
    final Map<String, String> extras = new LinkedHashMap<>();

    Route(String screen) { this.screen = screen; }
    static Route of(String screen, @Nullable Title title, String video) {
        Route route = new Route(screen); route.title = title; route.video = video == null ? "" : video; return route;
    }

    JSONObject save() throws JSONException {
        JSONObject data = new JSONObject().put("screen", screen).put("value", value).put("video", video).put("season", season)
                .put("page", page).put("focus", focus).put("searchSubmitted", searchSubmitted).put("extras", new JSONObject(extras));
        if (title != null) data.put("title", new JSONObject().put("provider", title.provider).put("id", title.id).put("type", title.type).put("name", title.name));
        return data;
    }
    static Route restore(JSONObject data) throws JSONException {
        Route route = new Route(data.getString("screen"));
        route.value = data.optString("value"); route.video = data.optString("video"); route.focus = data.optString("focus");
        route.season = data.optInt("season", -1); route.page = data.optInt("page"); route.searchSubmitted = data.optBoolean("searchSubmitted");
        JSONObject extras = data.optJSONObject("extras");
        if (extras != null) for (Iterator<String> keys = extras.keys(); keys.hasNext(); ) { String key = keys.next(); route.extras.put(key, extras.getString(key)); }
        JSONObject title = data.optJSONObject("title");
        if (title != null) route.title = Title.parse(title.getString("provider"), title);
        return route;
    }
}
