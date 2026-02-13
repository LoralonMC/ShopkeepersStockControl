package dev.oakheart.stockcontrol.data;

/**
 * Defines the cooldown reset mode for a shop's trades.
 */
public enum CooldownMode {
    /** Resets at a fixed time every day (e.g., midnight). */
    DAILY,
    /** Resets at a fixed time on a specific day each week (e.g., Monday midnight). */
    WEEKLY,
    /** Resets a configured number of seconds after the player's first trade. */
    ROLLING,
    /** Never restocks automatically. Stock remains depleted until an admin manually restocks. */
    NONE;

    /**
     * Parses a cooldown mode from a config string.
     *
     * @param value The string value (case-insensitive)
     * @return The parsed CooldownMode, or ROLLING if unrecognized
     */
    public static CooldownMode fromString(String value) {
        if (value == null) return ROLLING;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ROLLING;
        }
    }
}
