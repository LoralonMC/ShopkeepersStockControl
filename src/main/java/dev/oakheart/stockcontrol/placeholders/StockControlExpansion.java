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
 *
 * Tag-aggregate placeholders (sum across all shops carrying the tag):
 *   %ssc_tag_remaining_<tag>%             - Sum of remaining trades for the player across tagged shops
 *   %ssc_tag_max_<tag>%                   - Sum of effective max trades across tagged shops
 *   %ssc_tag_used_<tag>%                  - Sum of used trades (max - remaining) across tagged shops
 *   Unlimited trades are skipped from aggregates (they have no finite max to sum).
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

        // Tag-aggregate placeholders sum across all shops carrying a tag.
        // Format: %ssc_tag_<action>_<tag>% — handled before the shop:trade parser
        // because the aggregate form has no colon-separated trade key.
        if (params.startsWith("tag_remaining_")) {
            return String.valueOf(aggregateByTag(player, params.substring("tag_remaining_".length()), AggregateMode.REMAINING));
        }
        if (params.startsWith("tag_max_")) {
            return String.valueOf(aggregateByTag(player, params.substring("tag_max_".length()), AggregateMode.MAX));
        }
        if (params.startsWith("tag_used_")) {
            return String.valueOf(aggregateByTag(player, params.substring("tag_used_".length()), AggregateMode.USED));
        }

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

        // Determine effective max for the player (per-player cap for shared mode).
        // Render unlimited (-1) as "∞" — placeholders are display-only, so callers can
        // either show this or branch on it. The numeric `globalmax` placeholder still
        // returns the raw -1 for callers that key off it.
        boolean unlimitedEffective = tradeConfig.isUnlimited()
                && !(shopConfig.isShared() && tradeConfig.getMaxPerPlayer() > 0);
        int effectiveMax = (shopConfig.isShared() && tradeConfig.getMaxPerPlayer() > 0)
                ? tradeConfig.getMaxPerPlayer()
                : tradeConfig.getMaxTrades();

        switch (action) {
            case "remaining": {
                if (unlimitedEffective) return "∞";
                return String.valueOf(tdm.getRemainingTrades(player.getUniqueId(), shopId, tradeKey));
            }

            case "used": {
                int remaining = tdm.getRemainingTrades(player.getUniqueId(), shopId, tradeKey);
                if (unlimitedEffective) return String.valueOf(Math.max(0, TradeDataManager.UNLIMITED_REMAINING - remaining));
                return String.valueOf(effectiveMax - remaining);
            }

            case "max":
                return unlimitedEffective ? "∞" : String.valueOf(effectiveMax);

            case "cooldown": {
                if (tradeConfig.getCooldownMode() == CooldownMode.NONE) {
                    if (unlimitedEffective) return "Available";
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
                return tradeConfig.isUnlimited() ? "∞" : String.valueOf(tradeConfig.getMaxTrades());

            case "globalremaining":
                if (tradeConfig.isUnlimited()) return "∞";
                if (!shopConfig.isShared()) return String.valueOf(tradeConfig.getMaxTrades());
                return String.valueOf(tdm.getGlobalRemainingTrades(shopId, tradeKey));

            default:
                return null;
        }
    }

    private enum AggregateMode { REMAINING, MAX, USED }

    /**
     * Sums remaining / max / used trades across every shop tagged with {@code tag},
     * iterating both static trades and pool items. Unlimited trades are skipped
     * (they have no finite value to sum). Mirrors per-shop placeholder logic for
     * shared vs per_player mode so the aggregate is consistent with per-trade
     * placeholders.
     */
    private int aggregateByTag(OfflinePlayer player, String tag, AggregateMode mode) {
        if (tag == null || tag.isEmpty()) return 0;
        TradeDataManager tdm = plugin.getTradeDataManager();
        int sum = 0;
        for (ShopConfig shop : plugin.getConfigManager().getShopsByTag(tag)) {
            for (TradeConfig tradeConfig : shop.getAllTrades().values()) {
                boolean unlimitedEffective = tradeConfig.isUnlimited()
                        && !(shop.isShared() && tradeConfig.getMaxPerPlayer() > 0);
                if (unlimitedEffective) continue;

                int effectiveMax = (shop.isShared() && tradeConfig.getMaxPerPlayer() > 0)
                        ? tradeConfig.getMaxPerPlayer()
                        : tradeConfig.getMaxTrades();
                int remaining = tdm.getRemainingTrades(player.getUniqueId(), shop.getShopId(), tradeConfig.getTradeKey());

                switch (mode) {
                    case REMAINING -> sum += remaining;
                    case MAX -> sum += effectiveMax;
                    case USED -> sum += (effectiveMax - remaining);
                }
            }
        }
        return sum;
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
