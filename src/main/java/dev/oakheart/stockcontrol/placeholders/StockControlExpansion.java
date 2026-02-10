package dev.oakheart.stockcontrol.placeholders;

import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.ShopConfig;
import dev.oakheart.stockcontrol.data.TradeConfig;
import dev.oakheart.stockcontrol.managers.TradeDataManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI expansion for ShopkeepersStockControl.
 *
 * Placeholder format: %ssc_<action>_<shop>:<trade>%
 * The shop identifier can be either the shop ID (from trades.yml) or its display name.
 *
 * Supported placeholders:
 *   %ssc_remaining_<shop>:<trade>%  - Remaining trades (e.g., "3")
 *   %ssc_used_<shop>:<trade>%       - Used trades (e.g., "2")
 *   %ssc_max_<shop>:<trade>%        - Max trades from config (e.g., "5")
 *   %ssc_cooldown_<shop>:<trade>%   - Formatted cooldown or "Ready"
 *   %ssc_resettime_<shop>:<trade>%  - Reset time display (e.g., "00:00", "Monday 00:00", "")
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
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
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
        String tradeKey = identifier.substring(colonIndex + 1);

        // Try shop ID first, then fall back to display name lookup
        ShopConfig shopConfig = plugin.getConfigManager().getShop(shopIdentifier);
        if (shopConfig == null) {
            shopConfig = plugin.getConfigManager().getShopByName(shopIdentifier);
        }
        if (shopConfig == null) return null;

        String shopId = shopConfig.getShopId();

        TradeConfig tradeConfig = shopConfig.getTrade(tradeKey);
        if (tradeConfig == null) return null;

        TradeDataManager tdm = plugin.getTradeDataManager();

        switch (action) {
            case "remaining":
                return String.valueOf(tdm.getRemainingTrades(player.getUniqueId(), shopId, tradeKey));

            case "used": {
                int remaining = tdm.getRemainingTrades(player.getUniqueId(), shopId, tradeKey);
                return String.valueOf(tradeConfig.getMaxTrades() - remaining);
            }

            case "max":
                return String.valueOf(tradeConfig.getMaxTrades());

            case "cooldown": {
                if (tdm.hasCooldownExpired(player.getUniqueId(), shopId, tradeKey)) {
                    return "Ready";
                }
                long timeLeft = tdm.getTimeUntilReset(player.getUniqueId(), shopId, tradeKey);
                return tdm.formatDuration(timeLeft);
            }

            case "resettime":
                return tdm.getResetTimeString(shopId, tradeKey);

            default:
                return null;
        }
    }
}
