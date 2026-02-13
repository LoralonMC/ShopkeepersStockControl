package dev.oakheart.stockcontrol.managers;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.recipe.data.MerchantOffer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMerchantOffers;
import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.ShopConfig;
import dev.oakheart.stockcontrol.data.ShopContext;
import dev.oakheart.stockcontrol.data.TradeConfig;
import dev.oakheart.stockcontrol.listeners.PacketListener;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

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

    // Cached original merchant packet data per player (for pushing shared stock updates)
    private final Map<UUID, CachedMerchantData> playerMerchantData;

    // Players whose next MERCHANT_OFFERS packet should be cached (set on shop open, consumed by packet listener)
    private final Set<UUID> pendingCache;

    // Debounced stock push: shops that need a push next tick (collapses rapid trades into one push)
    private final Set<String> pendingStockPushes;
    private boolean pushTaskScheduled;

    private PacketListener packetListener;

    /**
     * Cached data from a player's original merchant offers packet.
     * Used to reconstruct and push updated packets for shared stock changes.
     */
    private record CachedMerchantData(
            int containerId,
            List<MerchantOffer> offers,
            int villagerLevel,
            int villagerXp,
            boolean showProgress,
            boolean canRestock
    ) {}

    public PacketManager(ShopkeepersStockControl plugin, TradeDataManager tradeDataManager) {
        this.plugin = plugin;
        this.tradeDataManager = tradeDataManager;
        this.playerShopCache = new ConcurrentHashMap<>();
        this.playerMerchantData = new ConcurrentHashMap<>();
        this.pendingCache = ConcurrentHashMap.newKeySet();
        this.pendingStockPushes = ConcurrentHashMap.newKeySet();
    }

    /**
     * Initializes PacketEvents and registers the packet listener.
     */
    public void initialize() {
        try {
            packetListener = new PacketListener(plugin, this, tradeDataManager);
            PacketEvents.getAPI().getEventManager().registerListener(packetListener);
            plugin.getLogger().info("PacketEvents listener registered successfully");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to initialize PacketEvents — UI stock display will be disabled", e);
        }
    }

    /**
     * Shuts down the packet manager and cleans up.
     */
    public void shutdown() {
        try {
            if (packetListener != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
            }
        } catch (Exception e) {
            // Ignore - PacketEvents might not be available
        }

        playerShopCache.clear();
        playerMerchantData.clear();
        pendingCache.clear();
        pendingStockPushes.clear();
        plugin.getLogger().info("PacketManager shutdown complete");
    }

    /**
     * Adds a player -> shop mapping to the cache.
     * Called when a player opens a Shopkeeper.
     *
     * @param playerId The player's UUID
     * @param shopId   The shop identifier
     */
    public void addShopMapping(UUID playerId, String shopId) {
        long cacheTTL = plugin.getConfigManager().getCacheTTL();
        long expiryTime = System.currentTimeMillis() + (cacheTTL * 1000L);

        ShopContext context = new ShopContext(shopId, expiryTime);
        playerShopCache.put(playerId, context);
        pendingCache.add(playerId);

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("Added shop mapping: " + playerId + " -> " + shopId +
                    " (expires in " + cacheTTL + "s)");
        }
    }

    /**
     * Removes a player -> shop mapping from the cache.
     *
     * @param playerId The player's UUID
     */
    public void removeShopMapping(UUID playerId) {
        ShopContext removed = playerShopCache.remove(playerId);
        playerMerchantData.remove(playerId);
        pendingCache.remove(playerId);

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
        return playerShopCache.computeIfPresent(playerId, (key, context) -> {
            if (context.isExpired()) {
                playerMerchantData.remove(playerId);
                if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().info("Removed expired shop mapping: " + playerId + " -> " + context.shopId());
                }
                return null;
            }
            // Refresh TTL to keep mapping alive while trading window is open
            long cacheTTL = plugin.getConfigManager().getCacheTTL();
            long newExpiry = System.currentTimeMillis() + (cacheTTL * 1000L);
            return new ShopContext(context.shopId(), newExpiry);
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
            int remaining = tradeDataManager.getRemainingTrades(
                    player.getUniqueId(),
                    shopId,
                    tradeConfig.getTradeKey()
            );

            // For shared mode with per-player cap, show the per-player cap as the max
            int displayMax;
            if (shopConfig.isShared() && tradeConfig.getMaxPerPlayer() > 0) {
                displayMax = tradeConfig.getMaxPerPlayer();
            } else {
                displayMax = tradeConfig.getMaxTrades();
            }
            int used = displayMax - remaining;

            // Show exact stock numbers - client will auto-cross-out when uses >= maxUses
            offer.setUses(Math.max(0, used));
            offer.setMaxUses(displayMax);

            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Slot " + slot + " [" + tradeConfig.getTradeKey() + "]:");
                plugin.getLogger().info("  Remaining: " + remaining + "/" + displayMax +
                        (shopConfig.isShared() ? " (shared)" : "") + " (used: " + Math.max(0, used) + ")");
                plugin.getLogger().info("  Setting to: uses=" + Math.max(0, used) + ", maxUses=" + displayMax);
            }
        }
    }

    // ===== Shared Stock Live Updates =====

    /**
     * Checks and consumes the pending cache flag for a player.
     * Called by PacketListener to decide whether to cache the original offers.
     *
     * @param playerId The player's UUID
     * @return true if this packet should be cached (first packet after shop open)
     */
    public boolean shouldCachePacket(UUID playerId) {
        return pendingCache.remove(playerId);
    }

    /**
     * Caches the original (pre-modification) merchant packet data for a player.
     * Called by PacketListener before modifying the packet.
     *
     * @param playerId The player's UUID
     * @param packet   The original merchant offers packet
     */
    public void cachePacketData(UUID playerId, WrapperPlayServerMerchantOffers packet) {
        List<MerchantOffer> originalOffers = cloneOffers(packet.getMerchantOffers());
        playerMerchantData.put(playerId, new CachedMerchantData(
                packet.getContainerId(),
                originalOffers,
                packet.getVillagerLevel(),
                packet.getVillagerXp(),
                packet.isShowProgress(),
                packet.isCanRestock()
        ));

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("Cached merchant data for " + playerId +
                    " (containerId=" + packet.getContainerId() + ", offers=" + originalOffers.size() + ")");
        }
    }

    /**
     * Schedules a debounced stock push for a shared shop.
     * Multiple trades within the same tick are collapsed into a single push on the next tick,
     * avoiding O(n²) packet storms when many players trade rapidly.
     *
     * @param shopId The shop identifier
     */
    public void scheduleSharedStockPush(String shopId) {
        pendingStockPushes.add(shopId);

        if (!pushTaskScheduled) {
            pushTaskScheduled = true;
            Bukkit.getScheduler().runTaskLater(plugin, this::processPendingPushes, 1L);
        }
    }

    /**
     * Processes all pending stock pushes. Runs on the next tick after trades are recorded.
     * Drains the pending set and pushes updated packets to all viewers of each affected shop.
     */
    private void processPendingPushes() {
        pushTaskScheduled = false;

        // Drain all pending shops
        Set<String> shopsToPush = Set.copyOf(pendingStockPushes);
        pendingStockPushes.clear();

        for (String shopId : shopsToPush) {
            pushSharedStockUpdate(shopId);
        }
    }

    /**
     * Pushes updated stock information to all players currently viewing a shared shop.
     * Rebuilds each viewer's packet from cached original data with current stock values.
     *
     * @param shopId The shop identifier
     */
    private void pushSharedStockUpdate(String shopId) {
        for (Map.Entry<UUID, ShopContext> entry : playerShopCache.entrySet()) {
            UUID viewerId = entry.getKey();
            ShopContext context = entry.getValue();

            if (context.isExpired() || !context.shopId().equals(shopId)) continue;

            CachedMerchantData cached = playerMerchantData.get(viewerId);
            if (cached == null) continue;

            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !viewer.isOnline()) continue;

            // Clone offers from cache so modifications don't corrupt the originals
            List<MerchantOffer> offers = cloneOffers(cached.offers());

            // Build a new packet with the cloned offers
            WrapperPlayServerMerchantOffers packet = new WrapperPlayServerMerchantOffers(
                    cached.containerId(), offers, cached.villagerLevel(),
                    cached.villagerXp(), cached.showProgress(), cached.canRestock()
            );

            // Apply current stock modifications for this viewer
            modifyMerchantPacket(viewer, packet);

            // Send the updated packet
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);

            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Pushed stock update to " + viewer.getName() + " for shop " + shopId);
            }
        }
    }

    /**
     * Creates a deep copy of a merchant offers list.
     * Only uses/maxUses are modified by our code, but we clone the full offer
     * to avoid corrupting cached originals.
     */
    private static List<MerchantOffer> cloneOffers(List<MerchantOffer> originals) {
        List<MerchantOffer> cloned = new ArrayList<>(originals.size());
        for (MerchantOffer offer : originals) {
            cloned.add(MerchantOffer.of(
                    offer.getFirstInputItem(),
                    offer.getSecondInputItem(),
                    offer.getOutputItem(),
                    offer.getUses(),
                    offer.getMaxUses(),
                    offer.getXp(),
                    offer.getSpecialPrice(),
                    offer.getPriceMultiplier(),
                    offer.getDemand()
            ));
        }
        return cloned;
    }
}
