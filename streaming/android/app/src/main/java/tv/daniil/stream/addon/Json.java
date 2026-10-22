package tv.daniil.stream.addon;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Lenient reads for add-on responses: a wrong type reads as absent rather than failing the whole response. */
final class Json {
    private Json() {}

    @Nullable static String string(JSONObject o, String key) {
        Object v = o.opt(key);
        if (!(v instanceof String)) return null;
        String s = (String) v;
        return s.trim().isEmpty() ? null : s;
    }

    static String string(JSONObject o, String key, String fallback) {
        String s = string(o, key);
        return s == null ? fallback : s;
    }

    static boolean bool(@Nullable JSONObject o, String key) {
        return o != null && o.opt(key) instanceof Boolean && o.optBoolean(key);
    }

    /** A non-negative integer, or -1 when absent; anything else is reported as -2 so callers can reject it. */
    static long index(JSONObject o, String key) {
        if (!o.has(key)) return -1;
        if (o.isNull(key)) return -2;
        Object v = o.opt(key);
        if (v instanceof Integer || v instanceof Long) {
            long n = ((Number) v).longValue();
            return n >= 0 ? n : -2;
        }
        return -2;
    }

    static long number(JSONObject o, String key, long fallback) {
        Object v = o.opt(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException ignored) { /* fallback */ }
        }
        return fallback;
    }

    /** Display text: some providers send HTML entities ("O&apos;Dea", "Tom &amp; Jerry"); show the characters. */
    static String text(JSONObject o, String key, String fallback) { return entities(string(o, key, fallback)); }
    static List<String> texts(JSONObject o, String key) {
        List<String> raw = strings(o, key);
        List<String> out = new ArrayList<>(raw.size());
        for (String value : raw) out.add(entities(value));
        return Collections.unmodifiableList(out);
    }
    static String entities(String value) {
        if (value == null || value.indexOf('&') < 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int end = c == '&' ? value.indexOf(';', i) : -1;
            if (end < 0 || end - i > 10) { out.append(c); continue; }
            String name = value.substring(i + 1, end);
            int code = -1;
            switch (name) {
                case "amp": code = '&'; break;
                case "lt": code = '<'; break;
                case "gt": code = '>'; break;
                case "quot": code = '"'; break;
                case "apos": code = '\''; break;
                case "nbsp": code = ' '; break;
                default:
                    try {
                        if (name.startsWith("#x") || name.startsWith("#X")) code = Integer.parseInt(name.substring(2), 16);
                        else if (name.startsWith("#")) code = Integer.parseInt(name.substring(1));
                    } catch (NumberFormatException ignored) { /* not an entity */ }
            }
            if (code <= 0 || code > Character.MAX_CODE_POINT) { out.append(c); continue; }
            out.appendCodePoint(code); i = end;
        }
        return out.toString();
    }

    /** The strings of an array, skipping anything else; empty when absent. */
    static List<String> strings(JSONObject o, String key) {
        JSONArray a = o.optJSONArray(key);
        if (a == null) return Collections.emptyList();
        List<String> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            Object v = a.opt(i);
            if (v instanceof String && !((String) v).trim().isEmpty()) out.add((String) v);
        }
        return Collections.unmodifiableList(out);
    }

    /** The objects of an array, skipping anything else. */
    static List<JSONObject> objects(JSONObject o, String key) {
        JSONArray a = o.optJSONArray(key);
        if (a == null) return Collections.emptyList();
        List<JSONObject> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            JSONObject item = a.optJSONObject(i);
            if (item != null) out.add(item);
        }
        return out;
    }

    /** http(s) only: add-ons must never steer the player to other schemes. */
    @Nullable static String webUrl(@Nullable String url) {
        if (url == null) return null;
        try { AddonUrl.web(url); return url; }
        catch (AddonException e) { return null; }
    }
}
