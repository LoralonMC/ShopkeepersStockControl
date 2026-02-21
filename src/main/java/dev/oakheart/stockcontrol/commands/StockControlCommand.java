package dev.oakheart.stockcontrol.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.*;
import dev.oakheart.stockcontrol.message.MessageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("UnstableApiUsage")
public class StockControlCommand {

    private final ShopkeepersStockControl plugin;
    private final MessageManager messageManager;

    public StockControlCommand(ShopkeepersStockControl plugin) {
        this.plugin = plugin;
        this.messageManager = plugin.getMessageManager();
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
                .requires(src -> src.getSender().hasPermission("shopkeepersstock.help"))
                .executes(ctx -> {
                    sendHelp(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                // help
                .then(Commands.literal("help")
                        .requires(src -> src.getSender().hasPermission("shopkeepersstock.help"))
                        .executes(ctx -> {
                            sendHelp(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // reload
                .then(Commands.literal("reload")
                        .requires(src -> src.getSender().hasPermission("shopkeepersstock.reload"))
                        .executes(ctx -> {
                            handleReload(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // debug
                .then(Commands.literal("debug")
                        .requires(src -> src.getSender().hasPermission("shopkeepersstock.debug"))
                        .executes(ctx -> {
                            handleDebug(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // cleanup
                .then(Commands.literal("cleanup")
                        .requires(src -> src.getSender().hasPermission("shopkeepersstock.cleanup"))
                        .executes(ctx -> {
                            handleCleanup(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // info <shop>
                .then(Commands.literal("info")
                        .requires(src -> src.getSender().hasPermission("shopkeepersstock.info"))
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
                        .requires(src -> src.getSender().hasPermission("shopkeepersstock.restock"))
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
                        .requires(src -> src.getSender().hasPermission("shopkeepersstock.reset"))
                        .then(buildPlayerShopTradeArgs(true)))
                // check <player> [shop] [trade]
                .then(Commands.literal("check")
                        .requires(src -> src.getSender().hasPermission("shopkeepersstock.check"))
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
        messageManager.sendMultiline(sender, MessageManager.HELP);
    }

    // ===== Reload =====

    private void handleReload(CommandSender sender) {
        messageManager.send(sender, MessageManager.RELOAD_START);

        boolean success = plugin.reloadPluginConfig();

        if (success) {
            messageManager.send(sender, MessageManager.RELOAD_SUCCESS);
        } else {
            messageManager.send(sender, MessageManager.RELOAD_FAILED);
        }
    }

    // ===== Debug =====

    private void handleDebug(CommandSender sender) {
        boolean currentDebug = plugin.getConfigManager().isDebugMode();
        boolean newDebug = !currentDebug;

        plugin.getConfigManager().setDebugMode(newDebug);

        if (newDebug) {
            messageManager.send(sender, MessageManager.DEBUG_ENABLED);
        } else {
            messageManager.send(sender, MessageManager.DEBUG_DISABLED);
        }
    }

    // ===== Cleanup =====

    private void handleCleanup(CommandSender sender) {
        messageManager.send(sender, MessageManager.CLEANUP_START);

        int cleaned = plugin.getCooldownManager().triggerManualCleanup();

        messageManager.send(sender, MessageManager.CLEANUP_COMPLETE,
                Map.of("count", String.valueOf(cleaned)));
    }

    // ===== Info =====

    private void handleInfo(CommandSender sender, String shopArg) {
        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            messageManager.send(sender, MessageManager.ERROR_SHOP_NOT_FOUND, Map.of("shop", shopArg));
            return;
        }

        String shortId = shop.getShopId().length() > 8
                ? shop.getShopId().substring(0, 8) + "..."
                : shop.getShopId();

        messageManager.send(sender, MessageManager.INFO_HEADER, Map.of("name", shop.getName()));
        messageManager.send(sender, MessageManager.INFO_ID, Map.of("id", shortId));
        messageManager.send(sender, MessageManager.INFO_ENABLED, Map.of("enabled", String.valueOf(shop.isEnabled())));
        messageManager.send(sender, MessageManager.INFO_STOCK_MODE, Map.of("mode", shop.getStockMode().name().toLowerCase()));

        String cooldownDisplay = shop.getCooldownMode() == CooldownMode.NONE
                ? "none (manual restock)"
                : shop.getCooldownMode().name().toLowerCase();
        messageManager.send(sender, MessageManager.INFO_COOLDOWN_MODE, Map.of("mode", cooldownDisplay));

        if (shop.getCooldownMode() == CooldownMode.DAILY || shop.getCooldownMode() == CooldownMode.WEEKLY) {
            messageManager.send(sender, MessageManager.INFO_RESET_TIME, Map.of("time", shop.getResetTime()));
        }
        if (shop.getCooldownMode() == CooldownMode.WEEKLY) {
            String day = shop.getResetDay().charAt(0) + shop.getResetDay().substring(1).toLowerCase();
            messageManager.send(sender, MessageManager.INFO_RESET_DAY, Map.of("day", day));
        }

        if (shop.isShared() && shop.getMaxPerPlayer() > 0) {
            messageManager.send(sender, MessageManager.INFO_PER_PLAYER_CAP,
                    Map.of("cap", String.valueOf(shop.getMaxPerPlayer())));
        }

        messageManager.send(sender, MessageManager.INFO_TRADES_COUNT,
                Map.of("count", String.valueOf(shop.getTrades().size())));

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

            messageManager.send(sender, MessageManager.INFO_TRADE_ENTRY,
                    Map.of("key", trade.getTradeKey(), "details", details.toString()));
        }
    }

    // ===== Reset handlers =====

    private void handleResetPlayer(CommandSender sender, String playerName) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            messageManager.send(sender, MessageManager.ERROR_PLAYER_NOT_FOUND, Map.of("player", playerName));
            return;
        }

        plugin.getTradeDataManager().resetPlayerTrades(playerId);
        messageManager.send(sender, MessageManager.RESET_PLAYER, Map.of("player", playerName));
    }

    private void handleResetPlayerShop(CommandSender sender, String playerName, String shopArg) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            messageManager.send(sender, MessageManager.ERROR_PLAYER_NOT_FOUND, Map.of("player", playerName));
            return;
        }

        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            messageManager.send(sender, MessageManager.ERROR_SHOP_NOT_FOUND, Map.of("shop", shopArg));
            return;
        }

        plugin.getTradeDataManager().resetPlayerShopTrades(playerId, shop.getShopId());
        messageManager.send(sender, MessageManager.RESET_PLAYER_SHOP,
                Map.of("player", playerName, "shop", shop.getName()));
    }

    private void handleResetPlayerShopTrade(CommandSender sender, String playerName, String shopArg, String tradeKey) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            messageManager.send(sender, MessageManager.ERROR_PLAYER_NOT_FOUND, Map.of("player", playerName));
            return;
        }

        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            messageManager.send(sender, MessageManager.ERROR_SHOP_NOT_FOUND, Map.of("shop", shopArg));
            return;
        }

        plugin.getTradeDataManager().resetPlayerTrade(playerId, shop.getShopId(), tradeKey);
        messageManager.send(sender, MessageManager.RESET_PLAYER_SHOP_TRADE,
                Map.of("trade", tradeKey, "player", playerName, "shop", shop.getName()));
    }

    // ===== Restock handlers =====

    private void handleRestockShop(CommandSender sender, String shopArg) {
        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            messageManager.send(sender, MessageManager.ERROR_SHOP_NOT_FOUND, Map.of("shop", shopArg));
            return;
        }

        if (!shop.isShared()) {
            messageManager.send(sender, MessageManager.ERROR_NOT_SHARED, Map.of("shop", shop.getName()));
            return;
        }

        plugin.getTradeDataManager().resetGlobalShop(shop.getShopId());
        messageManager.send(sender, MessageManager.RESTOCK_SHOP, Map.of("shop", shop.getName()));
    }

    private void handleRestockTrade(CommandSender sender, String shopArg, String tradeKey) {
        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            messageManager.send(sender, MessageManager.ERROR_SHOP_NOT_FOUND, Map.of("shop", shopArg));
            return;
        }

        if (!shop.isShared()) {
            messageManager.send(sender, MessageManager.ERROR_NOT_SHARED, Map.of("shop", shop.getName()));
            return;
        }

        TradeConfig trade = shop.getTrade(tradeKey);
        if (trade == null) {
            messageManager.send(sender, MessageManager.ERROR_TRADE_NOT_FOUND,
                    Map.of("trade", tradeKey, "shop", shop.getName()));
            return;
        }

        plugin.getTradeDataManager().resetGlobalTrade(shop.getShopId(), tradeKey);
        messageManager.send(sender, MessageManager.RESTOCK_TRADE,
                Map.of("trade", tradeKey, "shop", shop.getName()));
    }

    // ===== Check handlers =====

    private void handleCheckPlayer(CommandSender sender, String playerName) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            messageManager.send(sender, MessageManager.ERROR_PLAYER_NOT_FOUND, Map.of("player", playerName));
            return;
        }

        plugin.getTradeDataManager().flushPlayerData(playerId);
        List<PlayerTradeData> playerTrades = plugin.getTradeDataManager().getPlayerTrades(playerId);

        if (playerTrades.isEmpty()) {
            messageManager.send(sender, MessageManager.CHECK_NO_DATA, Map.of("player", playerName));
            return;
        }

        displayTradeData(sender, playerName, playerId, playerTrades);
    }

    private void handleCheckPlayerShop(CommandSender sender, String playerName, String shopArg) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            messageManager.send(sender, MessageManager.ERROR_PLAYER_NOT_FOUND, Map.of("player", playerName));
            return;
        }

        ShopConfig filterShop = resolveShop(shopArg);
        if (filterShop == null) {
            messageManager.send(sender, MessageManager.ERROR_SHOP_NOT_FOUND, Map.of("shop", shopArg));
            return;
        }

        plugin.getTradeDataManager().flushPlayerData(playerId);
        String shopId = filterShop.getShopId();
        List<PlayerTradeData> playerTrades = plugin.getTradeDataManager().getPlayerTrades(playerId).stream()
                .filter(data -> data.getShopId().equals(shopId))
                .toList();

        if (playerTrades.isEmpty()) {
            messageManager.send(sender, MessageManager.CHECK_NO_DATA_SHOP,
                    Map.of("player", playerName, "shop", filterShop.getName()));
            return;
        }

        displayTradeData(sender, playerName, playerId, playerTrades);
    }

    private void handleCheckPlayerShopTrade(CommandSender sender, String playerName, String shopArg, String tradeKey) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            messageManager.send(sender, MessageManager.ERROR_PLAYER_NOT_FOUND, Map.of("player", playerName));
            return;
        }

        ShopConfig filterShop = resolveShop(shopArg);
        if (filterShop == null) {
            messageManager.send(sender, MessageManager.ERROR_SHOP_NOT_FOUND, Map.of("shop", shopArg));
            return;
        }

        plugin.getTradeDataManager().flushPlayerData(playerId);
        String shopId = filterShop.getShopId();
        List<PlayerTradeData> playerTrades = plugin.getTradeDataManager().getPlayerTrades(playerId).stream()
                .filter(data -> data.getShopId().equals(shopId) && data.getTradeKey().equals(tradeKey))
                .toList();

        if (playerTrades.isEmpty()) {
            messageManager.send(sender, MessageManager.CHECK_NO_DATA_TRADE,
                    Map.of("player", playerName, "trade", tradeKey));
            return;
        }

        displayTradeData(sender, playerName, playerId, playerTrades);
    }

    private void displayTradeData(CommandSender sender, String playerName, UUID playerId, List<PlayerTradeData> playerTrades) {
        messageManager.send(sender, MessageManager.CHECK_HEADER, Map.of("player", playerName));

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

            messageManager.send(sender, MessageManager.CHECK_SHOP, Map.of("shop", shopName));
            messageManager.send(sender, MessageManager.CHECK_TRADE, Map.of("trade", data.getTradeKey()));
            messageManager.send(sender, MessageManager.CHECK_USED,
                    Map.of("used", String.valueOf(usedCount), "max", String.valueOf(maxTrades)));
            messageManager.send(sender, MessageManager.CHECK_REMAINING,
                    Map.of("remaining", String.valueOf(remaining)));

            if (timeRemaining < 0) {
                messageManager.send(sender, MessageManager.CHECK_COOLDOWN_NEVER);
            } else if (timeRemaining > 0) {
                String resetInfo = plugin.getTradeDataManager().formatDuration(timeRemaining);
                String resetTime = plugin.getTradeDataManager().getResetTimeString(data.getShopId(), data.getTradeKey());
                if (!resetTime.isEmpty() && !resetTime.equals("Never")) {
                    resetInfo += " (Resets at " + resetTime + ")";
                }
                messageManager.send(sender, MessageManager.CHECK_COOLDOWN_ACTIVE,
                        Map.of("cooldown", resetInfo));
            } else {
                messageManager.send(sender, MessageManager.CHECK_COOLDOWN_READY);
            }
        }
    }
}
