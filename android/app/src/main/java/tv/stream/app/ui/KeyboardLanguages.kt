package tv.stream.app.ui

import java.util.Arrays

/** Layouts offered by the on-screen keyboard; persisted values are language tags, never display labels. */
object KeyboardLanguages {
    @JvmField val CODES = arrayOf("en", "fr", "de", "es", "it", "pt", "ru", "uk")
    @JvmField val NAMES = arrayOf("English", "Français", "Deutsch", "Español", "Italiano", "Português", "Русский", "Українська")
    @JvmStatic fun parse(value: String): MutableList<String> {
        val result: MutableList<String> = ArrayList()
        for (code in value.split(",")) if (Arrays.asList(*CODES).contains(code) && !result.contains(code)) result.add(code)
        if (result.isEmpty()) result.add("en")
        return result
    }
    @JvmStatic fun name(code: String?): String {
        val index = Arrays.asList(*CODES).indexOf(code); return if (index >= 0) NAMES[index] else NAMES[0]
    }
    @JvmStatic fun alphabet(code: String): String {
        if (code.equals("ru")) return "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
        if (code.equals("uk")) return "абвгґдеєжзиіїйклмнопрстуфхцчшщьюя"
        return "abcdefghijklmnopqrstuvwxyz"
    }
    @JvmStatic fun accents(code: String): String {
        when (code) {
            "fr" -> return "àâæçéèêëîïôœùûüÿ"
            "de" -> return "äöüß"
            "es" -> return "áéíñóúü¿¡"
            "it" -> return "àèéìòù"
            "pt" -> return "áàâãçéêíóôõúü"
            else -> return "àáâäãåçèéêëíîïñóôöõøùúûüßæ"
        }
    }
}
