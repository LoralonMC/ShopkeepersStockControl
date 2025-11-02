package dev.oakheart.stockcontrol.data;

/**
 * Represents the configuration for a single trade.
 * Uses a stable trade key that doesn't change even if trades are reordered.
 */
public class TradeConfig {
    private final String tradeKey;      // Stable identifier (e.g., "diamond_trade")
    private final int slot;             // Current slot position
    private final int maxTrades;        // Maximum number of trades allowed
    private final int cooldownSeconds;  // Cooldown duration in seconds

    /**
     * Creates a new TradeConfig instance.
     *
     * @param tradeKey         Stable trade identifier
     * @param slot             Current slot position (0-indexed)
     * @param maxTrades        Maximum number of trades allowed
     * @param cooldownSeconds  Cooldown duration in seconds
     */
    public TradeConfig(String tradeKey, int slot, int maxTrades, int cooldownSeconds) {
        this.tradeKey = tradeKey;
        this.slot = slot;
        this.maxTrades = maxTrades;
        this.cooldownSeconds = cooldownSeconds;
    }

    public String getTradeKey() {
        return tradeKey;
    }

    public int getSlot() {
        return slot;
    }

    public int getMaxTrades() {
        return maxTrades;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    /**
     * Validates this trade configuration.
     *
     * @return true if valid, false otherwise
     */
    public boolean isValid() {
        return tradeKey != null && !tradeKey.isEmpty()
                && slot >= 0
                && maxTrades > 0
                && cooldownSeconds > 0;
    }

    @Override
    public String toString() {
        return "TradeConfig{" +
                "tradeKey='" + tradeKey + '\'' +
                ", slot=" + slot +
                ", maxTrades=" + maxTrades +
                ", cooldownSeconds=" + cooldownSeconds +
                '}';
    }
}
