package dev.oakheart.stockcontrol.placeholders;

import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.CooldownMode;
import dev.oakheart.stockcontrol.data.PoolConfig;
import dev.oakheart.stockcontrol.data.RotationState;
import dev.oakheart.stockcontrol.data.ShopConfig;
import dev.oakheart.stockcontrol.data.TradeConfig;
import dev.oakheart.stockcontrol.managers.TradeDataManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.ZonedDateTime;

/**
 * PlaceholderAPI expansion for ShopkeepersStockControl.
 *
 * Placeholder format: %ssc_<action>_<shop>:<trade>%
 * The shop identifier can be either the shop ID (from trades.yml) or its display name.
 *
 * Supported placeholders:
 *   %ssc_remaining_<shop>:<trade>%        - Remaining trades for the player (e.g., "3")
 *   %ssc_used_<shop>:<trade>%             - Used trades by the player (e.g., "2")
 *   %ssc_max_<shop>:<trade>%              - Effective max trades (per-player cap for shared, otherwise max_trades)
 *   %ssc_cooldown_<shop>:<trade>%         - Formatted cooldown, "Ready", or "Sold out" (NONE mode)
 *   %ssc_resettime_<shop>:<trade>%        - Reset time display (e.g., "00:00", "Monday 00:00", "Never")
 *   %ssc_globalmax_<shop>:<trade>%        - Total global stock for shared shops (e.g., "100")
 *   %ssc_globalremaining_<shop>:<trade>%  - Remaining global stock for shared shops (e.g., "73")
 *
 * Rotation-pool placeholders (use pool name instead of trade key):
 *   %ssc_poolactive_<shop>:<pool>%        - Comma-separated active item keys (e.g., "summer_melon,berry_pie")
 *   %ssc_poolnext_<shop>:<pool>%          - Time until next rotation (e.g., "5h 23m")
 */
public class StockControlExpansion extends PlaceholderExpansion {

    private final ShopkeepersStockControl plugin;

    public StockControlExpansion(ShopkeepersStockControl plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "ssc";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return null;

        // Split into action and identifier: "remaining_shopId:tradeKey" -> ["remaining", "shopId:tradeKey"]
        int firstUnderscore = params.indexOf('_');
        if (firstUnderscore == -1) return null;

        String action = params.substring(0, firstUnderscore);
        String identifier = params.substring(firstUnderscore + 1);

        // All placeholders require shop:trade format
        int colonIndex = identifier.indexOf(':');
        if (colonIndex == -1) return null;

        String shopIdentifier = identifier.substring(0, colonIndex);
        String rest = identifier.substring(colonIndex + 1);

        // Try shop ID first, then fall back to display name lookup
        ShopConfig shopConfig = plugin.getConfigManager().getShop(shopIdentifier);
        if (shopConfig == null) {
            shopConfig = plugin.getConfigManager().getShopByName(shopIdentifier);
        }
        if (shopConfig == null) return null;

        String shopId = shopConfig.getShopId();

        // Rotation-pool placeholders use the 'pool' name (instead of trade key) as the entity.
        if (action.equals("poolactive") || action.equals("poolnext")) {
            return resolvePoolPlaceholder(shopConfig, rest, action);
        }

        // Trade-level placeholders — pool items resolve through the unified lookup.
        String tradeKey = rest;
        TradeConfig tradeConfig = shopConfig.findTradeLimits(tradeKey);
        if (tradeConfig == null) return null;

        TradeDataManager tdm = plugin.getTradeDataManager();

        // Determine effective max for the player (per-player cap for shared mode)
        int effectiveMax = (shopConfig.isShared() && tradeConfig.getMaxPerPlayer() > 0)
                ? tradeConfig.getMaxPerPlayer()
                : tradeConfig.getMaxTrades();

        switch (action) {
            case "remaining":
                return String.valueOf(tdm.getRemainingTrades(player.getUniqueId(), shopId, tradeKey));

            case "used": {
                int remaining = tdm.getRemainingTrades(player.getUniqueId(), shopId, tradeKey);
                return String.valueOf(effectiveMax - remaining);
            }

            case "max":
                return String.valueOf(effectiveMax);

            case "cooldown": {
                if (tradeConfig.getCooldownMode() == CooldownMode.NONE) {
                    int remaining = tdm.getRemainingTrades(player.getUniqueId(), shopId, tradeKey);
                    return remaining > 0 ? "Available" : "Sold out";
                }
                if (tdm.hasCooldownExpired(player.getUniqueId(), shopId, tradeKey)) {
                    return "Ready";
                }
                long timeLeft = tdm.getTimeUntilReset(player.getUniqueId(), shopId, tradeKey);
                return tdm.formatDuration(timeLeft);
            }

            case "resettime":
                return tdm.getResetTimeString(shopId, tradeKey);

            case "globalmax":
                return String.valueOf(tradeConfig.getMaxTrades());

            case "globalremaining":
                if (!shopConfig.isShared()) return String.valueOf(tradeConfig.getMaxTrades());
                return String.valueOf(tdm.getGlobalRemainingTrades(shopId, tradeKey));

            default:
                return null;
        }
    }

    private String resolvePoolPlaceholder(ShopConfig shopConfig, String poolName, String action) {
        PoolConfig pool = shopConfig.getPool(poolName);
        if (pool == null) return null;

        RotationState state = plugin.getPoolRotationManager().getState(shopConfig.getShopId(), poolName);
        return switch (action) {
            case "poolactive" -> state == null ? "" : String.join(",", state.getActiveItems());
            case "poolnext" -> {
                if (state == null) yield "";
                long secondsLeft = Math.max(0, state.getAdvancesAt() - ZonedDateTime.now().toEpochSecond());
                yield plugin.getTradeDataManager().formatDuration(secondsLeft);
            }
            default -> null;
        };
    }
}
