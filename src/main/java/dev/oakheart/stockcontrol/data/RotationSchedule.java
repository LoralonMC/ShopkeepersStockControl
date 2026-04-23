package dev.oakheart.stockcontrol.data;

/**
 * Defines how often a pool advances its active selection.
 */
public enum RotationSchedule {
    /** Advances once per day at reset-time. */
    DAILY,
    /** Advances once per week at reset-time on reset-day. */
    WEEKLY,
    /** Advances every N seconds (configured via 'every'), anchored to reset-time on today. */
    INTERVAL;

    /**
     * Parses a rotation schedule from a config string.
     *
     * @param value The string value (case-insensitive)
     * @return The parsed RotationSchedule, or DAILY if unrecognized
     */
    public static RotationSchedule fromString(String value) {
        if (value == null) return DAILY;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return DAILY;
        }
    }
}
