package dev.oakheart.stockcontrol.commands;

import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.PlayerTradeData;
import dev.oakheart.stockcontrol.data.ShopConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Command executor for /ssc (ShopkeepersStockControl) command.
 */
public class StockControlCommand implements CommandExecutor, TabCompleter {

    private final ShopkeepersStockControl plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public StockControlCommand(ShopkeepersStockControl plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase();

        switch (subcommand) {
            case "reload":
                return handleReload(sender);

            case "reset":
                return handleReset(sender, args);

            case "check":
                return handleCheck(sender, args);

            case "debug":
                return handleDebug(sender);

            case "cleanup":
                return handleCleanup(sender);

            case "help":
                sendHelp(sender);
                return true;

            default:
                sender.sendMessage(miniMessage.deserialize(
                        "<red>Unknown subcommand: <white><input>",
                        Placeholder.unparsed("input", subcommand)));
                sender.sendMessage(miniMessage.deserialize("<yellow>Use /ssc help for a list of commands"));
                return true;
        }
    }

    /**
     * Resolves a player name to a UUID, checking online players first then offline.
     *
     * @param playerName The player name to resolve
     * @return The player's UUID, or null if never played before
     */
    private UUID resolvePlayerUUID(String playerName) {
        Player online = Bukkit.getPlayer(playerName);
        if (online != null) {
            return online.getUniqueId();
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
        if (offline.hasPlayedBefore() || offline.isOnline()) {
            return offline.getUniqueId();
        }

        return null;
    }

    /**
     * Handles /ssc reload - Reloads configuration
     */
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("shopkeepersstock.admin")) {
            sender.sendMessage(miniMessage.deserialize("<red>You don't have permission to use this command"));
            return true;
        }

        sender.sendMessage(miniMessage.deserialize("<yellow>Reloading configuration..."));

        boolean success = plugin.getConfigManager().reload();

        if (success) {
            sender.sendMessage(miniMessage.deserialize("<green>Configuration reloaded successfully!"));
        } else {
            sender.sendMessage(miniMessage.deserialize("<red>Failed to reload configuration. Check console for errors."));
        }

