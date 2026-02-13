package dev.oakheart.stockcontrol.data;

/**
 * Defines the stock tracking mode for a shop.
 */
public enum StockMode {
    /** Each player has independent stock limits. */
    PER_PLAYER,
    /** All players share a single stock pool. */
    SHARED;

    /**
     * Parses a stock mode from a config string.
     *
     * @param value The string value (case-insensitive)
     * @return The parsed StockMode, or PER_PLAYER if unrecognized
     */
    public static StockMode fromString(String value) {
        if (value == null) return PER_PLAYER;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PER_PLAYER;
        }
    }
}
