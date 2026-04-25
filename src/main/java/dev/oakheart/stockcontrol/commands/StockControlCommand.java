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
    private final BulkCommandHandler bulkHandler;

    public StockControlCommand(ShopkeepersStockControl plugin) {
        this.plugin = plugin;
        this.messageManager = plugin.getMessageManager();
        this.bulkHandler = new BulkCommandHandler(plugin);
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
                // diag
                .then(Commands.literal("diag")
                        .requires(src -> src.getSender().hasPermission("shopkeepersstock.admin"))
                        .executes(ctx -> {
                            handleDiag(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // stress <shop> <trade> <players> <duration>
                .then(Commands.literal("stress")
                        .requires(src -> src.getSender().hasPermission("shopkeepersstock.admin"))
                        .then(Commands.argument("shop", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String input = builder.getRemainingLowerCase();
                                    for (ShopConfig shop : plugin.getConfigManager().getShops().values()) {
                                        String name = commandName(shop.getName());
                                        if (name.toLowerCase().startsWith(input)) builder.suggest(name);
                                    }
                                    return builder.buildFuture();
                                })
                                .then(Commands.argument("trade", StringArgumentType.word())
                                        .then(Commands.argument("players", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 500))
                                                .then(Commands.argument("duration", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 300))
                                                        .executes(ctx -> {
                                                            handleStress(ctx.getSource().getSender(),
                                                                    StringArgumentType.getString(ctx, "shop"),
                                                                    StringArgumentType.getString(ctx, "trade"),
                                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "players"),
                                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "duration"));
                                                            return Command.SINGLE_SUCCESS;
                                                        }))))))
                // bulk add <shop> <pool> [subpool] <items-file> | bulk clear <shop> <count>
                .then(Commands.literal("bulk")
                        .requires(src -> src.getSender().hasPermission("shopkeepersstock.bulk"))
                        .then(Commands.literal("add")
                                .then(Commands.argument("shop", StringArgumentType.word())
                                        .then(Commands.argument("pool", StringArgumentType.word())
                                                .then(Commands.argument("subpool", StringArgumentType.word())
                                                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                                                .executes(ctx -> {
                                                                    bulkHandler.handleBulkAdd(
                                                                            ctx.getSource().getSender(),
                                                                            StringArgumentType.getString(ctx, "shop"),
                                                                            StringArgumentType.getString(ctx, "pool"),
                                                                            StringArgumentType.getString(ctx, "subpool"),
                                                                            StringArgumentType.getString(ctx, "file"));
                                                                    return Command.SINGLE_SUCCESS;
                                                                }))))))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("shop", StringArgumentType.word())
                                        .then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 10000))
                                                .executes(ctx -> {
                                                    bulkHandler.handleBulkClear(
                                                            ctx.getSource().getSender(),
                                                            StringArgumentType.getString(ctx, "shop"),
                                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count"));
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
                    .append(", max ").append(trade.isUnlimited() ? "∞" : trade.getMaxTrades());

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
                        + ", schedule " + pool.getSchedule().name().toLowerCase()
                        + (pool.hasSubpools() ? ", subpools=" + pool.getSubpools().size() : "");
                messageManager.sendCommand(sender, "info-pool-entry",
                        Placeholder.unparsed("pool", pool.getName()),
                        Placeholder.unparsed("details", poolDetails));
                for (PoolItemConfig item : pool.getItems().values()) {
                    String itemDetails = "source " + item.getSourceSlot()
                            + ", max " + (item.isUnlimited() ? "∞" : item.getMaxTrades());
                    messageManager.sendCommand(sender, "info-pool-item-entry",
                            Placeholder.unparsed("key", item.getItemKey()),
                            Placeholder.unparsed("details", itemDetails));
                }
                for (dev.oakheart.stockcontrol.data.SubpoolConfig sub : pool.getSubpools().values()) {
                    for (PoolItemConfig item : sub.getItems().values()) {
                        String itemDetails = "[" + sub.getName() + "] source " + item.getSourceSlot()
                                + ", max " + (item.isUnlimited() ? "∞" : item.getMaxTrades());
                        messageManager.sendCommand(sender, "info-pool-item-entry",
                                Placeholder.unparsed("key", item.getItemKey()),
                                Placeholder.unparsed("details", itemDetails));
                    }
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

    // ===== Diagnostics =====

    private void handleDiag(CommandSender sender) {
        dev.oakheart.stockcontrol.managers.TradeDataManager tdm = plugin.getTradeDataManager();
        dev.oakheart.stockcontrol.managers.PacketManager pm = plugin.getPacketManager();
        dev.oakheart.stockcontrol.managers.PoolRotationManager prm = plugin.getPoolRotationManager();

        int shopsCount = plugin.getConfigManager().getShops().size();
        long pooledCount = plugin.getConfigManager().getShops().values().stream()
                .filter(ShopConfig::hasPools).count();

        dev.oakheart.stockcontrol.data.DataStore.TableCounts rows = plugin.getDataStore().countRows();
        long now = java.time.ZonedDateTime.now().toEpochSecond();

        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("=== Diagnostics: " + plugin.getPluginMeta().getName()
                + " v" + plugin.getPluginMeta().getVersion() + " ===");
        lines.add("Shops: " + shopsCount + " (" + pooledCount + " with pools)");
        lines.add("Trade cache: " + tdm.cacheSize() + " entries, dirty " + tdm.dirtyCount()
                + ", tracked players " + tdm.trackedPlayerCount());
        lines.add("Global cache: " + tdm.globalCacheSize() + " entries, dirty " + tdm.globalDirtyCount());
        lines.add("Open shops: " + pm.openShopCount()
                + ", cached merchants " + pm.cachedMerchantDataCount()
                + ", ui-maps " + pm.uiToSourceMapCount());
        lines.add("Pending: cache " + pm.pendingCacheCount()
                + ", stock-push " + pm.pendingStockPushCount()
                + ", rotation-push " + pm.pendingRotationPushCount());
        lines.add("DB rows: player_trades " + rows.playerTrades()
                + ", global_trades " + rows.globalTrades()
                + ", pool_rotation_state " + rows.rotationStates());

        java.util.List<dev.oakheart.stockcontrol.data.RotationState> states = prm.allStates();
        lines.add("Rotation states: " + states.size());
        for (dev.oakheart.stockcontrol.data.RotationState s : states) {
            long until = Math.max(0, s.getAdvancesAt() - now);
            String shortShop = s.getShopId().length() > 8
                    ? s.getShopId().substring(0, 8) + "…" : s.getShopId();
            lines.add("  - " + shortShop + "/" + s.getPoolName()
                    + " period " + s.getPeriodIndex()
                    + ", next in " + plugin.getTradeDataManager().formatDuration(until)
                    + ", active " + s.getActiveItems());
        }

        // Echo to both the issuer and the server console so the output is easy to copy out.
        for (String line : lines) {
            sender.sendMessage(MINI_MESSAGE.deserialize("<#f2ebd7>" + line));
            plugin.getLogger().info(line);
        }
    }

    // ===== Stress test =====

    /**
     * Concurrency stress test. Simulates N virtual players performing canTrade/recordTrade
     * at a realistic rate (not "as fast as possible" — that just starves the main thread and
     * tanks TPS without telling us anything useful about production behavior). Each virtual
     * player averages ~1 op per 20ms, distributed across a small worker pool.
     *
     * For pooled shops the rotation is force-advanced every 5 seconds in parallel so the
     * reset/flush lock is exercised under contention with ongoing trades.
     *
     * Writes to the live DB under stress-only UUIDs which are cleaned up at the end. Still
     * only safe to run during a maintenance window.
     */
    private void handleStress(CommandSender sender, String shopArg, String tradeKey,
                              int players, int durationSec) {
        ShopConfig shop = resolveShop(shopArg);
        if (shop == null) {
            messageManager.sendCommand(sender, "error-shop-not-found",
                    Placeholder.unparsed("shop", shopArg));
            return;
        }
        if (shop.findTradeLimits(tradeKey) == null) {
            messageManager.sendCommand(sender, "error-trade-not-found",
                    Placeholder.unparsed("trade", tradeKey),
                    Placeholder.unparsed("shop", shop.getName()));
            return;
        }

        final String shopId = shop.getShopId();
        final boolean hasPools = shop.hasPools();

        // Cap the thread pool well below core count so real server work still gets CPU.
        final int workerCount = Math.min(players, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        // Each virtual player targets ~50 ops/sec (every 20ms). Per-worker delay accounts
        // for how many virtual players that worker is round-robining through.
        final int virtualsPerWorker = (players + workerCount - 1) / workerCount;
        final long perOpSleepNanos = 20_000_000L / Math.max(1, virtualsPerWorker);

        String startLine = "[stress] " + players + " virtual players × " + durationSec + "s on "
                + shop.getName() + ":" + tradeKey
                + (hasPools ? " (rotation force every 5s)" : "")
                + " | " + workerCount + " worker threads, ~50 ops/s per player";
        sender.sendMessage(MINI_MESSAGE.deserialize("<#D89B6A>" + startLine));
        sender.sendMessage(MINI_MESSAGE.deserialize(
                "<#C27B6B>[stress] Writes to live DB. Cleanup runs at end."));
        plugin.getLogger().info(startLine);
        plugin.getLogger().info("[stress] Writes to live DB. Cleanup runs at end.");

        // Stable UUID prefix so we can identify and delete stress data after.
        final java.util.List<java.util.UUID> fakeIds = new java.util.ArrayList<>(players);
        for (int i = 0; i < players; i++) {
            fakeIds.add(new java.util.UUID(0x5374__72657373L, (long) i));  // 0x_Stress_<i>
        }

        final java.util.concurrent.atomic.AtomicLong ops = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong successes = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong blocks = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong errors = new java.util.concurrent.atomic.AtomicLong();
        final java.util.List<Throwable> firstErrors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        // Reservoir sample: cap memory for latency recording regardless of op count.
        final int latencyCapacity = 10_000;
        final java.util.concurrent.atomic.AtomicLongArray latencyReservoir = new java.util.concurrent.atomic.AtomicLongArray(latencyCapacity);
        final java.util.concurrent.atomic.AtomicLong latencyIndex = new java.util.concurrent.atomic.AtomicLong();

        final long deadlineNs = System.nanoTime() + durationSec * 1_000_000_000L;
        final java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(workerCount);

        for (int w = 0; w < workerCount; w++) {
            final int workerIndex = w;
            pool.submit(() -> {
                java.util.Random rng = new java.util.Random(Thread.currentThread().getId());
                int virtualStart = workerIndex * virtualsPerWorker;
                int virtualEnd = Math.min(players, virtualStart + virtualsPerWorker);
                while (System.nanoTime() < deadlineNs && !Thread.currentThread().isInterrupted()) {
                    for (int i = virtualStart; i < virtualEnd && System.nanoTime() < deadlineNs; i++) {
                        java.util.UUID id = fakeIds.get(i);
                        long t0 = System.nanoTime();
                        try {
                            // Use the atomic compound entry point so "Success" only counts
                            // trades that actually passed check+increment under the lock.
                            if (plugin.getTradeDataManager().attemptTrade(id, shopId, tradeKey)) {
                                successes.incrementAndGet();
                            } else {
                                blocks.incrementAndGet();
                            }
                        } catch (Throwable t) {
                            long errs = errors.incrementAndGet();
                            if (errs <= 5) firstErrors.add(t);
                        }
                        long latency = System.nanoTime() - t0;
                        long idx = latencyIndex.getAndIncrement();
                        if (idx < latencyCapacity) {
                            latencyReservoir.set((int) idx, latency);
                        } else if (rng.nextInt() % (int) Math.min(idx, Integer.MAX_VALUE) < latencyCapacity) {
                            // Reservoir-style replacement so we keep a representative sample.
                            latencyReservoir.set(rng.nextInt(latencyCapacity), latency);
                        }
                        ops.incrementAndGet();
                        try {
                            if (perOpSleepNanos > 0) {
                                java.util.concurrent.locks.LockSupport.parkNanos(perOpSleepNanos);
                            }
                        } catch (Throwable ignored) { /* park can't throw, kept for symmetry */ }
                    }
                }
            });
        }

        // Interleave rotation force-advances on pooled shops. Runs on Bukkit scheduler
        // so it hits the same lock paths that natural rotation checks would.
        final org.bukkit.scheduler.BukkitTask rotationTask = hasPools
                ? org.bukkit.Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
                    try {
                        plugin.getPoolRotationManager().forceAdvance(shopId, null);
                    } catch (Throwable t) {
                        long errs = errors.incrementAndGet();
                        if (errs <= 5) firstErrors.add(t);
                    }
                }, 100L, 100L)  // every 5 seconds
                : null;

        org.bukkit.Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (rotationTask != null) rotationTask.cancel();
            pool.shutdown();
            try { pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

            // Percentiles from the bounded latency reservoir.
            int populated = (int) Math.min(latencyIndex.get(), latencyCapacity);
            long[] arr = new long[populated];
            for (int i = 0; i < populated; i++) arr[i] = latencyReservoir.get(i);
            java.util.Arrays.sort(arr);
            long p50 = arr.length == 0 ? 0 : arr[arr.length / 2];
            long p99 = arr.length == 0 ? 0 : arr[Math.min(arr.length - 1, arr.length * 99 / 100)];
            long max = arr.length == 0 ? 0 : arr[arr.length - 1];

            // Clean up: bulk reset under a single lock acquisition, one summary log line
            // instead of one line per fake player.
            plugin.getTradeDataManager().resetPlayersBulk(fakeIds);

            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                java.util.List<String> result = new java.util.ArrayList<>();
                result.add("=== Stress result ===");
                result.add("Total ops: " + ops.get()
                        + " (" + (ops.get() / Math.max(1, durationSec)) + "/s)");
                result.add("Success: " + successes.get()
                        + "  Blocked: " + blocks.get()
                        + "  Errors: " + errors.get());
                result.add("Latency µs: p50 " + (p50 / 1000)
                        + "  p99 " + (p99 / 1000)
                        + "  max " + (max / 1000));
                if (!firstErrors.isEmpty()) {
                    Throwable first = firstErrors.get(0);
                    result.add("First error: " + first.getClass().getSimpleName()
                            + ": " + String.valueOf(first.getMessage()));
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "Stress test recorded errors", first);
                }
                result.add("Stress data cleaned. Real data untouched.");

                for (String line : result) {
                    sender.sendMessage(MINI_MESSAGE.deserialize("<#f2ebd7>" + line));
                    plugin.getLogger().info(line);
                }
            });
        }, durationSec * 20L);
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
            boolean unlimited = resolved != null && resolved.isUnlimited();
            int maxTrades = resolved != null ? resolved.getMaxTrades() : data.getTradesUsed();

            String shopName = shop != null ? shop.getName() : data.getShopId();
            int usedCount = cooldownExpired ? 0 : data.getTradesUsed();

            messageManager.sendCommand(sender, "check-shop",
                    Placeholder.unparsed("shop", shopName));
            messageManager.sendCommand(sender, "check-trade",
                    Placeholder.unparsed("trade", data.getTradeKey()));
            messageManager.sendCommand(sender, "check-used",
                    Placeholder.unparsed("used", String.valueOf(usedCount)),
                    Placeholder.unparsed("max", unlimited ? "∞" : String.valueOf(maxTrades)));
            messageManager.sendCommand(sender, "check-remaining",
                    Placeholder.unparsed("remaining", unlimited ? "∞" : String.valueOf(remaining)));

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