        return true;
    }

    /**
     * Handles /ssc reset <player> [shop] [trade]
     */
    private boolean handleReset(CommandSender sender, String[] args) {
        if (!sender.hasPermission("shopkeepersstock.admin")) {
            sender.sendMessage(miniMessage.deserialize("<red>You don't have permission to use this command"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(miniMessage.deserialize("<red>Usage: /ssc reset <player> [shop] [trade]"));
            return true;
        }

        String playerName = args[1];
        UUID playerId = resolvePlayerUUID(playerName);

        if (playerId == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Player not found: <white><player>",
                    Placeholder.unparsed("player", playerName)));
            return true;
        }

        // Reset all trades for player
        if (args.length == 2) {
            plugin.getTradeDataManager().resetPlayerTrades(playerId);
            sender.sendMessage(miniMessage.deserialize(
                    "<green>Reset all trades for player <white><player>",
                    Placeholder.unparsed("player", playerName)));
            return true;
        }

        // Reset specific shop or trade
        String shopId = args[2];

        if (args.length == 3) {
            // Reset all trades for specific shop
            List<PlayerTradeData> playerTrades = plugin.getTradeDataManager().getPlayerTrades(playerId);
            int resetCount = 0;

            for (PlayerTradeData data : playerTrades) {
                if (data.getShopId().equals(shopId)) {
                    plugin.getTradeDataManager().resetPlayerTrade(playerId, shopId, data.getTradeKey());
                    resetCount++;
                }
            }

            sender.sendMessage(miniMessage.deserialize(
                    "<green>Reset <white><count></white> trades for player <white><player></white> in shop <white><shop>",
                    Placeholder.unparsed("count", String.valueOf(resetCount)),
                    Placeholder.unparsed("player", playerName),
                    Placeholder.unparsed("shop", shopId)));
            return true;
        }

        // Reset specific trade
        String tradeKey = args[3];
        plugin.getTradeDataManager().resetPlayerTrade(playerId, shopId, tradeKey);
        sender.sendMessage(miniMessage.deserialize(
                "<green>Reset trade <white><trade></white> for player <white><player></white> in shop <white><shop>",
                Placeholder.unparsed("trade", tradeKey),
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("shop", shopId)));

        return true;
    }

    /**
     * Handles /ssc check <player> [shop] [trade]
     */
    private boolean handleCheck(CommandSender sender, String[] args) {
        if (!sender.hasPermission("shopkeepersstock.admin")) {
            sender.sendMessage(miniMessage.deserialize("<red>You don't have permission to use this command"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(miniMessage.deserialize("<red>Usage: /ssc check <player> [shop] [trade]"));
            return true;
        }

        String playerName = args[1];
        UUID playerId = resolvePlayerUUID(playerName);

        if (playerId == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Player not found: <white><player>",
                    Placeholder.unparsed("player", playerName)));
            return true;
        }

        // Flush any pending writes for this player to ensure we get the latest data
        plugin.getTradeDataManager().flushPlayerData(playerId);

        List<PlayerTradeData> playerTrades = plugin.getTradeDataManager().getPlayerTrades(playerId);

        if (playerTrades.isEmpty()) {
            sender.sendMessage(miniMessage.deserialize(
                    "<yellow>Player <white><player></white> has no trade data",
                    Placeholder.unparsed("player", playerName)));
            return true;
        }

        // Filter by shop if specified
        if (args.length >= 3) {
            String shopId = args[2];
            playerTrades = playerTrades.stream()
                    .filter(data -> data.getShopId().equals(shopId))
                    .collect(Collectors.toList());

            if (playerTrades.isEmpty()) {
                sender.sendMessage(miniMessage.deserialize(
                        "<yellow>Player <white><player></white> has no trades in shop <white><shop>",
                        Placeholder.unparsed("player", playerName),
                        Placeholder.unparsed("shop", shopId)));
                return true;
            }
        }

        // Filter by trade if specified
        if (args.length >= 4) {
            String tradeKey = args[3];
            playerTrades = playerTrades.stream()
                    .filter(data -> data.getTradeKey().equals(tradeKey))
                    .collect(Collectors.toList());

            if (playerTrades.isEmpty()) {
                sender.sendMessage(miniMessage.deserialize(
                        "<yellow>Player <white><player></white> has no data for trade <white><trade>",
                        Placeholder.unparsed("player", playerName),
                        Placeholder.unparsed("trade", tradeKey)));
                return true;
            }
        }

        // Display trade data
        sender.sendMessage(miniMessage.deserialize(
                "<green>=== Trade data for <white><player></white> ===",
                Placeholder.unparsed("player", playerName)));

        for (PlayerTradeData data : playerTrades) {
            int remaining = plugin.getTradeDataManager().getRemainingTrades(playerId, data.getShopId(), data.getTradeKey());
            boolean cooldownExpired = plugin.getTradeDataManager().hasCooldownExpired(playerId, data.getShopId(), data.getTradeKey());
            long timeRemaining = cooldownExpired ? 0 : plugin.getTradeDataManager().getTimeUntilReset(playerId, data.getShopId(), data.getTradeKey());

            ShopConfig shop = plugin.getConfigManager().getShop(data.getShopId());
            int maxTrades = shop != null && shop.getTrade(data.getTradeKey()) != null
                    ? shop.getTrade(data.getTradeKey()).getMaxTrades()
                    : data.getTradesUsed();

            String shopName = shop != null ? shop.getName() : data.getShopId();

            // Show accurate "Used" count - if cooldown expired, it's effectively 0
            int usedCount = cooldownExpired ? 0 : data.getTradesUsed();

            sender.sendMessage(miniMessage.deserialize(
                    "<aqua>Shop: <white><shop>",
                    Placeholder.unparsed("shop", shopName)));
            sender.sendMessage(miniMessage.deserialize(
                    "<aqua>  Trade: <white><trade>",
                    Placeholder.unparsed("trade", data.getTradeKey())));
            sender.sendMessage(miniMessage.deserialize(
                    "<aqua>  Used: <white><used>/<max>",
                    Placeholder.unparsed("used", String.valueOf(usedCount)),
                    Placeholder.unparsed("max", String.valueOf(maxTrades))));
            sender.sendMessage(miniMessage.deserialize(
                    "<aqua>  Remaining: <white><remaining>",
                    Placeholder.unparsed("remaining", String.valueOf(remaining))));

            if (timeRemaining > 0) {
                String resetInfo = plugin.getTradeDataManager().formatDuration(timeRemaining);
                String resetTime = plugin.getTradeDataManager().getResetTimeString();
                if (!resetTime.isEmpty()) {
                    resetInfo += " (Resets at " + resetTime + ")";
                }
                sender.sendMessage(miniMessage.deserialize(
                        "<aqua>  Cooldown: <white><cooldown>",
                        Placeholder.unparsed("cooldown", resetInfo)));
            } else {
                sender.sendMessage(miniMessage.deserialize("<aqua>  Cooldown: <green>Ready"));
            }
        }

        return true;
    }

    /**
     * Handles /ssc debug - Toggles debug mode
     */
    private boolean handleDebug(CommandSender sender) {
        if (!sender.hasPermission("shopkeepersstock.admin")) {
            sender.sendMessage(miniMessage.deserialize("<red>You don't have permission to use this command"));
            return true;
        }

        boolean currentDebug = plugin.getConfigManager().isDebugMode();
        boolean newDebug = !currentDebug;

        // Update config in memory
        plugin.getConfig().set("debug", newDebug);
        plugin.saveConfig();
        plugin.getConfigManager().reload();

        if (newDebug) {
            sender.sendMessage(miniMessage.deserialize("<green>Debug mode enabled"));
        } else {
            sender.sendMessage(miniMessage.deserialize("<yellow>Debug mode disabled"));
        }

        return true;
    }

    /**
     * Handles /ssc cleanup - Manually triggers cleanup
     */
    private boolean handleCleanup(CommandSender sender) {
        if (!sender.hasPermission("shopkeepersstock.admin")) {
            sender.sendMessage(miniMessage.deserialize("<red>You don't have permission to use this command"));
            return true;
        }

        sender.sendMessage(miniMessage.deserialize("<yellow>Running manual cleanup..."));

        int cleaned = plugin.getCooldownManager().triggerManualCleanup();

        sender.sendMessage(miniMessage.deserialize(
                "<green>Cleanup complete! Removed <white><count></white> expired entries",
                Placeholder.unparsed("count", String.valueOf(cleaned))));

        return true;
    }

    /**
     * Sends help message
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(miniMessage.deserialize("<green>=== ShopkeepersStockControl Commands ==="));
        sender.sendMessage(miniMessage.deserialize("<aqua>/ssc reload<white> - Reload configuration"));
        sender.sendMessage(miniMessage.deserialize("<aqua>/ssc reset <player> [shop] [trade]<white> - Reset trade data"));
        sender.sendMessage(miniMessage.deserialize("<aqua>/ssc check <player> [shop] [trade]<white> - Check remaining trades"));
        sender.sendMessage(miniMessage.deserialize("<aqua>/ssc debug<white> - Toggle debug mode"));
        sender.sendMessage(miniMessage.deserialize("<aqua>/ssc cleanup<white> - Manually trigger cleanup"));
        sender.sendMessage(miniMessage.deserialize("<aqua>/ssc help<white> - Show this help message"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("shopkeepersstock.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            // Suggest subcommands
            return Arrays.asList("reload", "reset", "check", "debug", "cleanup", "help").stream()
                    .filter(sub -> sub.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("check"))) {
            // Suggest online player names
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 3 && (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("check"))) {
            // Suggest shop IDs
            return plugin.getConfigManager().getShops().keySet().stream()
                    .filter(shopId -> shopId.toLowerCase().startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 4 && (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("check"))) {
            // Suggest trade keys for the specified shop
            String shopId = args[2];
            ShopConfig shop = plugin.getConfigManager().getShop(shopId);

            if (shop != null) {
                return shop.getTrades().keySet().stream()
                        .filter(tradeKey -> tradeKey.toLowerCase().startsWith(args[3].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }
}
