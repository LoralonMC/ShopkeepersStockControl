package dev.oakheart.stockcontrol.data;

/**
 * Configuration for one item inside a rotation pool.
 * Unlike {@link TradeConfig}, a pool item has no fixed UI slot — its UI position
 * is determined by the owning pool's {@code ui-slots} list when the item is active.
 */
public class PoolItemConfig {
    private final String itemKey;
    private final int sourceSlot;
    private final int maxTrades;
    private final int cooldownSeconds;
    private final CooldownMode cooldownMode;
    private final String resetTime;
    private final String resetDay;
    private final int maxPerPlayer;

    /**
     * @param itemKey         Stable identifier (unique across all pools in the shop)
     * @param sourceSlot      Slot in Shopkeepers' editor where this item lives
     * @param maxTrades       Maximum trades allowed before the item is depleted
     * @param cooldownSeconds Cooldown duration in seconds (for rolling mode)
     * @param cooldownMode    Resolved cooldown mode (trade-level or inherited from shop)
     * @param resetTime       Reset time in HH:mm format
     * @param resetDay        Day of week for weekly resets (e.g. "MONDAY")
     * @param maxPerPlayer    Per-player cap in shared mode (0 = no cap)
     */
    public PoolItemConfig(String itemKey, int sourceSlot, int maxTrades, int cooldownSeconds,
                          CooldownMode cooldownMode, String resetTime, String resetDay,
                          int maxPerPlayer) {
        this.itemKey = itemKey;
        this.sourceSlot = sourceSlot;
        this.maxTrades = maxTrades;
        this.cooldownSeconds = cooldownSeconds;
        this.cooldownMode = cooldownMode;
        this.resetTime = resetTime;
        this.resetDay = resetDay;
        this.maxPerPlayer = maxPerPlayer;
    }

    public String getItemKey() {
        return itemKey;
    }

    public int getSourceSlot() {
        return sourceSlot;
    }

    public int getMaxTrades() {
        return maxTrades;
    }

    /** A {@code max-trades} of {@code -1} means "no per-period cap" (unlimited). */
    public boolean isUnlimited() {
        return maxTrades < 0;
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
        return "PoolItemConfig{" +
                "itemKey='" + itemKey + '\'' +
                ", sourceSlot=" + sourceSlot +
                ", maxTrades=" + maxTrades +
                '}';
    }
}
