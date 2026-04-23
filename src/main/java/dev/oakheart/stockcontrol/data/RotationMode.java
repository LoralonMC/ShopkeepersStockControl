package dev.oakheart.stockcontrol.data;

/**
 * Defines how a pool picks which of its items are active each rotation period.
 */
public enum RotationMode {
    /** Each period picks a random subset of items, seeded deterministically by shop+pool+period. */
    RANDOM,
    /** Each period advances through the item list in order, wrapping around. */
    SEQUENTIAL;

    /**
     * Parses a rotation mode from a config string.
     *
     * @param value The string value (case-insensitive)
     * @return The parsed RotationMode, or RANDOM if unrecognized
     */
    public static RotationMode fromString(String value) {
        if (value == null) return RANDOM;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return RANDOM;
        }
    }
}
