package dev.oakheart.stockcontrol.commands;

import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.CooldownMode;
import dev.oakheart.stockcontrol.data.PlayerTradeData;
import dev.oakheart.stockcontrol.data.ShopConfig;
import dev.oakheart.stockcontrol.data.TradeConfig;
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

            case "info":
                return handleInfo(sender, args);

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
     * Resolves a shop argument to a ShopConfig, trying display name first then ID.
     *
     * @param arg The shop name or ID
     * @return The ShopConfig, or null if not found
     */
    private ShopConfig resolveShop(String arg) {
        ShopConfig shop = plugin.getConfigManager().getShopByName(arg);
        if (shop == null) {
            shop = plugin.getConfigManager().getShop(arg);
        }
        return shop;
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
        ShopConfig shop = resolveShop(args[2]);
        if (shop == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Shop not found: <white><shop>",
                    Placeholder.unparsed("shop", args[2])));
            return true;
        }
        String shopId = shop.getShopId();

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
                    Placeholder.unparsed("shop", shop.getName())));
            return true;
        }

        // Reset specific trade
        String tradeKey = args[3];
        plugin.getTradeDataManager().resetPlayerTrade(playerId, shopId, tradeKey);
        sender.sendMessage(miniMessage.deserialize(
                "<green>Reset trade <white><trade></white> for player <white><player></white> in shop <white><shop>",
                Placeholder.unparsed("trade", tradeKey),
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("shop", shop.getName())));

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
            ShopConfig filterShop = resolveShop(args[2]);
            if (filterShop == null) {
                sender.sendMessage(miniMessage.deserialize(
                        "<red>Shop not found: <white><shop>",
                        Placeholder.unparsed("shop", args[2])));
                return true;
            }
            String shopId = filterShop.getShopId();
            playerTrades = playerTrades.stream()
                    .filter(data -> data.getShopId().equals(shopId))
                    .collect(Collectors.toList());

            if (playerTrades.isEmpty()) {
                sender.sendMessage(miniMessage.deserialize(
                        "<yellow>Player <white><player></white> has no trades in shop <white><shop>",
                        Placeholder.unparsed("player", playerName),
                        Placeholder.unparsed("shop", filterShop.getName())));
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
                String resetTime = plugin.getTradeDataManager().getResetTimeString(data.getShopId(), data.getTradeKey());
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
     * Handles /ssc info <shop> - Shows shop configuration details
     */
    private boolean handleInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("shopkeepersstock.admin")) {
            sender.sendMessage(miniMessage.deserialize("<red>You don't have permission to use this command"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(miniMessage.deserialize("<red>Usage: /ssc info <shop>"));
            return true;
        }

        ShopConfig shop = resolveShop(args[1]);
        if (shop == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Shop not found: <white><shop>",
                    Placeholder.unparsed("shop", args[1])));
            return true;
        }

        // Header
        String shortId = shop.getShopId().length() > 8
                ? shop.getShopId().substring(0, 8) + "..."
                : shop.getShopId();
        sender.sendMessage(miniMessage.deserialize(
                "<green>=== Shop Info: <white><name></white> ===",
                Placeholder.unparsed("name", shop.getName())));
        sender.sendMessage(miniMessage.deserialize(
                "<aqua>ID: <white><id>",
                Placeholder.unparsed("id", shortId)));
        sender.sendMessage(miniMessage.deserialize(
                "<aqua>Enabled: <white><enabled>",
                Placeholder.unparsed("enabled", String.valueOf(shop.isEnabled()))));
        sender.sendMessage(miniMessage.deserialize(
                "<aqua>Cooldown Mode: <white><mode>",
                Placeholder.unparsed("mode", shop.getCooldownMode().name().toLowerCase())));

        if (shop.getCooldownMode() == CooldownMode.DAILY || shop.getCooldownMode() == CooldownMode.WEEKLY) {
            sender.sendMessage(miniMessage.deserialize(
                    "<aqua>Reset Time: <white><time>",
                    Placeholder.unparsed("time", shop.getResetTime())));
        }
        if (shop.getCooldownMode() == CooldownMode.WEEKLY) {
            String day = shop.getResetDay().charAt(0) + shop.getResetDay().substring(1).toLowerCase();
            sender.sendMessage(miniMessage.deserialize(
                    "<aqua>Reset Day: <white><day>",
                    Placeholder.unparsed("day", day)));
        }

        // Trades
        sender.sendMessage(miniMessage.deserialize(
                "<aqua>Trades: <white><count>",
                Placeholder.unparsed("count", String.valueOf(shop.getTrades().size()))));

        for (TradeConfig trade : shop.getTrades().values()) {
            StringBuilder details = new StringBuilder();
            details.append("slot ").append(trade.getSlot())
                    .append(", max ").append(trade.getMaxTrades());

            if (trade.getCooldownMode() != shop.getCooldownMode()) {
                details.append(", mode ").append(trade.getCooldownMode().name().toLowerCase());
            }
            if (trade.getCooldownMode() == CooldownMode.ROLLING) {
                details.append(", cooldown ").append(plugin.getTradeDataManager().formatDuration(trade.getCooldownSeconds()));
            }

            sender.sendMessage(miniMessage.deserialize(
                    "<gray>  - <white><key><gray> (<details>)",
                    Placeholder.unparsed("key", trade.getTradeKey()),
                    Placeholder.unparsed("details", details.toString())));
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

        // Update config on disk and refresh in-memory (lightweight, no trades reload)
        plugin.getConfig().set("debug", newDebug);
        plugin.saveConfig();
        plugin.getConfigManager().refreshMainConfig();

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
        sender.sendMessage(miniMessage.deserialize("<aqua>/ssc info <shop><white> - Show shop configuration details"));
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
            return Arrays.asList("reload", "reset", "check", "info", "debug", "cleanup", "help").stream()
                    .filter(sub -> sub.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        String sub = args[0].toLowerCase();

        if (args.length == 2 && (sub.equals("reset") || sub.equals("check"))) {
            // Suggest online player names
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && sub.equals("info")) {
            // Suggest shop display names
            return plugin.getConfigManager().getShops().values().stream()
                    .map(ShopConfig::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 3 && (sub.equals("reset") || sub.equals("check"))) {
            // Suggest shop display names
            return plugin.getConfigManager().getShops().values().stream()
                    .map(ShopConfig::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 4 && (sub.equals("reset") || sub.equals("check"))) {
            // Suggest trade keys for the specified shop (resolve by name first, then ID)
            ShopConfig shop = plugin.getConfigManager().getShopByName(args[2]);
            if (shop == null) {
                shop = plugin.getConfigManager().getShop(args[2]);
            }

            if (shop != null) {
                return shop.getTrades().keySet().stream()
                        .filter(tradeKey -> tradeKey.toLowerCase().startsWith(args[3].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }
}
