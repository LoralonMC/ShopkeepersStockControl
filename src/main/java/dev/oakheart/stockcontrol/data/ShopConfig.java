package dev.oakheart.stockcontrol.data;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents the configuration for a single shop.
 * Contains all trade configurations and shop-level settings.
 */
public class ShopConfig {
    private final String shopId;
    private final String name;  // Display name for the shop
    private final boolean enabled;
    private final boolean respectShopStock;
    private final CooldownMode cooldownMode;
    private final String resetTime;   // HH:mm for daily/weekly modes
    private final String resetDay;    // Day of week for weekly mode (e.g., "MONDAY")
    private final Map<String, TradeConfig> trades;  // Key: tradeKey, Value: TradeConfig
    private final Map<Integer, TradeConfig> tradesBySlot;  // Cached slot-to-trade mapping

    /**
     * Creates a new ShopConfig instance.
     *
     * @param shopId            The shop's unique identifier
     * @param name              Display name for the shop
     * @param enabled           Whether this shop is enabled for tracking
     * @param respectShopStock  Whether to respect Shopkeepers' finite stock
     * @param cooldownMode      The cooldown reset mode (daily, weekly, or rolling)
     * @param resetTime         Reset time in HH:mm format (for daily/weekly modes)
     * @param resetDay          Day of week for weekly resets (e.g., "MONDAY")
     * @param trades            Map of trade configurations
     */
    public ShopConfig(String shopId, String name, boolean enabled, boolean respectShopStock,
                      CooldownMode cooldownMode, String resetTime, String resetDay,
                      Map<String, TradeConfig> trades) {
        this.shopId = shopId;
        this.name = name != null && !name.isEmpty() ? name : shopId;  // Fallback to shopId if no name
        this.enabled = enabled;
        this.respectShopStock = respectShopStock;
        this.cooldownMode = cooldownMode;
        this.resetTime = resetTime;
        this.resetDay = resetDay;
        this.trades = new HashMap<>(trades);

        // Pre-compute slot map for fast lookups during packet modification
        Map<Integer, TradeConfig> slotMap = new HashMap<>();
        for (TradeConfig trade : trades.values()) {
            slotMap.put(trade.getSlot(), trade);
        }
        this.tradesBySlot = Map.copyOf(slotMap);
    }

    public String getShopId() {
        return shopId;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean shouldRespectShopStock() {
        return respectShopStock;
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

    public Map<String, TradeConfig> getTrades() {
        return new HashMap<>(trades);
    }

    /**
     * Gets a trade configuration by its key.
     *
     * @param tradeKey The trade key
     * @return The TradeConfig, or null if not found
     */
    public TradeConfig getTrade(String tradeKey) {
        return trades.get(tradeKey);
    }

    /**
     * Gets a trade configuration by its slot position.
     *
     * @param slot The slot number
     * @return The TradeConfig, or null if not found
     */
    public TradeConfig getTradeBySlot(int slot) {
        for (TradeConfig trade : trades.values()) {
            if (trade.getSlot() == slot) {
                return trade;
            }
        }
        return null;
    }

    /**
     * Gets the cached slot-to-TradeConfig mapping for quick lookups.
     *
     * @return Unmodifiable map of slot numbers to TradeConfig
     */
    public Map<Integer, TradeConfig> getTradesBySlot() {
        return tradesBySlot;
    }

    /**
     * Validates this shop configuration.
     *
     * @return true if valid, false otherwise
     */
    public boolean isValid() {
        if (shopId == null || shopId.isEmpty()) {
            return false;
        }

        // Check for duplicate slots
        Map<Integer, String> slotToKey = new HashMap<>();
        for (TradeConfig trade : trades.values()) {
            if (!trade.isValid()) {
                return false;
            }
            String existing = slotToKey.put(trade.getSlot(), trade.getTradeKey());
            if (existing != null) {
                return false; // Duplicate slot
            }
        }

        return true;
    }

    @Override
    public String toString() {
        return "ShopConfig{" +
                "shopId='" + shopId + '\'' +
                ", enabled=" + enabled +
                ", respectShopStock=" + respectShopStock +
                ", trades=" + trades.size() +
                '}';
    }
}
