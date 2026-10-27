package tv.stream.app.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Layouts offered by the on-screen keyboard; persisted values are language tags, never display labels. */
public final class KeyboardLanguages {
    public static final String[] CODES = {"en", "fr", "de", "es", "it", "pt", "ru", "uk"};
    public static final String[] NAMES = {"English", "Français", "Deutsch", "Español", "Italiano", "Português", "Русский", "Українська"};
    private KeyboardLanguages() {}
    public static List<String> parse(String value) {
        List<String> result = new ArrayList<>();
        for (String code : value.split(",")) if (Arrays.asList(CODES).contains(code) && !result.contains(code)) result.add(code);
        if (result.isEmpty()) result.add("en");
        return result;
    }
    public static String name(String code) {
        int index = Arrays.asList(CODES).indexOf(code); return index >= 0 ? NAMES[index] : NAMES[0];
    }
    public static String alphabet(String code) {
        if (code.equals("ru")) return "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
        if (code.equals("uk")) return "абвгґдеєжзиіїйклмнопрстуфхцчшщьюя";
        return "abcdefghijklmnopqrstuvwxyz";
    }
    public static String accents(String code) {
        switch (code) {
            case "fr": return "àâæçéèêëîïôœùûüÿ";
            case "de": return "äöüß";
            case "es": return "áéíñóúü¿¡";
            case "it": return "àèéìòù";
            case "pt": return "áàâãçéêíóôõúü";
            default: return "àáâäãåçèéêëíîïñóôöõøùúûüßæ";
        }
    }
}
