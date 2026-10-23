package tv.daniil.stream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tv.daniil.stream.addon.AddonException;
import tv.daniil.stream.addon.MediaSource;
import tv.daniil.stream.data.InstalledAddon;

/** Explains an empty source list: no source add-on, failing providers, only unsupported results, or none at all. */
final class Availability {
    static final String NO_PROVIDER = "No source add-on installed";
    static final String NO_PROVIDER_DETAIL = "Add a source add-on to play titles.";

    final String heading, detail;
    private Availability(String heading, String detail) { this.heading = heading; this.detail = detail; }

    /**
     * @param providers add-ons asked for streams
     * @param found every advertised source, including unsupported ones
     * @param failures failed providers by add-on ID
     */
    static Availability of(List<InstalledAddon> providers, List<MediaSource> found, Map<String, AddonException.Kind> failures, List<InstalledAddon> installed) {
        if (found.isEmpty() && providers.isEmpty()) return new Availability(NO_PROVIDER, NO_PROVIDER_DETAIL);
        if (found.isEmpty() && !failures.isEmpty()) return new Availability("Source providers are unavailable", failures(failures, installed));
        if (!found.isEmpty()) {
            Map<String, Integer> reasons = new LinkedHashMap<>();
            for (MediaSource s : found) { String reason = SourcePolicy.assess(s).label; reasons.merge(reason, 1, Integer::sum); }
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : reasons.entrySet()) parts.add(e.getKey() + (reasons.size() > 1 ? " (" + e.getValue() + ")" : ""));
            return new Availability("No playable sources", found.size() + " found · " + String.join(" · ", parts));
        }
        return new Availability("No sources found", "Nothing for this title yet.");
    }

    /** "Provider X didn't respond in time." for each failed provider, without addresses or response bodies. */
    static String failures(Map<String, AddonException.Kind> failures, List<InstalledAddon> installed) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, AddonException.Kind> e : failures.entrySet()) {
            String name = "A provider";
            for (InstalledAddon a : installed) if (a.id.equals(e.getKey())) name = a.manifest.name;
            lines.add(name + " " + problem(e.getValue()) + ".");
        }
        return String.join(" ", lines);
    }
    static String problem(AddonException.Kind kind) {
        switch (kind) {
            case TIMEOUT: return "didn't respond in time";
            case CERTIFICATE: return "has a certificate this TV can't verify";
            case HTTP: return "returned an error";
            case MALFORMED: return "returned data this app can't read";
            case CANCELLED: return "was cancelled";
            default: return "couldn't be reached";
        }
    }
}
