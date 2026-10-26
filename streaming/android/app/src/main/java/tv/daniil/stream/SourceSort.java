package tv.daniil.stream;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tv.daniil.stream.addon.MediaSource;
import tv.daniil.stream.data.TvStore;

/** The orders Sources offers, from what add-ons advertise in a source's name and description. Nothing is downloaded. */
final class SourceSort {
    private SourceSort() {}

    enum Order {
        BEST("Best"), QUALITY("Quality"), SIZE("Size"), SEEDS("Seeds"), PLAYED("Played");
        final String label;
        Order(String label) { this.label = label; }
        static Order of(String name) { for (Order o : values()) if (o.name().equals(name)) return o; return BEST; }
    }

    /** Facts read from the advertised text: 0 or -1 when a source does not say. */
    static final class Facts {
        final int lines;
        final long bytes;
        final int seeds;
        Facts(int lines, long bytes, int seeds) { this.lines = lines; this.bytes = bytes; this.seeds = seeds; }
        String quality() { return lines <= 0 ? "" : lines + "p"; }
    }

    private static final Pattern LINES = Pattern.compile("(?i)\\b(4320|2160|1440|1080|720|576|480|360)p\\b|\\b(4k|uhd)\\b");
    private static final Pattern SIZE = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*(tb|tib|gb|gib|mb|mib)\\b");
    private static final Pattern SEEDS = Pattern.compile("(?i)(?:👤|👥|🌱|⬆️?|\\b(?:seed(?:er)?s?|peers?)\\b\\s*[:=]?)\\s*(\\d+)");

    static Facts facts(String name, String description, String filename, long advertisedBytes) {
        String text = name + "\n" + description + "\n" + (filename == null ? "" : filename);
        int lines = 0;
        Matcher m = LINES.matcher(text);
        while (m.find()) lines = Math.max(lines, m.group(1) != null ? Integer.parseInt(m.group(1)) : 2160);
        long bytes = advertisedBytes;
        if (bytes <= 0) {
            m = SIZE.matcher(text);
            if (m.find()) {
                double value = Double.parseDouble(m.group(1).replace(',', '.'));
                String unit = m.group(2).toLowerCase(Locale.ROOT);
                double scale = unit.startsWith("t") ? 1L << 40 : unit.startsWith("g") ? 1L << 30 : 1L << 20;
                bytes = (long) (value * scale);
            }
        }
        m = SEEDS.matcher(text);
        int seeds = -1;
        if (m.find()) { try { seeds = (int) Math.min(Integer.MAX_VALUE, Long.parseLong(m.group(1))); } catch (NumberFormatException overflow) { seeds = Integer.MAX_VALUE; } }
        return new Facts(lines, bytes, seeds);
    }
    static Facts facts(MediaSource s) {
        Facts text = facts(s.name, s.description, s.filename, s.videoSize);
        return new Facts(text.lines, text.bytes, s.seeders >= 0 ? s.seeders : text.seeds);
    }

    /**
     * {@code ranked} is the policy's best-first list. {@code reverse} flips an order (worst, smallest, fewest or oldest
     * first). Sources that don't say keep their best-first place below those that do, whichever way the list runs.
     */
    static List<MediaSource> sort(List<MediaSource> ranked, Order order, boolean reverse, Map<String, TvStore.SourceMark> marks) {
        List<MediaSource> out = new ArrayList<>(ranked);
        if (order == Order.BEST) { if (reverse) java.util.Collections.reverse(out); return out; }
        Map<MediaSource, Long> value = new java.util.IdentityHashMap<>();
        Map<MediaSource, Integer> rank = new java.util.IdentityHashMap<>();
        for (int i = 0; i < out.size(); i++) {
            MediaSource s = out.get(i); Facts f = facts(s); rank.put(s, i);
            switch (order) {
                case QUALITY: value.put(s, (long) f.lines); break;
                case SIZE: value.put(s, f.bytes); break;
                case SEEDS: value.put(s, (long) f.seeds); break;
                default: value.put(s, playedAt(marks, s)); break;
            }
        }
        Comparator<MediaSource> known = Comparator.comparing((MediaSource s) -> value.get(s) <= 0 && !(order == Order.SEEDS && value.get(s) == 0));
        Comparator<MediaSource> by = Comparator.comparingLong(value::get);
        out.sort(known.thenComparing(reverse ? by : by.reversed()).thenComparingInt(rank::get));
        return out;
    }
    /** When a source last played; zero for failed and untried ones. */
    private static long playedAt(Map<String, TvStore.SourceMark> marks, MediaSource s) {
        TvStore.SourceMark mark = marks.get(SourcePolicy.key(s));
        return mark != null && mark.state.equals(TvStore.SourceMark.PLAYED) ? mark.updatedMs : 0;
    }
}
