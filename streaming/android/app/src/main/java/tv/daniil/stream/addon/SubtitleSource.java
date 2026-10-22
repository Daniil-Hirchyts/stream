package tv.daniil.stream.addon;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SubtitleSource {
    public final String provider, id, url, language;
    private SubtitleSource(String provider, String id, String url, String language) {
        this.provider = provider; this.id = id; this.url = url; this.language = language;
    }
    public static List<SubtitleSource> list(String provider, JSONObject response) {
        List<SubtitleSource> out = new ArrayList<>();
        for (JSONObject o : Json.objects(response, "subtitles")) {
            String url = Json.webUrl(Json.string(o, "url")), language = Json.string(o, "lang");
            if (url != null && language != null) out.add(new SubtitleSource(provider, Json.string(o, "id", url), url, language));
        }
        return Collections.unmodifiableList(out);
    }
}
