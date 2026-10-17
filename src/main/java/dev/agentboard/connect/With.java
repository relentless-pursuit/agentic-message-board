package dev.agentboard.connect;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Small helpers for reading the free-form "with:" settings and JSON maps. */
public final class With {

    private With() {
    }

    public static List<String> strings(Map<String, Object> with, String key) {
        Object v = with.get(key);
        if (v instanceof Collection<?> c) return c.stream().map(String::valueOf).toList();
        if (v == null) return List.of();
        return List.of(String.valueOf(v));
    }

    public static String string(Map<String, Object> with, String key, String fallback) {
        Object v = with.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    public static int integer(Map<String, Object> with, String key, int fallback, int max) {
        Object v = with.get(key);
        try {
            int n = v == null ? fallback : Integer.parseInt(String.valueOf(v));
            return Math.max(1, Math.min(n, max));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    public static String text(Object map, String key) {
        Object v = map(map).get(key);
        return v == null ? "" : String.valueOf(v);
    }

    /** Collapses whitespace and trims to a length, for one-line facts. */
    /** A look-back period such as 30s, 5m, 24h or 7d. */
    public static java.time.Duration duration(String text) {
        var m = java.util.regex.Pattern.compile("([1-9][0-9]{0,5})([smhd])").matcher(text == null ? "" : text);
        if (!m.matches()) throw new IllegalArgumentException("bad period: " + text);
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> java.time.Duration.ofSeconds(n);
            case "m" -> java.time.Duration.ofMinutes(n);
            case "h" -> java.time.Duration.ofHours(n);
            default -> java.time.Duration.ofDays(n);
        };
    }

    public static String oneLine(String s, int max) {
        if (s == null) return "";
        String flat = s.replaceAll("<[^>]{0,200}>", " ").replaceAll("\\s+", " ").trim();
        return flat.length() > max ? flat.substring(0, max - 1) + "…" : flat;
    }
}
