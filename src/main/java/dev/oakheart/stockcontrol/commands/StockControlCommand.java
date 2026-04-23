package dev.oakheart.stockcontrol.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.oakheart.command.CommandRegistrar;
import dev.oakheart.message.MessageManager;
import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.*;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

@SuppressWarnings("UnstableApiUsage")
public class StockControlCommand {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final ShopkeepersStockControl plugin;
    private final MessageManager messageManager;

    public StockControlCommand(ShopkeepersStockControl plugin) {
        this.plugin = plugin;
        this.messageManager = plugin.getMessageManager();
    }

    public void register() {
        CommandRegistrar.register(plugin, buildCommand(),
                "ShopkeepersStockControl main command",
                List.of("shopkeepersstock", "stockcontrol"));
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
                // rotation peek <shop> | rotation force <shop> [pool]
                .then(Commands.literal("rotation")
                        .requires(src -> src.getSender().hasPermission("shopkeepersstock.rotation"))
                        .then(Commands.literal("peek")
                                .then(Commands.argument("shop", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestPooledShops(builder))
                                        .executes(ctx -> {
                                            handleRotationPeek(ctx.getSource().getSender(),
                                                    StringArgumentType.getString(ctx, "shop"));
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .then(Commands.literal("force")
                                .then(Commands.argument("shop", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestPooledShops(builder))
                                        .executes(ctx -> {
                                            handleRotationForce(ctx.getSource().getSender(),
                                                    StringArgumentType.getString(ctx, "shop"), null);
                                            return Command.SINGLE_SUCCESS;
                                        })
                                        .then(Commands.argument("pool", StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    ShopConfig shop = resolveShop(StringArgumentType.getString(ctx, "shop"));
                                                    if (shop != null) {
                                                        String input = builder.getRemainingLowerCase();
                                                        for (String poolName : shop.getPools().keySet()) {
                                                            if (poolName.toLowerCase().startsWith(input)) {
                                                                builder.suggest(poolName);
                                                            }
                                                        }
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> {
                                                    handleRotationForce(ctx.getSource().getSender(),
                                                            StringArgumentType.getString(ctx, "shop"),
                                                            StringArgumentType.getString(ctx, "pool"));
                                                    return Command.SINGLE_SUCCESS;
                                                })))))
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
        List<String> lines = messageManager.getConfig().getStringList("commands.help");
        if (lines.isEmpty()) return;
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                sender.sendMessage(MINI_MESSAGE.deserialize(line));
            }
        }
    }

    // ===== Reload =====

    private void handleReload(CommandSender sender) {
        messageManager.sendCommand(sender, "reload-start");

        boolean success = plugin.reloadPluginConfig();

        if (success) {
            messageManager.sendCommand(sender, "reload-success");
        } else {
            messageManager.sendCommand(sender, "reload-failed");
        }
    }

    // ===== Debug =====

    private void handleDebug(CommandSender sender) {
        boolean currentDebug = plugin.getConfigManager().isDebugMode();
        boolean newDebug = !currentDebug;

        plugin.getConfigManager().setDebugMode(newDebug);

        if (newDebug) {
            messageManager.sendCommand(sender, "debug-enabled");
        } else {
            messageManager.sendCommand(sender, "debug-disabled");
        }
    }

    // ===== Cleanup =====

    private void handleCleanup(CommandSender sender) {
        messageManager.sendCommand(sender, "cleanup-start");

        int cleaned = plugin.getCooldownManager().triggerManualCleanup();

        messageManager.sendCommand(sender, "cleanup-complete",
                Placeholder.unparsed("count", String.valueOf(cleaned)));
    }

    // ===== Info =====

    private void handleInfo(CommandSender sender, String shopArg) {
        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            messageManager.sendCommand(sender, "error-shop-not-found",
                    Placeholder.unparsed("shop", shopArg));
            return;
        }

        String shortId = shop.getShopId().length() > 8
                ? shop.getShopId().substring(0, 8) + "..."
                : shop.getShopId();

        messageManager.sendCommand(sender, "info-header", Placeholder.unparsed("name", shop.getName()));
        messageManager.sendCommand(sender, "info-id", Placeholder.unparsed("id", shortId));
        messageManager.sendCommand(sender, "info-enabled", Placeholder.unparsed("enabled", String.valueOf(shop.isEnabled())));
        messageManager.sendCommand(sender, "info-stock-mode", Placeholder.unparsed("mode", shop.getStockMode().name().toLowerCase()));

        String cooldownDisplay = shop.getCooldownMode() == CooldownMode.NONE
                ? "none (manual restock)"
                : shop.getCooldownMode().name().toLowerCase();
        messageManager.sendCommand(sender, "info-cooldown-mode", Placeholder.unparsed("mode", cooldownDisplay));

        if (shop.getCooldownMode() == CooldownMode.DAILY || shop.getCooldownMode() == CooldownMode.WEEKLY) {
            messageManager.sendCommand(sender, "info-reset-time", Placeholder.unparsed("time", shop.getResetTime()));
        }
        if (shop.getCooldownMode() == CooldownMode.WEEKLY) {
            String day = shop.getResetDay().charAt(0) + shop.getResetDay().substring(1).toLowerCase();
            messageManager.sendCommand(sender, "info-reset-day", Placeholder.unparsed("day", day));
        }

        if (shop.isShared() && shop.getMaxPerPlayer() > 0) {
            messageManager.sendCommand(sender, "info-per-player-cap",
                    Placeholder.unparsed("cap", String.valueOf(shop.getMaxPerPlayer())));
        }

        messageManager.sendCommand(sender, "info-trades-count",
                Placeholder.unparsed("count", String.valueOf(shop.getTrades().size())));

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

            messageManager.sendCommand(sender, "info-trade-entry",
                    Placeholder.unparsed("key", trade.getTradeKey()),
                    Placeholder.unparsed("details", details.toString()));
        }

        if (shop.hasPools()) {
            messageManager.sendCommand(sender, "info-pools-count",
                    Placeholder.unparsed("count", String.valueOf(shop.getPools().size())));
            for (PoolConfig pool : shop.getPools().values()) {
                String poolDetails = "ui-slots " + pool.getUiSlots()
                        + ", visible " + pool.getVisible()
                        + ", mode " + pool.getMode().name().toLowerCase()
                        + ", schedule " + pool.getSchedule().name().toLowerCase();
                messageManager.sendCommand(sender, "info-pool-entry",
                        Placeholder.unparsed("pool", pool.getName()),
                        Placeholder.unparsed("details", poolDetails));
                for (PoolItemConfig item : pool.getItems().values()) {
                    String itemDetails = "source " + item.getSourceSlot()
                            + ", max " + item.getMaxTrades();
                    messageManager.sendCommand(sender, "info-pool-item-entry",
                            Placeholder.unparsed("key", item.getItemKey()),
                            Placeholder.unparsed("details", itemDetails));
                }
            }
        }
    }

    // ===== Rotation =====

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPooledShops(
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String input = builder.getRemainingLowerCase();
        for (ShopConfig shop : plugin.getConfigManager().getShops().values()) {
            if (!shop.hasPools()) continue;
            String name = commandName(shop.getName());
            if (name.toLowerCase().startsWith(input)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    private void handleRotationPeek(CommandSender sender, String shopArg) {
        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            messageManager.sendCommand(sender, "error-shop-not-found",
                    Placeholder.unparsed("shop", shopArg));
            return;
        }
        if (!shop.hasPools()) {
            messageManager.sendCommand(sender, "rotation-no-pools",
                    Placeholder.unparsed("name", shop.getName()));
            return;
        }

        messageManager.sendCommand(sender, "rotation-peek-header",
                Placeholder.unparsed("name", shop.getName()));

        long now = java.time.ZonedDateTime.now().toEpochSecond();
        for (PoolConfig pool : shop.getPools().values()) {
            RotationState state = plugin.getPoolRotationManager().getState(shop.getShopId(), pool.getName());
            String active = state == null ? "(no state yet)" : String.join(", ", state.getActiveItems());
            String nextIn = state == null
                    ? "?"
                    : plugin.getTradeDataManager().formatDuration(Math.max(0, state.getAdvancesAt() - now));

            messageManager.sendCommand(sender, "rotation-peek-pool",
                    Placeholder.unparsed("pool", pool.getName()),
                    Placeholder.unparsed("active", active),
                    Placeholder.unparsed("next", nextIn));
        }
    }

    private void handleRotationForce(CommandSender sender, String shopArg, String poolArg) {
        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            messageManager.sendCommand(sender, "error-shop-not-found",
                    Placeholder.unparsed("shop", shopArg));
            return;
        }
        if (!shop.hasPools()) {
            messageManager.sendCommand(sender, "rotation-no-pools",
                    Placeholder.unparsed("name", shop.getName()));
            return;
        }
        if (poolArg != null && !shop.getPools().containsKey(poolArg)) {
            messageManager.sendCommand(sender, "rotation-pool-not-found",
                    Placeholder.unparsed("pool", poolArg),
                    Placeholder.unparsed("name", shop.getName()));
            return;
        }

        int advanced = plugin.getPoolRotationManager().forceAdvance(shop.getShopId(), poolArg);
        messageManager.sendCommand(sender, "rotation-force-success",
                Placeholder.unparsed("count", String.valueOf(advanced)),
                Placeholder.unparsed("name", shop.getName()));
    }

    // ===== Reset handlers =====

    private void handleResetPlayer(CommandSender sender, String playerName) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            messageManager.sendCommand(sender, "error-player-not-found",
                    Placeholder.unparsed("player", playerName));
            return;
        }

        plugin.getTradeDataManager().resetPlayerTrades(playerId);
        messageManager.sendCommand(sender, "reset-player",
                Placeholder.unparsed("player", playerName));
    }

