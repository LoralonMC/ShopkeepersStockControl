package dev.oakheart.stockcontrol.listeners;

import com.nisovin.shopkeepers.api.events.ShopkeeperOpenUIEvent;
import com.nisovin.shopkeepers.api.events.ShopkeeperTradeEvent;
import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import com.nisovin.shopkeepers.api.util.UnmodifiableItemStack;

import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.ShopConfig;
import dev.oakheart.stockcontrol.data.TradeConfig;
import dev.oakheart.stockcontrol.managers.PacketManager;
import dev.oakheart.stockcontrol.managers.TradeDataManager;
import dev.oakheart.stockcontrol.message.MessageManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.logging.Level;

/**
 * Listens for Shopkeepers events to track shop openings and validate trades.
 * Manages the critical player -> shop mapping for packet modification.
 */
public class ShopkeepersListener implements Listener {

    private final ShopkeepersStockControl plugin;
    private final PacketManager packetManager;
    private final TradeDataManager tradeDataManager;

    public ShopkeepersListener(ShopkeepersStockControl plugin, PacketManager packetManager,
                                TradeDataManager tradeDataManager) {
        this.plugin = plugin;
        this.packetManager = packetManager;
        this.tradeDataManager = tradeDataManager;
    }

    /**
     * Called when a player opens a Shopkeeper's UI.
     * We cache the player -> shop mapping here for packet modification.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShopOpen(ShopkeeperOpenUIEvent event) {
        Player player = event.getPlayer();
        Shopkeeper shopkeeper = event.getShopkeeper();

        // Get shop ID (use Shopkeeper's unique ID)
        String shopId = shopkeeper.getUniqueId().toString();

        packetManager.addShopMapping(player.getUniqueId(), shopId);
        // Pre-load trade data into cache so the packet thread never hits the database
        tradeDataManager.preloadShopData(player.getUniqueId(), shopId);

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info(player.getName() + " opened shop: " + shopId);
        }
    }

    /**
     * Called when a player attempts to trade with a Shopkeeper.
     * We validate the trade and record it if allowed.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShopkeeperTrade(ShopkeeperTradeEvent event) {
        Player player = event.getPlayer();
        Shopkeeper shopkeeper = event.getShopkeeper();
        String shopId = shopkeeper.getUniqueId().toString();

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("=== ShopkeeperTradeEvent fired for " + player.getName() + " at shop " + shopId + " ===");
        }

        // Check if this shop is tracked
        ShopConfig shopConfig = plugin.getConfigManager().getShop(shopId);
        if (shopConfig == null || !shopConfig.isEnabled()) {
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Shop not tracked or disabled - allowing trade");
            }
            return; // Not tracked, allow trade
        }

        // Get the trade recipe being used
        var tradingRecipe = event.getTradingRecipe();
        if (tradingRecipe == null) {
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().warning("No trading recipe in event for " + player.getName());
            }
            return;
        }

        // Try to match the trade to our configured trades
        String matchedTradeKey = findMatchingTradeKey(shopConfig, event);

        if (matchedTradeKey == null) {
            // No matching config, allow trade (not tracked)
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("No matching trade config for shop " + shopId +
                        ", allowing untracked trade");
            }
            return;
        }

        // Check if player can trade
        if (!tradeDataManager.canTrade(player.getUniqueId(), shopId, matchedTradeKey)) {
            event.setCancelled(true);

            // Send message to player (if configured)
            long timeRemaining = tradeDataManager.getTimeUntilReset(player.getUniqueId(), shopId, matchedTradeKey);
            plugin.getMessageManager().sendFeedback(player, MessageManager.TRADE_LIMIT_REACHED, java.util.Map.of(
                    "time_remaining", tradeDataManager.formatDuration(timeRemaining),
                    "reset_time", tradeDataManager.getResetTimeString(shopId, matchedTradeKey)
            ));

            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Blocked trade for " + player.getName() +
                        " - limit reached (cooldown: " + tradeDataManager.formatDuration(timeRemaining) + ")");
            }
            return;
        }

        // Record the trade
        tradeDataManager.recordTrade(player.getUniqueId(), shopId, matchedTradeKey);

        // Schedule debounced stock push for other viewers of shared shops
        if (shopConfig.isShared()) {
            packetManager.scheduleSharedStockPush(shopId);
        }

        // Get remaining trades for feedback message
        int remaining = tradeDataManager.getRemainingTrades(player.getUniqueId(), shopId, matchedTradeKey);

        // Send feedback message
        if (remaining == 0) {
            // Player just used their last trade (or global stock depleted)
            long timeUntilReset = shopConfig.isShared()
                    ? tradeDataManager.getGlobalTimeUntilReset(shopId, matchedTradeKey)
                    : tradeDataManager.getTimeUntilReset(player.getUniqueId(), shopId, matchedTradeKey);
            plugin.getMessageManager().sendFeedback(player, MessageManager.COOLDOWN_ACTIVE, java.util.Map.of(
                    "time_remaining", tradeDataManager.formatDuration(timeUntilReset),
                    "reset_time", tradeDataManager.getResetTimeString(shopId, matchedTradeKey)
            ));
        } else {
            // Show remaining trades
            TradeConfig tradeConfig = shopConfig.getTrade(matchedTradeKey);
            if (tradeConfig != null) {
                int displayMax = (shopConfig.isShared() && tradeConfig.getMaxPerPlayer() > 0)
                        ? tradeConfig.getMaxPerPlayer()
                        : tradeConfig.getMaxTrades();
                plugin.getMessageManager().sendFeedback(player, MessageManager.TRADES_REMAINING, java.util.Map.of(
                        "remaining", String.valueOf(remaining),
                        "max", String.valueOf(displayMax)
                ));
            }
        }

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("Recorded trade for " + player.getName() +
                    " at " + shopId + ":" + matchedTradeKey +
                    " (remaining: " + remaining + ")");
        }
    }

    private String findMatchingTradeKey(ShopConfig shopConfig, ShopkeeperTradeEvent event) {
        try {
            var clickedRecipe = event.getTradingRecipe();
            var shopkeeper = event.getShopkeeper();
            var player = event.getPlayer();

            // Get all trading recipes from the shopkeeper and find the clicked one
            var allRecipes = shopkeeper.getTradingRecipes(player);

            if (allRecipes == null || allRecipes.isEmpty()) {
                if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().warning("Shopkeeper has no trading recipes");
                }
                return null;
            }

            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Shopkeeper has " + allRecipes.size() + " recipes");
                plugin.getLogger().info("Clicked recipe: " + clickedRecipe.toString());
                for (int i = 0; i < allRecipes.size(); i++) {
                    plugin.getLogger().info("  Recipe " + i + ": " + allRecipes.get(i).toString());
                    plugin.getLogger().info("    Equals? " + allRecipes.get(i).equals(clickedRecipe));
                    plugin.getLogger().info("    Same ref? " + (allRecipes.get(i) == clickedRecipe));
                }
            }

            // Find the index of the clicked recipe by comparing items
            int clickedIndex = -1;
            for (int i = 0; i < allRecipes.size(); i++) {
                var recipe = allRecipes.get(i);

                // Compare the items (result, item1, item2)
                boolean resultMatches = itemsEqual(recipe.getResultItem(), clickedRecipe.getResultItem());
                boolean item1Matches = itemsEqual(recipe.getItem1(), clickedRecipe.getItem1());
                boolean item2Matches = itemsEqual(recipe.getItem2(), clickedRecipe.getItem2());

                if (resultMatches && item1Matches && item2Matches) {
                    clickedIndex = i;
                    if (plugin.getConfigManager().isDebugMode()) {
                        plugin.getLogger().info("Found match at index " + i + " by comparing items");
                    }
                    break;
                }
            }

            if (clickedIndex == -1) {
                if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().warning("Could not find clicked recipe in shopkeeper's recipe list");
                }
                return null;
            }

            // Map the slot index to the configured trade key
            var slotMap = shopConfig.getTradesBySlot();
            TradeConfig tradeConfig = slotMap.get(clickedIndex);

            if (tradeConfig == null) {
                // This slot is not configured for tracking
                if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().info("Slot " + clickedIndex + " is not configured for tracking - allowing trade");
                }
                return null;
            }

            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Matched slot " + clickedIndex + " to trade key: " + tradeConfig.getTradeKey());
            }

            return tradeConfig.getTradeKey();

        } catch (Exception e) {
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().log(Level.WARNING, "Error matching trade", e);
            }
        }

        return null;
    }

    /**
     * Compares two items for equality (null-safe).
     * Uses Shopkeepers' UnmodifiableItemStack API with isSimilar() for full comparison
     * including type, amount, and item meta/NBT data.
     *
     * @param item1 First item (can be null)
     * @param item2 Second item (can be null)
     * @return true if both are null or both represent the same item
     */
    private boolean itemsEqual(Object item1, Object item2) {
        // Both null
        if (item1 == null && item2 == null) {
            return true;
        }
        // One null, one not
        if (item1 == null || item2 == null) {
            return false;
        }

        // Use Shopkeepers' UnmodifiableItemStack
        if (!(item1 instanceof UnmodifiableItemStack stack1) ||
            !(item2 instanceof UnmodifiableItemStack stack2)) {
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("      itemsEqual: not UnmodifiableItemStack instances = false");
            }
            return false;
        }

        // Use isSimilar for full comparison (type and meta/NBT) + explicit amount check
        boolean match = stack1.isSimilar(stack2) && stack1.getAmount() == stack2.getAmount();

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("      itemsEqual: " + stack1.getType() + " x" + stack1.getAmount() +
                    " vs " + stack2.getType() + " x" + stack2.getAmount() + " = " + match);
        }
        return match;
    }

}
