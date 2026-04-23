package dev.oakheart.stockcontrol.data;

/**
 * Represents the configuration for a single trade.
 * Uses a stable trade key that doesn't change even if trades are reordered.
 * Each trade has its own cooldown mode, resolved at load time with shop-level fallback.
 */
public class TradeConfig {
    private final String tradeKey;          // Stable identifier (e.g., "diamond_trade")
    private final int slot;                 // UI position the player sees
    private final int sourceSlot;           // Shopkeepers editor slot (defaults to slot)
    private final int maxTrades;            // Maximum number of trades allowed
    private final int cooldownSeconds;      // Cooldown duration in seconds (for rolling mode)
    private final CooldownMode cooldownMode; // Resolved cooldown mode (trade-level or inherited from shop)
    private final String resetTime;         // HH:mm for daily/weekly modes
    private final String resetDay;          // Day of week for weekly mode (e.g., "MONDAY")
    private final int maxPerPlayer;         // Per-player cap in shared mode (0 = no cap)

    /**
     * Creates a new TradeConfig instance with explicit source slot.
     *
     * @param tradeKey         Stable trade identifier
     * @param slot             UI position shown to the player (0-indexed)
     * @param sourceSlot       Shopkeepers editor slot where the offer actually lives
     * @param maxTrades        Maximum number of trades allowed
     * @param cooldownSeconds  Cooldown duration in seconds
     * @param cooldownMode     Resolved cooldown mode
     * @param resetTime        Reset time in HH:mm format
     * @param resetDay         Day of week for weekly resets (e.g., "MONDAY")
     * @param maxPerPlayer     Per-player purchase cap in shared mode (0 = no cap)
     */
    public TradeConfig(String tradeKey, int slot, int sourceSlot, int maxTrades, int cooldownSeconds,
                       CooldownMode cooldownMode, String resetTime, String resetDay,
                       int maxPerPlayer) {
        this.tradeKey = tradeKey;
        this.slot = slot;
        this.sourceSlot = sourceSlot;
        this.maxTrades = maxTrades;
        this.cooldownSeconds = cooldownSeconds;
        this.cooldownMode = cooldownMode;
        this.resetTime = resetTime;
        this.resetDay = resetDay;
        this.maxPerPlayer = maxPerPlayer;
    }

    /**
     * Convenience constructor for trades whose UI slot matches their Shopkeepers source slot.
     */
    public TradeConfig(String tradeKey, int slot, int maxTrades, int cooldownSeconds,
                       CooldownMode cooldownMode, String resetTime, String resetDay,
                       int maxPerPlayer) {
        this(tradeKey, slot, slot, maxTrades, cooldownSeconds, cooldownMode, resetTime, resetDay, maxPerPlayer);
    }

    public String getTradeKey() {
        return tradeKey;
    }

    public int getSlot() {
        return slot;
    }

    public int getSourceSlot() {
        return sourceSlot;
    }

    public int getMaxTrades() {
        return maxTrades;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public CooldownMode getCooldownMode() {
        return cooldownMode;
    }

    public String getResetTime() {
        return resetTime;
    }

    public String getResetDay() {
        return resetDay;
    }

    public int getMaxPerPlayer() {
        return maxPerPlayer;
    }

    @Override
    public String toString() {
        return "TradeConfig{" +
                "tradeKey='" + tradeKey + '\'' +
                ", slot=" + slot +
                ", maxTrades=" + maxTrades +
                ", cooldownSeconds=" + cooldownSeconds +
                ", cooldownMode=" + cooldownMode +
                '}';
    }
}
