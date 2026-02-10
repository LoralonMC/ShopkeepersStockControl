package dev.oakheart.stockcontrol.managers;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMerchantOffers;
import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.ShopConfig;
import dev.oakheart.stockcontrol.data.ShopContext;
import dev.oakheart.stockcontrol.data.TradeConfig;
import dev.oakheart.stockcontrol.listeners.PacketListener;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages PacketEvents integration and merchant packet modification.
 * Handles the critical shop mapping cache and packet interception.
 */
public class PacketManager {

    private final ShopkeepersStockControl plugin;
    private final TradeDataManager tradeDataManager;

    // Cache to map players to their currently open shop
    // Thread-safe for packet thread access
    private final Map<UUID, ShopContext> playerShopCache;

    private PacketListener packetListener;

    public PacketManager(ShopkeepersStockControl plugin, TradeDataManager tradeDataManager) {
        this.plugin = plugin;
        this.tradeDataManager = tradeDataManager;
        this.playerShopCache = new ConcurrentHashMap<>();
    }

    /**
     * Initializes PacketEvents and registers the packet listener.
     */
    public void initialize() {
        try {
            // Register packet listener
            packetListener = new PacketListener(plugin, this, tradeDataManager);
            PacketEvents.getAPI().getEventManager().registerListener(packetListener);

            plugin.getLogger().info("PacketEvents listener registered successfully");

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to initialize PacketEvents: " + e.getMessage());
            plugin.getLogger().warning("UI stock display will be disabled");
            if (plugin.getConfigManager().isDebugMode()) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Shuts down the packet manager and cleans up.
     */
    public void shutdown() {
        // Unregister packet listener
        try {
            if (packetListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            }
        } catch (Exception e) {
            // Ignore - PacketEvents might not be available
        }

        // Clear cache
        playerShopCache.clear();

        plugin.getLogger().info("PacketManager shutdown complete");
    }

    /**
     * Adds a player -> shop mapping to the cache.
     * Called when a player opens a Shopkeeper.
     *
     * @param playerId The player's UUID
     * @param shopId   The shop identifier
     * @param entityId The NPC entity ID
     */
    public void addShopMapping(UUID playerId, String shopId, int entityId) {
        long cacheTTL = plugin.getConfigManager().getCacheTTL();
        long expiryTime = System.currentTimeMillis() + (cacheTTL * 1000L);

        ShopContext context = new ShopContext(shopId, entityId, expiryTime);
        playerShopCache.put(playerId, context);

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("Added shop mapping: " + playerId + " -> " + shopId +
                    " (expires in " + cacheTTL + "s)");
        }
    }

    /**
     * Removes a player -> shop mapping from the cache.
     * Called when a player closes the trading UI.
     *
     * @param playerId The player's UUID
     */
    public void removeShopMapping(UUID playerId) {
        ShopContext removed = playerShopCache.remove(playerId);

        if (removed != null && plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("Removed shop mapping: " + playerId + " -> " + removed.shopId());
        }
    }

    /**
     * Gets the shop context for a player.
     * Automatically removes expired contexts and refreshes TTL on active ones.
     *
     * @param playerId The player's UUID
     * @return ShopContext or null if not found or expired
     */
    public ShopContext getShopContext(UUID playerId) {
        // Use atomic operation to check and remove expired contexts
        // This prevents race conditions between get() and remove()
        return playerShopCache.computeIfPresent(playerId, (key, context) -> {
            if (context.isExpired()) {
                if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().info("Removed expired shop mapping: " + playerId + " -> " + context.shopId());
                }
                return null; // Returning null removes the entry atomically
            }
            // Refresh TTL to keep mapping alive while trading window is open
            long cacheTTL = plugin.getConfigManager().getCacheTTL();
            long newExpiry = System.currentTimeMillis() + (cacheTTL * 1000L);
            return new ShopContext(context.shopId(), context.entityId(), newExpiry);
        });
    }

    /**
     * Modifies merchant offers for a player based on their trade limits.
     * This is the core packet modification logic.
     *
     * @param player The player
     * @param packet The merchant offers packet to modify
     */
    public void modifyMerchantPacket(Player player, WrapperPlayServerMerchantOffers packet) {
        ShopContext context = getShopContext(player.getUniqueId());

        // Not a tracked shop or context expired
        if (context == null) {
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("No shop context for " + player.getName() + " - not modifying packet");
            }
            return;
        }

        String shopId = context.shopId();

        // Check if this shop is enabled for tracking
        ShopConfig shopConfig = plugin.getConfigManager().getShop(shopId);
        if (shopConfig == null || !shopConfig.isEnabled()) {
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Shop " + shopId + " not tracked or disabled - not modifying packet");
            }
            return;
        }

        // Get trade configurations by slot
        Map<Integer, TradeConfig> slotMap = shopConfig.getTradesBySlot();

        // Get original offers
        var offers = packet.getMerchantOffers();

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("Modifying merchant packet: " + offers.size() + " offers, " + slotMap.size() + " configured slots");
        }

        // Modify each offer based on player's remaining trades
        for (int slot = 0; slot < offers.size(); slot++) {
            TradeConfig tradeConfig = slotMap.get(slot);

            // This slot is not tracked in config
            if (tradeConfig == null) {
                continue;
            }

            var offer = offers.get(slot);
            int maxTrades = tradeConfig.getMaxTrades();
            int remaining = tradeDataManager.getRemainingTrades(
                    player.getUniqueId(),
                    shopId,
                    tradeConfig.getTradeKey()
            );

            int playerUsed = maxTrades - remaining;

            // Show exact stock numbers - client will auto-cross-out when uses >= maxUses
            offer.setUses(playerUsed);
            offer.setMaxUses(maxTrades);

            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Slot " + slot + " [" + tradeConfig.getTradeKey() + "]:");
                plugin.getLogger().info("  Player: " + remaining + "/" + maxTrades + " remaining (used: " + playerUsed + ")");
                plugin.getLogger().info("  Setting to: uses=" + playerUsed + ", maxUses=" + maxTrades);
            }
        }

        // Note: We modified the offers in-place, so they're already updated in the packet
    }

    /**
     * Clears all shop mappings (called on plugin disable).
     */
    public void clearAllMappings() {
        int size = playerShopCache.size();
        playerShopCache.clear();
        plugin.getLogger().info("Cleared " + size + " shop mappings from cache");
    }

    /**
     * Gets the current cache size (for debugging).
     *
     * @return Number of cached mappings
     */
    public int getCacheSize() {
        return playerShopCache.size();
    }
}
