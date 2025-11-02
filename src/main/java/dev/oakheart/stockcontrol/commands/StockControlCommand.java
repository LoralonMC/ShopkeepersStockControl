package dev.oakheart.stockcontrol.commands;

import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.PlayerTradeData;
import dev.oakheart.stockcontrol.data.ShopConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Command executor for /ssc (ShopkeepersStockControl) command.
 *
 * PHASE 6 - COMMANDS
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
                sender.sendMessage(miniMessage.deserialize("<red>Unknown subcommand: " + subcommand));
                sender.sendMessage(miniMessage.deserialize("<yellow>Use /ssc help for a list of commands"));
                return true;
        }
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
        Player target = Bukkit.getPlayer(playerName);
        UUID playerId = null;

        if (target != null) {
            playerId = target.getUniqueId();
        } else {
            // Try to find offline player
            try {
                playerId = Bukkit.getOfflinePlayer(playerName).getUniqueId();
            } catch (Exception e) {
                sender.sendMessage(miniMessage.deserialize("<red>Player not found: " + playerName));
                return true;
            }
        }

        // Reset all trades for player
        if (args.length == 2) {
            plugin.getTradeDataManager().resetPlayerTrades(playerId);
            sender.sendMessage(miniMessage.deserialize("<green>Reset all trades for player " + playerName));
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

            sender.sendMessage(miniMessage.deserialize("<green>Reset " + resetCount + " trades for player " + playerName + " in shop " + shopId));
            return true;
        }

        // Reset specific trade
        String tradeKey = args[3];
        plugin.getTradeDataManager().resetPlayerTrade(playerId, shopId, tradeKey);
        sender.sendMessage(miniMessage.deserialize("<green>Reset trade " + tradeKey + " for player " + playerName + " in shop " + shopId));

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
        Player target = Bukkit.getPlayer(playerName);
        UUID playerId = null;

        if (target != null) {
            playerId = target.getUniqueId();
        } else {
            // Try to find offline player
            try {
                playerId = Bukkit.getOfflinePlayer(playerName).getUniqueId();
            } catch (Exception e) {
                sender.sendMessage(miniMessage.deserialize("<red>Player not found: " + playerName));
                return true;
            }
        }

        List<PlayerTradeData> playerTrades = plugin.getTradeDataManager().getPlayerTrades(playerId);

        if (playerTrades.isEmpty()) {
            sender.sendMessage(miniMessage.deserialize("<yellow>Player " + playerName + " has no trade data"));
            return true;
        }

        // Filter by shop if specified
        if (args.length >= 3) {
            String shopId = args[2];
            playerTrades = playerTrades.stream()
                    .filter(data -> data.getShopId().equals(shopId))
                    .collect(Collectors.toList());

            if (playerTrades.isEmpty()) {
                sender.sendMessage(miniMessage.deserialize("<yellow>Player " + playerName + " has no trades in shop " + shopId));
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
                sender.sendMessage(miniMessage.deserialize("<yellow>Player " + playerName + " has no data for trade " + tradeKey));
                return true;
            }
        }

        // Display trade data
        sender.sendMessage(miniMessage.deserialize("<green>=== Trade data for " + playerName + " ==="));

        for (PlayerTradeData data : playerTrades) {
            int remaining = plugin.getTradeDataManager().getRemainingTrades(playerId, data.getShopId(), data.getTradeKey());
            long timeRemaining = plugin.getTradeDataManager().getTimeUntilReset(playerId, data.getShopId(), data.getTradeKey());

            ShopConfig shop = plugin.getConfigManager().getShop(data.getShopId());
            int maxTrades = shop != null && shop.getTrade(data.getTradeKey()) != null
                    ? shop.getTrade(data.getTradeKey()).getMaxTrades()
                    : data.getTradesUsed();

            String shopName = shop != null ? shop.getName() : data.getShopId();

            sender.sendMessage(miniMessage.deserialize("<aqua>Shop: <white>" + shopName));
            sender.sendMessage(miniMessage.deserialize("<aqua>  Trade: <white>" + data.getTradeKey()));
            sender.sendMessage(miniMessage.deserialize("<aqua>  Used: <white>" + data.getTradesUsed() + "/" + maxTrades));
            sender.sendMessage(miniMessage.deserialize("<aqua>  Remaining: <white>" + remaining));

            if (timeRemaining > 0) {
                sender.sendMessage(miniMessage.deserialize("<aqua>  Cooldown: <white>" + formatDuration(timeRemaining)));
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

        sender.sendMessage(miniMessage.deserialize("<green>Cleanup complete! Removed " + cleaned + " expired entries"));

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

    /**
     * Formats a duration in seconds to a human-readable string.
     */
    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        if (seconds < 86400) {
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            return hours + "h " + minutes + "m";
        }
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        return days + "d " + hours + "h";
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
