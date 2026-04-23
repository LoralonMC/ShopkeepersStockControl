package dev.oakheart.stockcontrol.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses human-friendly duration strings like "6h", "30m", "45s", "2d" into seconds.
 * Also accepts bare integers (interpreted as seconds) for backwards compatibility.
 */
public final class DurationParser {

    private static final Pattern PATTERN = Pattern.compile("^\\s*(\\d+)\\s*([smhd]?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private DurationParser() {}

    /**
     * Parses a duration string to seconds.
     *
     * @param input The duration string (e.g. "6h", "30m", "120")
     * @return Duration in seconds
     * @throws IllegalArgumentException if the input cannot be parsed
     */
    public static long parseSeconds(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Duration is null");
        }
        Matcher m = PATTERN.matcher(input);
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid duration: '" + input
                    + "' (expected a number optionally followed by s/m/h/d)");
        }
        long value = Long.parseLong(m.group(1));
        String unit = m.group(2).toLowerCase();
        return switch (unit) {
            case "", "s" -> value;
            case "m" -> value * 60L;
            case "h" -> value * 3600L;
            case "d" -> value * 86400L;
            default -> throw new IllegalArgumentException("Unknown duration unit: " + unit);
        };
    }

    /**
     * Like {@link #parseSeconds(String)} but returns a fallback instead of throwing.
     *
     * @param input    The duration string
     * @param fallback Value to return on parse failure
     * @return Parsed seconds or the fallback
     */
    public static long parseSecondsOr(String input, long fallback) {
        try {
            return parseSeconds(input);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
