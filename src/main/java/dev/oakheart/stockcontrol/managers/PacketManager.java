package dev.oakheart.stockcontrol.managers;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.recipe.data.MerchantOffer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMerchantOffers;
import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.PoolConfig;
import dev.oakheart.stockcontrol.data.PoolItemConfig;
import dev.oakheart.stockcontrol.data.RotationState;
import dev.oakheart.stockcontrol.data.ShopConfig;
import dev.oakheart.stockcontrol.data.ShopContext;
import dev.oakheart.stockcontrol.data.TradeConfig;
import dev.oakheart.stockcontrol.listeners.PacketListener;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;

import java.util.ArrayList;
import java.util.HashMap;
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

    // Stock pushes are collected across the current tick window and drained together on the next
    // tick. Adding a shopId is idempotent (ConcurrentHashMap.newKeySet dedupes), so two trades on
    // the same shop in the same tick produce one push.
    private final Set<String> pendingStockPushes;
    // Rotation pushes behave like stock pushes but additionally return any merchant input items to
    // each viewer's inventory, clearing any stale trade selection that the rotation invalidated.
    private final Set<String> pendingRotationPushes;

    // Per-player UI→Shopkeepers-source slot mapping for the currently open merchant.
    // Populated when a pooled-shop packet is rebuilt; consumed by the inbound
    // SELECT_TRADE listener to remap the client's index back to what Shopkeepers expects.
    private final Map<UUID, List<Integer>> uiToSourceMaps;

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
        this.pendingRotationPushes = ConcurrentHashMap.newKeySet();
        this.uiToSourceMaps = new ConcurrentHashMap<>();
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
        pendingRotationPushes.clear();
        uiToSourceMaps.clear();
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
        uiToSourceMaps.remove(playerId);

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
            uiToSourceMaps.remove(player.getUniqueId());
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("No shop context for " + player.getName() + " - not modifying packet");
            }
            return;
        }

        applyPacketModifications(player, packet, context.shopId());
    }

    /**
     * Applies packet modifications when the shopId is already known, bypassing the
     * TTL-guarded context lookup. Used by the live stock push so an expired TTL doesn't
     * leak an un-rebuilt 5-offer packet to a player still viewing the shop.
     */
    private void applyPacketModifications(Player player, WrapperPlayServerMerchantOffers packet, String shopId) {
        ShopConfig shopConfig = plugin.getConfigManager().getShop(shopId);
        if (shopConfig == null || !shopConfig.isEnabled()) {
            uiToSourceMaps.remove(player.getUniqueId());
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Shop " + shopId + " not tracked or disabled - not modifying packet");
            }
            return;
        }

        if (shopConfig.hasPools()) {
            rebuildWithPools(player, packet, shopConfig);
        } else {
            // No pools configured — legacy in-place path. UI slot == Shopkeepers source.
            uiToSourceMaps.remove(player.getUniqueId());
            modifyInPlace(player, packet, shopConfig);
        }
    }

    private void modifyInPlace(Player player, WrapperPlayServerMerchantOffers packet, ShopConfig shopConfig) {
        Map<Integer, TradeConfig> slotMap = shopConfig.getTradesBySlot();
        var offers = packet.getMerchantOffers();

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("Modifying merchant packet: " + offers.size() + " offers, "
                    + slotMap.size() + " configured slots");
        }

        for (int slot = 0; slot < offers.size(); slot++) {
            TradeConfig tradeConfig = slotMap.get(slot);
            if (tradeConfig == null) continue;

            var offer = offers.get(slot);
            applyLimitsToOffer(player, shopConfig, tradeConfig.getTradeKey(),
                    tradeConfig.getMaxTrades(), tradeConfig.getMaxPerPlayer(), offer);

            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Slot " + slot + " [" + tradeConfig.getTradeKey()
                        + "]: uses=" + offer.getUses() + "/" + offer.getMaxUses());
            }
        }
    }

    /**
     * Rebuilds the outgoing offer list in UI order, hiding pool items that aren't currently active.
     * Also records the per-player UI→source slot map so inbound SELECT_TRADE can be remapped.
     */
    private void rebuildWithPools(Player player, WrapperPlayServerMerchantOffers packet, ShopConfig shopConfig) {
        List<MerchantOffer> originals = packet.getMerchantOffers();
        Map<Integer, TradeConfig> staticByUi = shopConfig.getTradesBySlot();

        // Build assignment: which (pool, item) occupies each UI slot this period.
        Map<Integer, PoolSlotAssignment> poolByUi = new HashMap<>();
        int maxUiSlot = -1;
        for (TradeConfig t : shopConfig.getTrades().values()) {
            maxUiSlot = Math.max(maxUiSlot, t.getSlot());
        }
        for (PoolConfig pool : shopConfig.getPools().values()) {
            RotationState state = plugin.getPoolRotationManager().getState(shopConfig.getShopId(), pool.getName());
            if (state == null) continue;
            List<String> active = state.getActiveItems();
            List<Integer> uiSlots = pool.getUiSlots();
            for (int i = 0; i < uiSlots.size() && i < active.size(); i++) {
                int ui = uiSlots.get(i);
                poolByUi.put(ui, new PoolSlotAssignment(pool, active.get(i)));
                maxUiSlot = Math.max(maxUiSlot, ui);
            }
        }

        List<MerchantOffer> newOffers = new ArrayList<>();
        List<Integer> uiToSource = new ArrayList<>();

        for (int ui = 0; ui <= maxUiSlot; ui++) {
            TradeConfig staticTrade = staticByUi.get(ui);
            PoolSlotAssignment poolAssign = poolByUi.get(ui);

            if (staticTrade != null) {
                int src = staticTrade.getSourceSlot();
                MerchantOffer offer = cloneSingle(originals, src);
                if (offer == null) continue;
                applyLimitsToOffer(player, shopConfig, staticTrade.getTradeKey(),
                        staticTrade.getMaxTrades(), staticTrade.getMaxPerPlayer(), offer);
                newOffers.add(offer);
                uiToSource.add(src);
            } else if (poolAssign != null) {
                PoolItemConfig item = poolAssign.pool().getItem(poolAssign.itemKey());
                if (item == null) continue;
                int src = item.getSourceSlot();
                MerchantOffer offer = cloneSingle(originals, src);
                if (offer == null) continue;
                applyLimitsToOffer(player, shopConfig, item.getItemKey(),
                        item.getMaxTrades(), item.getMaxPerPlayer(), offer);
                newOffers.add(offer);
                uiToSource.add(src);
            }
            // UI slot has neither static nor pool assignment → not appended (no offer at this position).
        }

        packet.setMerchantOffers(newOffers);
        uiToSourceMaps.put(player.getUniqueId(), List.copyOf(uiToSource));

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("Rebuilt merchant packet for " + player.getName()
                    + " in shop " + shopConfig.getShopId()
                    + ": " + originals.size() + " source offers → " + newOffers.size() + " shown"
                    + " (uiToSource=" + uiToSource + ")");
        }
    }

    /**
     * Applies the correct uses/maxUses to an offer based on the player's remaining trades.
     * For shared shops with a per-player cap, the cap is used as the display max.
     */
    private void applyLimitsToOffer(Player player, ShopConfig shopConfig, String tradeKey,
                                    int maxTrades, int maxPerPlayer, MerchantOffer offer) {
        int remaining = tradeDataManager.getRemainingTrades(player.getUniqueId(), shopConfig.getShopId(), tradeKey);
        int displayMax = (shopConfig.isShared() && maxPerPlayer > 0) ? maxPerPlayer : maxTrades;
        int used = displayMax - remaining;
        offer.setUses(Math.max(0, used));
        offer.setMaxUses(displayMax);
    }

    private static MerchantOffer cloneSingle(List<MerchantOffer> originals, int sourceSlot) {
        if (sourceSlot < 0 || sourceSlot >= originals.size()) return null;
        MerchantOffer offer = originals.get(sourceSlot);
        return MerchantOffer.of(
                offer.getFirstInputItem(),
                offer.getSecondInputItem(),
                offer.getOutputItem(),
                offer.getUses(),
                offer.getMaxUses(),
                offer.getXp(),
                offer.getSpecialPrice(),
                offer.getPriceMultiplier(),
                offer.getDemand()
        );
    }

    /**
     * Returns the recorded UI→source mapping for a player (inbound SELECT_TRADE remap).
     * Returns null if the player doesn't have a rebuilt merchant UI open.
     */
    public List<Integer> getUiToSourceMap(UUID playerId) {
        return uiToSourceMaps.get(playerId);
    }

    private record PoolSlotAssignment(PoolConfig pool, String itemKey) {}

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
        // Schedule unconditionally from either thread; the drain-on-next-tick handler is a no-op
        // when the set is already empty. A per-call Bukkit task is far cheaper than the
        // cross-thread synchronization a debounce flag would require.
        Bukkit.getScheduler().runTaskLater(plugin, this::processPendingPushes, 1L);
    }

    /**
     * Schedules a rotation-driven push. In addition to the normal stock rebuild, this returns
     * any merchant-input items back to each viewer's inventory so a stale trade selection
     * (populated before the rotation) can't complete against the wrong item.
     */
    public void scheduleRotationPush(String shopId) {
        pendingRotationPushes.add(shopId);
        Bukkit.getScheduler().runTaskLater(plugin, this::processPendingPushes, 1L);
    }

    /**
     * Processes all pending stock pushes. Runs on the next tick after trades or rotations.
     * Drains the pending sets and pushes updated packets to all viewers of each affected shop.
     * Shops that appear in both sets are treated as rotation pushes (superset of the normal push).
     */
    private void processPendingPushes() {
        Set<String> rotationShops = pendingRotationPushes.isEmpty()
                ? Set.of() : Set.copyOf(pendingRotationPushes);
        pendingRotationPushes.clear();

        Set<String> stockShops = pendingStockPushes.isEmpty()
                ? Set.of() : Set.copyOf(pendingStockPushes);
        pendingStockPushes.clear();

        for (String shopId : rotationShops) {
            pushSharedStockUpdate(shopId, true);
        }
        for (String shopId : stockShops) {
            if (rotationShops.contains(shopId)) continue; // already handled as rotation push
            pushSharedStockUpdate(shopId, false);
        }
    }

    /**
     * Pushes updated stock information to all players currently viewing a shared shop.
     * Rebuilds each viewer's packet from cached original data with current stock values.
     *
     * @param shopId The shop identifier
     */
    private void pushSharedStockUpdate(String shopId, boolean returnInputs) {
        for (Map.Entry<UUID, ShopContext> entry : playerShopCache.entrySet()) {
            UUID viewerId = entry.getKey();
            ShopContext context = entry.getValue();

            // Don't skip on TTL expiry — TTL is a stale-entry safety net, not a "shop closed"
            // signal. Players can sit in an open merchant UI longer than the TTL window between
            // activity. The isOnline() check below covers the real liveness question.
            if (!context.shopId().equals(shopId)) continue;

            CachedMerchantData cached = playerMerchantData.get(viewerId);
            if (cached == null) continue;

            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !viewer.isOnline()) continue;

            // Rotation pushes: return any in-progress trade inputs before re-sending the packet
            // so a previously-selected (now stale) trade can't complete against the new item.
            if (returnInputs) {
                returnMerchantInputs(viewer);
            }

            // Clone offers from cache so modifications don't corrupt the originals
            List<MerchantOffer> offers = cloneOffers(cached.offers());

            // Build a new packet with the cloned offers
            WrapperPlayServerMerchantOffers packet = new WrapperPlayServerMerchantOffers(
                    cached.containerId(), offers, cached.villagerLevel(),
                    cached.villagerXp(), cached.showProgress(), cached.canRestock()
            );

            // Apply current stock modifications for this viewer using the known shopId.
            // Bypasses TTL-guarded context lookup — viewers still watching after their TTL expired
            // would otherwise get an unmodified 5-offer packet leaked to their client.
            applyPacketModifications(viewer, packet, shopId);

            // Send silently — sendPacket would re-trigger our own PacketListener and double-rebuild
            // the already-rebuilt offer list, treating it as if it were the raw 5-offer Shopkeepers packet.
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(viewer, packet);

            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Pushed " + (returnInputs ? "rotation " : "")
                        + "stock update to " + viewer.getName() + " for shop " + shopId);
            }
        }
    }

    /**
     * Returns any merchant input items to the viewer's inventory and clears the output slot.
     * Called on rotation advance so a previously-selected stale trade can't complete against
     * a now-different item at the same UI slot. Leftover items that don't fit are dropped at
     * the player's location so nothing is lost.
     */
    private void returnMerchantInputs(Player viewer) {
        InventoryView view = viewer.getOpenInventory();
        Inventory top = view.getTopInventory();
        if (!(top instanceof MerchantInventory merchantInv)) return;

        for (int slot = 0; slot < 2; slot++) {
            ItemStack item = merchantInv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            Map<Integer, ItemStack> leftover = viewer.getInventory().addItem(item);
            for (ItemStack drop : leftover.values()) {
                viewer.getWorld().dropItemNaturally(viewer.getLocation(), drop);
            }
            merchantInv.setItem(slot, null);
        }
        // Clear the output slot — the server populated it from the previously-selected recipe.
        merchantInv.setItem(2, null);
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
