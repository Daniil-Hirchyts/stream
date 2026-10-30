package tv.stream.app

import android.net.Uri
import java.util.Collections
import tv.stream.app.addon.SubtitleSource

/**
 * The player's external subtitle choices, apart from the activity that shows them: which offers a video gets (the
 * source's own and the subtitle add-ons', without repeats, the viewer's language first), which are not loaded yet,
 * and the names and formats they are listed and attached with.
 */
internal object SubtitleOffers {
    const val MAX_OFFERS = 40

    /** `advertised` ranked with the `wanted` language first, without repeated addresses, at most [MAX_OFFERS]. */
    fun rank(advertised: List<SubtitleSource>, wanted: String?): List<SubtitleSource> {
        val sorted = advertised.sortedBy { if (sameLanguage(it.language, wanted)) 0 else 1 }
        val unique = ArrayList<SubtitleSource>(); val urls = HashSet<String>()
        for (s in sorted) if (unique.size < MAX_OFFERS && urls.add(s.url)) unique.add(s)
        return Collections.unmodifiableList(unique)
    }

    /** Offers not already loaded as tracks of the current media, nor being attached. */
    fun unloaded(offers: List<SubtitleSource>, loadedUrls: Collection<String>, pending: Set<String>, external: (String) -> Boolean): List<SubtitleSource> {
        val urls = HashSet(loadedUrls)
        return offers.filter { !urls.contains(it.url) && !pending.contains(it.url) && !external(it.url) }
    }

    /** Menu names for `offers`, numbered after the tracks already loaded, as they will be named once added. */
    fun names(loadedLanguages: List<String>, offers: List<SubtitleSource>): List<String> {
        val seen = HashMap<String, Int>()
        for (language in loadedLanguages) numbered(languageName(language), seen)
        return offers.map { numbered(languageName(it.language), seen) }
    }

    fun sameLanguage(one: String?, two: String?): Boolean {
        if (one == null || two == null || two.isEmpty()) return false
        return try { java.util.Locale(one).isO3Language == java.util.Locale(two).isO3Language } catch (e: Exception) { one.equals(two, ignoreCase = true) }
    }

    fun numbered(name: String, seen: MutableMap<String, Int>): String {
        val count = (seen[name] ?: 0) + 1; seen[name] = count
        return if (count > 1) "$name $count" else name
    }

    fun languageName(code: String): String {
        val name = SetupScreens.languageName(code)
        if (name != code) return name
        try { val display = java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH); if (display.isNotEmpty()) return display } catch (ignored: Exception) { /* provider's own code */ }
        return code
    }

    fun mime(url: String): String {
        val path = (Uri.parse(url).path ?: "").lowercase(java.util.Locale.ROOT)
        return if (path.endsWith(".srt")) "application/x-subrip" else if (path.endsWith(".ass") || path.endsWith(".ssa")) "text/x-ssa" else "text/vtt"
    }
}
