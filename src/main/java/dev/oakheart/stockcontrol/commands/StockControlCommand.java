package dev.oakheart.stockcontrol.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.*;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@SuppressWarnings("UnstableApiUsage")
public class StockControlCommand {

    private final ShopkeepersStockControl plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public StockControlCommand(ShopkeepersStockControl plugin) {
        this.plugin = plugin;
    }

    public void register() {
        LifecycleEventManager<Plugin> manager = plugin.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register(buildCommand(), "ShopkeepersStockControl main command",
                    List.of("shopkeepersstock", "stockcontrol"));
        });
    }

    private LiteralCommandNode<CommandSourceStack> buildCommand() {
        return Commands.literal("ssc")
                .requires(src -> src.getSender().hasPermission("shopkeepersstock.admin"))
                .executes(ctx -> {
                    sendHelp(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                // help
                .then(Commands.literal("help")
                        .executes(ctx -> {
                            sendHelp(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // reload
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            handleReload(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // debug
                .then(Commands.literal("debug")
                        .executes(ctx -> {
                            handleDebug(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // cleanup
                .then(Commands.literal("cleanup")
                        .executes(ctx -> {
                            handleCleanup(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // info <shop>
                .then(Commands.literal("info")
                        .then(Commands.argument("shop", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String input = builder.getRemainingLowerCase();
                                    for (ShopConfig shop : plugin.getConfigManager().getShops().values()) {
                                        String name = commandName(shop.getName());
                                        if (name.toLowerCase().startsWith(input)) {
                                            builder.suggest(name);
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    handleInfo(ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, "shop"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                // restock <shop> [trade]
                .then(Commands.literal("restock")
                        .then(Commands.argument("shop", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String input = builder.getRemainingLowerCase();
                                    for (ShopConfig shop : plugin.getConfigManager().getShops().values()) {
                                        if (shop.isShared()) {
                                            String name = commandName(shop.getName());
                                            if (name.toLowerCase().startsWith(input)) {
                                                builder.suggest(name);
                                            }
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    handleRestockShop(ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, "shop"));
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("trade", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            String shopArg = StringArgumentType.getString(ctx, "shop");
                                            ShopConfig shop = resolveShop(shopArg);
                                            if (shop != null && shop.isShared()) {
                                                String input = builder.getRemainingLowerCase();
                                                for (String tradeKey : shop.getTrades().keySet()) {
                                                    if (tradeKey.toLowerCase().startsWith(input)) {
                                                        builder.suggest(tradeKey);
                                                    }
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            handleRestockTrade(ctx.getSource().getSender(),
                                                    StringArgumentType.getString(ctx, "shop"),
                                                    StringArgumentType.getString(ctx, "trade"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                // reset <player> [shop] [trade]
                .then(Commands.literal("reset")
                        .then(buildPlayerShopTradeArgs(true)))
                // check <player> [shop] [trade]
                .then(Commands.literal("check")
                        .then(buildPlayerShopTradeArgs(false)))
                .build();
    }

    /**
     * Builds the nested <player> [shop] [trade] argument chain for reset/check.
     */
    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> buildPlayerShopTradeArgs(boolean isReset) {
        return Commands.argument("player", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    String input = builder.getRemainingLowerCase();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getName().toLowerCase().startsWith(input)) {
                            builder.suggest(p.getName());
                        }
                    }
                    return builder.buildFuture();
                })
                .executes(ctx -> {
                    String playerName = StringArgumentType.getString(ctx, "player");
                    if (isReset) {
                        handleResetPlayer(ctx.getSource().getSender(), playerName);
                    } else {
                        handleCheckPlayer(ctx.getSource().getSender(), playerName);
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("shopArg", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            String input = builder.getRemainingLowerCase();
                            for (ShopConfig shop : plugin.getConfigManager().getShops().values()) {
                                String name = commandName(shop.getName());
                                if (name.toLowerCase().startsWith(input)) {
                                    builder.suggest(name);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String playerName = StringArgumentType.getString(ctx, "player");
                            String shopArg = StringArgumentType.getString(ctx, "shopArg");
                            if (isReset) {
                                handleResetPlayerShop(ctx.getSource().getSender(), playerName, shopArg);
                            } else {
                                handleCheckPlayerShop(ctx.getSource().getSender(), playerName, shopArg);
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("trade", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String shopArg = StringArgumentType.getString(ctx, "shopArg");
                                    ShopConfig shop = resolveShop(shopArg);
                                    if (shop != null) {
                                        String input = builder.getRemainingLowerCase();
                                        for (String tradeKey : shop.getTrades().keySet()) {
                                            if (tradeKey.toLowerCase().startsWith(input)) {
                                                builder.suggest(tradeKey);
                                            }
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    String playerName = StringArgumentType.getString(ctx, "player");
                                    String shopArg = StringArgumentType.getString(ctx, "shopArg");
                                    String tradeKey = StringArgumentType.getString(ctx, "trade");
                                    if (isReset) {
                                        handleResetPlayerShopTrade(ctx.getSource().getSender(), playerName, shopArg, tradeKey);
                                    } else {
                                        handleCheckPlayerShopTrade(ctx.getSource().getSender(), playerName, shopArg, tradeKey);
                                    }
                                    return Command.SINGLE_SUCCESS;
                                })));
    }

    // ===== Helpers =====

    private UUID resolvePlayerUUID(String playerName) {
        Player online = Bukkit.getPlayer(playerName);
        if (online != null) {
            return online.getUniqueId();
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
        if (offline.hasPlayedBefore() || offline.isOnline()) {
            return offline.getUniqueId();
        }

        return null;
    }

    private ShopConfig resolveShop(String arg) {
        ShopConfig shop = plugin.getConfigManager().getShopByName(arg);
        if (shop == null) {
            shop = plugin.getConfigManager().getShop(arg);
        }
        return shop;
    }

    /**
     * Returns a command-friendly version of a shop name (spaces replaced with underscores).
     */
    private static String commandName(String name) {
        return name.replace(' ', '_');
    }

    // ===== Help =====

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(miniMessage.deserialize("<green>=== ShopkeepersStockControl Commands ==="));
        sender.sendMessage(miniMessage.deserialize("<aqua>/ssc reload<white> - Reload configuration"));
        sender.sendMessage(miniMessage.deserialize("<aqua>/ssc reset <player> [shop] [trade]<white> - Reset trade data"));
        sender.sendMessage(miniMessage.deserialize("<aqua>/ssc check <player> [shop] [trade]<white> - Check remaining trades"));
        sender.sendMessage(miniMessage.deserialize("<aqua>/ssc restock <shop> [trade]<white> - Restock a shared shop"));
        sender.sendMessage(miniMessage.deserialize("<aqua>/ssc info <shop><white> - Show shop configuration details"));
        sender.sendMessage(miniMessage.deserialize("<aqua>/ssc debug<white> - Toggle debug mode"));
        sender.sendMessage(miniMessage.deserialize("<aqua>/ssc cleanup<white> - Manually trigger cleanup"));
        sender.sendMessage(miniMessage.deserialize("<aqua>/ssc help<white> - Show this help message"));
    }

    // ===== Reload =====

    private void handleReload(CommandSender sender) {
        sender.sendMessage(miniMessage.deserialize("<yellow>Reloading configuration..."));

        boolean success = plugin.reloadPluginConfig();

        if (success) {
            sender.sendMessage(miniMessage.deserialize("<green>Configuration reloaded successfully!"));
        } else {
            sender.sendMessage(miniMessage.deserialize("<red>Failed to reload configuration. Check console for errors."));
        }
    }

    // ===== Debug =====

    private void handleDebug(CommandSender sender) {
        boolean currentDebug = plugin.getConfigManager().isDebugMode();
        boolean newDebug = !currentDebug;

        plugin.getConfigManager().setDebugMode(newDebug);

        if (newDebug) {
            sender.sendMessage(miniMessage.deserialize("<green>Debug mode enabled"));
        } else {
            sender.sendMessage(miniMessage.deserialize("<yellow>Debug mode disabled"));
        }
    }

    // ===== Cleanup =====

    private void handleCleanup(CommandSender sender) {
        sender.sendMessage(miniMessage.deserialize("<yellow>Running manual cleanup..."));

        int cleaned = plugin.getCooldownManager().triggerManualCleanup();

        sender.sendMessage(miniMessage.deserialize(
                "<green>Cleanup complete! Removed <white><count></white> expired entries",
                Placeholder.unparsed("count", String.valueOf(cleaned))));
    }

    // ===== Info =====

    private void handleInfo(CommandSender sender, String shopArg) {
        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Shop not found: <white><shop>",
                    Placeholder.unparsed("shop", shopArg)));
            return;
        }

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
                "<aqua>Stock Mode: <white><mode>",
                Placeholder.unparsed("mode", shop.getStockMode().name().toLowerCase())));

        String cooldownDisplay = shop.getCooldownMode() == CooldownMode.NONE
                ? "none (manual restock)"
                : shop.getCooldownMode().name().toLowerCase();
        sender.sendMessage(miniMessage.deserialize(
                "<aqua>Cooldown Mode: <white><mode>",
                Placeholder.unparsed("mode", cooldownDisplay)));

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

        if (shop.isShared() && shop.getMaxPerPlayer() > 0) {
            sender.sendMessage(miniMessage.deserialize(
                    "<aqua>Default Per-Player Cap: <white><cap>",
                    Placeholder.unparsed("cap", String.valueOf(shop.getMaxPerPlayer()))));
        }

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
            if (shop.isShared() && trade.getMaxPerPlayer() > 0 && trade.getMaxPerPlayer() != shop.getMaxPerPlayer()) {
                details.append(", per-player ").append(trade.getMaxPerPlayer());
            }

            sender.sendMessage(miniMessage.deserialize(
                    "<gray>  - <white><key><gray> (<details>)",
                    Placeholder.unparsed("key", trade.getTradeKey()),
                    Placeholder.unparsed("details", details.toString())));
        }
    }

    // ===== Reset handlers =====

    private void handleResetPlayer(CommandSender sender, String playerName) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Player not found: <white><player>",
                    Placeholder.unparsed("player", playerName)));
            return;
        }

        plugin.getTradeDataManager().resetPlayerTrades(playerId);
        sender.sendMessage(miniMessage.deserialize(
                "<green>Reset all trades for player <white><player>",
                Placeholder.unparsed("player", playerName)));
    }

    private void handleResetPlayerShop(CommandSender sender, String playerName, String shopArg) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Player not found: <white><player>",
                    Placeholder.unparsed("player", playerName)));
            return;
        }

        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Shop not found: <white><shop>",
                    Placeholder.unparsed("shop", shopArg)));
            return;
        }

        String shopId = shop.getShopId();
        plugin.getTradeDataManager().resetPlayerShopTrades(playerId, shopId);

        sender.sendMessage(miniMessage.deserialize(
                "<green>Reset trades for player <white><player></white> in shop <white><shop>",
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("shop", shop.getName())));
    }

    private void handleResetPlayerShopTrade(CommandSender sender, String playerName, String shopArg, String tradeKey) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Player not found: <white><player>",
                    Placeholder.unparsed("player", playerName)));
            return;
        }

        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Shop not found: <white><shop>",
                    Placeholder.unparsed("shop", shopArg)));
            return;
        }

        plugin.getTradeDataManager().resetPlayerTrade(playerId, shop.getShopId(), tradeKey);
        sender.sendMessage(miniMessage.deserialize(
                "<green>Reset trade <white><trade></white> for player <white><player></white> in shop <white><shop>",
                Placeholder.unparsed("trade", tradeKey),
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("shop", shop.getName())));
    }

    // ===== Restock handlers =====

    private void handleRestockShop(CommandSender sender, String shopArg) {
        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Shop not found: <white><shop>",
                    Placeholder.unparsed("shop", shopArg)));
            return;
        }

        if (!shop.isShared()) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Shop <white><shop></white> is not in shared stock mode. Use <aqua>/ssc reset</aqua> for per-player shops.",
                    Placeholder.unparsed("shop", shop.getName())));
            return;
        }

        plugin.getTradeDataManager().resetGlobalShop(shop.getShopId());
        sender.sendMessage(miniMessage.deserialize(
                "<green>Restocked all trades in shop <white><shop>",
                Placeholder.unparsed("shop", shop.getName())));
    }

    private void handleRestockTrade(CommandSender sender, String shopArg, String tradeKey) {
        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Shop not found: <white><shop>",
                    Placeholder.unparsed("shop", shopArg)));
            return;
        }

        if (!shop.isShared()) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Shop <white><shop></white> is not in shared stock mode. Use <aqua>/ssc reset</aqua> for per-player shops.",
                    Placeholder.unparsed("shop", shop.getName())));
            return;
        }

        TradeConfig trade = shop.getTrade(tradeKey);
        if (trade == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Trade not found: <white><trade></white> in shop <white><shop>",
                    Placeholder.unparsed("trade", tradeKey),
                    Placeholder.unparsed("shop", shop.getName())));
            return;
        }

        plugin.getTradeDataManager().resetGlobalTrade(shop.getShopId(), tradeKey);
        sender.sendMessage(miniMessage.deserialize(
                "<green>Restocked trade <white><trade></white> in shop <white><shop>",
                Placeholder.unparsed("trade", tradeKey),
                Placeholder.unparsed("shop", shop.getName())));
    }

    // ===== Check handlers =====

    private void handleCheckPlayer(CommandSender sender, String playerName) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Player not found: <white><player>",
                    Placeholder.unparsed("player", playerName)));
            return;
        }

        plugin.getTradeDataManager().flushPlayerData(playerId);
        List<PlayerTradeData> playerTrades = plugin.getTradeDataManager().getPlayerTrades(playerId);

        if (playerTrades.isEmpty()) {
            sender.sendMessage(miniMessage.deserialize(
                    "<yellow>Player <white><player></white> has no trade data",
                    Placeholder.unparsed("player", playerName)));
            return;
        }

        displayTradeData(sender, playerName, playerId, playerTrades);
    }

    private void handleCheckPlayerShop(CommandSender sender, String playerName, String shopArg) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Player not found: <white><player>",
                    Placeholder.unparsed("player", playerName)));
            return;
        }

        ShopConfig filterShop = resolveShop(shopArg);
        if (filterShop == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Shop not found: <white><shop>",
                    Placeholder.unparsed("shop", shopArg)));
            return;
        }

        plugin.getTradeDataManager().flushPlayerData(playerId);
        String shopId = filterShop.getShopId();
        List<PlayerTradeData> playerTrades = plugin.getTradeDataManager().getPlayerTrades(playerId).stream()
                .filter(data -> data.getShopId().equals(shopId))
                .collect(Collectors.toList());

        if (playerTrades.isEmpty()) {
            sender.sendMessage(miniMessage.deserialize(
                    "<yellow>Player <white><player></white> has no trades in shop <white><shop>",
                    Placeholder.unparsed("player", playerName),
                    Placeholder.unparsed("shop", filterShop.getName())));
            return;
        }

        displayTradeData(sender, playerName, playerId, playerTrades);
    }

    private void handleCheckPlayerShopTrade(CommandSender sender, String playerName, String shopArg, String tradeKey) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Player not found: <white><player>",
                    Placeholder.unparsed("player", playerName)));
            return;
        }

        ShopConfig filterShop = resolveShop(shopArg);
        if (filterShop == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Shop not found: <white><shop>",
                    Placeholder.unparsed("shop", shopArg)));
            return;
        }

        plugin.getTradeDataManager().flushPlayerData(playerId);
        String shopId = filterShop.getShopId();
        List<PlayerTradeData> playerTrades = plugin.getTradeDataManager().getPlayerTrades(playerId).stream()
                .filter(data -> data.getShopId().equals(shopId) && data.getTradeKey().equals(tradeKey))
                .collect(Collectors.toList());

        if (playerTrades.isEmpty()) {
            sender.sendMessage(miniMessage.deserialize(
                    "<yellow>Player <white><player></white> has no data for trade <white><trade>",
                    Placeholder.unparsed("player", playerName),
                    Placeholder.unparsed("trade", tradeKey)));
            return;
        }

        displayTradeData(sender, playerName, playerId, playerTrades);
    }

    private void displayTradeData(CommandSender sender, String playerName, UUID playerId, List<PlayerTradeData> playerTrades) {
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

            if (timeRemaining < 0) {
                // NONE mode — never resets
                sender.sendMessage(miniMessage.deserialize("<aqua>  Cooldown: <yellow>Never (manual restock)"));
            } else if (timeRemaining > 0) {
                String resetInfo = plugin.getTradeDataManager().formatDuration(timeRemaining);
                String resetTime = plugin.getTradeDataManager().getResetTimeString(data.getShopId(), data.getTradeKey());
                if (!resetTime.isEmpty() && !resetTime.equals("Never")) {
                    resetInfo += " (Resets at " + resetTime + ")";
                }
                sender.sendMessage(miniMessage.deserialize(
                        "<aqua>  Cooldown: <white><cooldown>",
                        Placeholder.unparsed("cooldown", resetInfo)));
            } else {
                sender.sendMessage(miniMessage.deserialize("<aqua>  Cooldown: <green>Ready"));
            }
        }
    }
}
