package dev.oakheart.stockcontrol.data;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents the configuration for a single shop.
 * Contains all trade configurations and shop-level settings.
 */
public class ShopConfig {
    private final String shopId;
    private final String name;  // Display name for the shop
    private final boolean enabled;
    private final CooldownMode cooldownMode;
    private final String resetTime;   // HH:mm for daily/weekly modes
    private final String resetDay;    // Day of week for weekly mode (e.g., "MONDAY")
    private final StockMode stockMode;       // per_player or shared
    private final int maxPerPlayer;          // Default per-player cap for shared mode (0 = no cap)
    private final Map<String, TradeConfig> trades;  // Key: tradeKey — static trades only
    private final Map<Integer, TradeConfig> tradesBySlot;  // Static trades keyed by UI slot (== source for legacy)
    private final Map<String, PoolConfig> pools;    // Key: pool name (empty if no pools configured)
    // Unified lookup for limits / cooldown config by trade key across statics and pool items.
    // Pool items are represented as synthetic TradeConfigs so TradeDataManager can treat them
    // identically to static trades for remaining/used/cooldown calculations.
    private final Map<String, TradeConfig> allTradesByKey;
    // Unified lookup by Shopkeepers source slot — used by the trade-completion listener to
    // resolve which TradeConfig (static or pool item) corresponds to the slot the player clicked.
    private final Map<Integer, TradeConfig> allTradesBySourceSlot;

    /**
     * Creates a new ShopConfig instance.
     *
     * @param shopId       The shop's unique identifier
     * @param name         Display name for the shop
     * @param enabled      Whether this shop is enabled for tracking
     * @param cooldownMode The cooldown reset mode (daily, weekly, rolling, or none)
     * @param resetTime    Reset time in HH:mm format (for daily/weekly modes)
     * @param resetDay     Day of week for weekly resets (e.g., "MONDAY")
     * @param stockMode    Stock tracking mode (per_player or shared)
     * @param maxPerPlayer Default per-player purchase cap for shared mode (0 = no cap)
     * @param trades       Map of trade configurations
     */
    public ShopConfig(String shopId, String name, boolean enabled,
                      CooldownMode cooldownMode, String resetTime, String resetDay,
                      StockMode stockMode, int maxPerPlayer,
                      Map<String, TradeConfig> trades,
                      Map<String, PoolConfig> pools) {
        this.shopId = shopId;
        this.name = name != null && !name.isEmpty() ? name : shopId;  // Fallback to shopId if no name
        this.enabled = enabled;
        this.cooldownMode = cooldownMode;
        this.resetTime = resetTime;
        this.resetDay = resetDay;
        this.stockMode = stockMode;
        this.maxPerPlayer = maxPerPlayer;
        this.trades = new HashMap<>(trades);
        this.pools = new LinkedHashMap<>(pools);

        // Pre-compute slot map for fast lookups during packet modification (legacy no-pools path)
        Map<Integer, TradeConfig> slotMap = new HashMap<>();
        for (TradeConfig trade : trades.values()) {
            slotMap.put(trade.getSlot(), trade);
        }
        this.tradesBySlot = Map.copyOf(slotMap);

        // Unified key lookup — statics first, then each pool's items synthesized as TradeConfig.
        // For pools with subpools, items live inside each subpool — collect those too.
        Map<String, TradeConfig> merged = new HashMap<>(trades);
        for (PoolConfig pool : pools.values()) {
            for (PoolItemConfig item : pool.getItems().values()) {
                mergePoolItem(merged, item);
            }
            for (dev.oakheart.stockcontrol.data.SubpoolConfig sub : pool.getSubpools().values()) {
                for (PoolItemConfig item : sub.getItems().values()) {
                    mergePoolItem(merged, item);
                }
            }
        }
        this.allTradesByKey = Map.copyOf(merged);

        // Source-slot lookup — the trade-completion path gets a Shopkeepers recipe index and
        // needs to find the TradeConfig whose source matches, regardless of static vs pool.
        Map<Integer, TradeConfig> bySource = new HashMap<>();
        for (TradeConfig t : merged.values()) {
            if (t.getSourceSlot() >= 0) {
                bySource.put(t.getSourceSlot(), t);
            }
        }
        this.allTradesBySourceSlot = Map.copyOf(bySource);
    }

    /**
     * Convenience constructor for shops without any rotation pools.
     */
    public ShopConfig(String shopId, String name, boolean enabled,
                      CooldownMode cooldownMode, String resetTime, String resetDay,
                      StockMode stockMode, int maxPerPlayer,
                      Map<String, TradeConfig> trades) {
        this(shopId, name, enabled, cooldownMode, resetTime, resetDay,
                stockMode, maxPerPlayer, trades, Collections.emptyMap());
    }

    private static void mergePoolItem(Map<String, TradeConfig> merged, PoolItemConfig item) {
        merged.put(item.getItemKey(), new TradeConfig(
                item.getItemKey(),
                -1,                          // no fixed UI slot; lookups don't use this
                item.getSourceSlot(),
                item.getMaxTrades(),
                item.getCooldownSeconds(),
                item.getCooldownMode(),
                item.getResetTime(),
                item.getResetDay(),
                item.getMaxPerPlayer()
        ));
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

    public CooldownMode getCooldownMode() {
        return cooldownMode;
    }

    public String getResetTime() {
        return resetTime;
    }

    public String getResetDay() {
        return resetDay;
    }

    public StockMode getStockMode() {
        return stockMode;
    }

    public int getMaxPerPlayer() {
        return maxPerPlayer;
    }

    public boolean isShared() {
        return stockMode == StockMode.SHARED;
    }

    public Map<String, TradeConfig> getTrades() {
        return Collections.unmodifiableMap(trades);
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
     * Resolves trade limits by key across both static trades and pool items.
     * Callers that just need max-trades / cooldown / reset-time for a trade key should use this.
     *
     * @param tradeKey The trade key
     * @return A TradeConfig view of the limits, or null if no such trade or pool item exists
     */
    public TradeConfig findTradeLimits(String tradeKey) {
        return allTradesByKey.get(tradeKey);
    }

    /**
     * Resolves which TradeConfig corresponds to a Shopkeepers source slot.
     * Covers both static trades and pool items, so the trade-completion listener can
     * count trades on rotated items correctly.
     *
     * @param sourceSlot The index of the offer in the Shopkeepers-generated packet
     * @return The TradeConfig for that source slot, or null if none is configured
     */
    public TradeConfig findBySourceSlot(int sourceSlot) {
        return allTradesBySourceSlot.get(sourceSlot);
    }

    /**
     * Gets a trade configuration by its slot position.
     *
     * @param slot The slot number
     * @return The TradeConfig, or null if not found
     */
    public TradeConfig getTradeBySlot(int slot) {
        return tradesBySlot.get(slot);
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
     * Gets all rotation pools configured for this shop.
     *
     * @return Unmodifiable map of pool name to PoolConfig (empty if none)
     */
    public Map<String, PoolConfig> getPools() {
        return Collections.unmodifiableMap(pools);
    }

    public PoolConfig getPool(String poolName) {
        return pools.get(poolName);
    }

    public boolean hasPools() {
        return !pools.isEmpty();
    }

    @Override
    public String toString() {
        return "ShopConfig{" +
                "shopId='" + shopId + '\'' +
                ", enabled=" + enabled +
                ", trades=" + trades.size() +
                '}';
    }
}