    private void handleResetPlayerShop(CommandSender sender, String playerName, String shopArg) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            messageManager.sendCommand(sender, "error-player-not-found",
                    Placeholder.unparsed("player", playerName));
            return;
        }

        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            messageManager.sendCommand(sender, "error-shop-not-found",
                    Placeholder.unparsed("shop", shopArg));
            return;
        }

        plugin.getTradeDataManager().resetPlayerShopTrades(playerId, shop.getShopId());
        messageManager.sendCommand(sender, "reset-player-shop",
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("shop", shop.getName()));
    }

    private void handleResetPlayerShopTrade(CommandSender sender, String playerName, String shopArg, String tradeKey) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            messageManager.sendCommand(sender, "error-player-not-found",
                    Placeholder.unparsed("player", playerName));
            return;
        }

        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            messageManager.sendCommand(sender, "error-shop-not-found",
                    Placeholder.unparsed("shop", shopArg));
            return;
        }

        plugin.getTradeDataManager().resetPlayerTrade(playerId, shop.getShopId(), tradeKey);
        messageManager.sendCommand(sender, "reset-player-shop-trade",
                Placeholder.unparsed("trade", tradeKey),
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("shop", shop.getName()));
    }

    // ===== Restock handlers =====

    private void handleRestockShop(CommandSender sender, String shopArg) {
        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            messageManager.sendCommand(sender, "error-shop-not-found",
                    Placeholder.unparsed("shop", shopArg));
            return;
        }

        if (!shop.isShared()) {
            messageManager.sendCommand(sender, "error-not-shared",
                    Placeholder.unparsed("shop", shop.getName()));
            return;
        }

        plugin.getTradeDataManager().resetGlobalShop(shop.getShopId());
        messageManager.sendCommand(sender, "restock-shop",
                Placeholder.unparsed("shop", shop.getName()));
    }

    private void handleRestockTrade(CommandSender sender, String shopArg, String tradeKey) {
        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            messageManager.sendCommand(sender, "error-shop-not-found",
                    Placeholder.unparsed("shop", shopArg));
            return;
        }

        if (!shop.isShared()) {
            messageManager.sendCommand(sender, "error-not-shared",
                    Placeholder.unparsed("shop", shop.getName()));
            return;
        }

        TradeConfig trade = shop.getTrade(tradeKey);
        if (trade == null) {
            messageManager.sendCommand(sender, "error-trade-not-found",
                    Placeholder.unparsed("trade", tradeKey),
                    Placeholder.unparsed("shop", shop.getName()));
            return;
        }

        plugin.getTradeDataManager().resetGlobalTrade(shop.getShopId(), tradeKey);
        messageManager.sendCommand(sender, "restock-trade",
                Placeholder.unparsed("trade", tradeKey),
                Placeholder.unparsed("shop", shop.getName()));
    }

    // ===== Check handlers =====

    private void handleCheckPlayer(CommandSender sender, String playerName) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            messageManager.sendCommand(sender, "error-player-not-found",
                    Placeholder.unparsed("player", playerName));
            return;
        }

        plugin.getTradeDataManager().flushPlayerData(playerId);
        List<PlayerTradeData> playerTrades = plugin.getTradeDataManager().getPlayerTrades(playerId);

        if (playerTrades.isEmpty()) {
            messageManager.sendCommand(sender, "check-no-data",
                    Placeholder.unparsed("player", playerName));
            return;
        }

        displayTradeData(sender, playerName, playerId, playerTrades);
    }

    private void handleCheckPlayerShop(CommandSender sender, String playerName, String shopArg) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            messageManager.sendCommand(sender, "error-player-not-found",
                    Placeholder.unparsed("player", playerName));
            return;
        }

        ShopConfig filterShop = resolveShop(shopArg);
        if (filterShop == null) {
            messageManager.sendCommand(sender, "error-shop-not-found",
                    Placeholder.unparsed("shop", shopArg));
            return;
        }

        plugin.getTradeDataManager().flushPlayerData(playerId);
        String shopId = filterShop.getShopId();
        List<PlayerTradeData> playerTrades = plugin.getTradeDataManager().getPlayerTrades(playerId).stream()
                .filter(data -> data.getShopId().equals(shopId))
                .toList();

        if (playerTrades.isEmpty()) {
            messageManager.sendCommand(sender, "check-no-data-shop",
                    Placeholder.unparsed("player", playerName),
                    Placeholder.unparsed("shop", filterShop.getName()));
            return;
        }

        displayTradeData(sender, playerName, playerId, playerTrades);
    }

    private void handleCheckPlayerShopTrade(CommandSender sender, String playerName, String shopArg, String tradeKey) {
        UUID playerId = resolvePlayerUUID(playerName);
        if (playerId == null) {
            messageManager.sendCommand(sender, "error-player-not-found",
                    Placeholder.unparsed("player", playerName));
            return;
        }

        ShopConfig filterShop = resolveShop(shopArg);
        if (filterShop == null) {
            messageManager.sendCommand(sender, "error-shop-not-found",
                    Placeholder.unparsed("shop", shopArg));
            return;
        }

        plugin.getTradeDataManager().flushPlayerData(playerId);
        String shopId = filterShop.getShopId();
        List<PlayerTradeData> playerTrades = plugin.getTradeDataManager().getPlayerTrades(playerId).stream()
                .filter(data -> data.getShopId().equals(shopId) && data.getTradeKey().equals(tradeKey))
                .toList();

        if (playerTrades.isEmpty()) {
            messageManager.sendCommand(sender, "check-no-data-trade",
                    Placeholder.unparsed("player", playerName),
                    Placeholder.unparsed("trade", tradeKey));
            return;
        }

        displayTradeData(sender, playerName, playerId, playerTrades);
    }

    private void displayTradeData(CommandSender sender, String playerName, UUID playerId, List<PlayerTradeData> playerTrades) {
        messageManager.sendCommand(sender, "check-header",
                Placeholder.unparsed("player", playerName));

        for (PlayerTradeData data : playerTrades) {
            int remaining = plugin.getTradeDataManager().getRemainingTrades(playerId, data.getShopId(), data.getTradeKey());
            boolean cooldownExpired = plugin.getTradeDataManager().hasCooldownExpired(playerId, data.getShopId(), data.getTradeKey());
            long timeRemaining = cooldownExpired ? 0 : plugin.getTradeDataManager().getTimeUntilReset(playerId, data.getShopId(), data.getTradeKey());

            ShopConfig shop = plugin.getConfigManager().getShop(data.getShopId());
            TradeConfig resolved = shop != null ? shop.findTradeLimits(data.getTradeKey()) : null;
            int maxTrades = resolved != null ? resolved.getMaxTrades() : data.getTradesUsed();

            String shopName = shop != null ? shop.getName() : data.getShopId();
            int usedCount = cooldownExpired ? 0 : data.getTradesUsed();

            messageManager.sendCommand(sender, "check-shop",
                    Placeholder.unparsed("shop", shopName));
            messageManager.sendCommand(sender, "check-trade",
                    Placeholder.unparsed("trade", data.getTradeKey()));
            messageManager.sendCommand(sender, "check-used",
                    Placeholder.unparsed("used", String.valueOf(usedCount)),
                    Placeholder.unparsed("max", String.valueOf(maxTrades)));
            messageManager.sendCommand(sender, "check-remaining",
                    Placeholder.unparsed("remaining", String.valueOf(remaining)));

            if (timeRemaining < 0) {
                messageManager.sendCommand(sender, "check-cooldown-never");
            } else if (timeRemaining > 0) {
                String resetInfo = plugin.getTradeDataManager().formatDuration(timeRemaining);
                String resetTime = plugin.getTradeDataManager().getResetTimeString(data.getShopId(), data.getTradeKey());
                if (!resetTime.isEmpty() && !resetTime.equals("Never")) {
                    resetInfo += " (Resets at " + resetTime + ")";
                }
                messageManager.sendCommand(sender, "check-cooldown-active",
                        Placeholder.unparsed("cooldown", resetInfo));
            } else {
                messageManager.sendCommand(sender, "check-cooldown-ready");
            }
        }
    }
}
