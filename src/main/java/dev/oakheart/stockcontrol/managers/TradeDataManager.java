package dev.oakheart.stockcontrol.managers;

import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.*;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player trade data including cooldowns, limits, and persistence.
 * Supports both per-player and shared (global) stock modes.
 * Uses in-memory caching with dirty tracking for performance.
 */
public class TradeDataManager {

    private final ShopkeepersStockControl plugin;
    private final DataStore dataStore;

    // In-memory cache: cacheKey -> PlayerTradeData
    private final Map<String, PlayerTradeData> tradeCache;

    // Dirty tracking for batch writes
    private final Set<String> dirtyKeys;

    // Player-keyed index for O(1) eviction: playerId -> set of cache keys
    private final Map<UUID, Set<String>> playerCacheKeys;

    // Global trade cache for shared stock mode: "shopId:tradeKey" -> GlobalTradeData
    private final Map<String, GlobalTradeData> globalTradeCache;
    private final Set<String> globalDirtyKeys;

    // Scheduled tasks
    private BukkitTask batchWriteTask;

    public TradeDataManager(ShopkeepersStockControl plugin, DataStore dataStore) {
        this.plugin = plugin;
        this.dataStore = dataStore;
        this.tradeCache = new ConcurrentHashMap<>();
        this.dirtyKeys = ConcurrentHashMap.newKeySet();
        this.playerCacheKeys = new ConcurrentHashMap<>();
        this.globalTradeCache = new ConcurrentHashMap<>();
        this.globalDirtyKeys = ConcurrentHashMap.newKeySet();
    }

    /**
     * Initializes the manager and starts scheduled tasks.
     */
    public void initialize() {
        // Start batch write task
        int batchInterval = plugin.getConfigManager().getBatchWriteInterval();
        batchWriteTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::flushDirtyData,
                batchInterval * 20L, // Convert seconds to ticks
                batchInterval * 20L
        );

        plugin.getLogger().info("TradeDataManager initialized with batch write interval: " + batchInterval + "s");
    }

    /**
     * Restarts the batch write task with the current config interval.
     * Called after config reload to pick up interval changes.
     */
    public void restartBatchWriteTask() {
        if (batchWriteTask != null) {
            batchWriteTask.cancel();
        }
        int batchInterval = plugin.getConfigManager().getBatchWriteInterval();
        batchWriteTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::flushDirtyData,
                batchInterval * 20L,
                batchInterval * 20L
        );
        plugin.getLogger().info("Batch write task restarted with interval: " + batchInterval + "s");
    }

    /**
     * Shuts down the manager and flushes all data.
     */
    public void shutdown() {
        // Cancel scheduled tasks
        if (batchWriteTask != null) {
            batchWriteTask.cancel();
        }

        // Flush all dirty data synchronously
        flushAllDirtyData();

        plugin.getLogger().info("TradeDataManager shutdown complete");
    }

    // ===== Core Trade Logic =====

    /**
     * Checks if a player can perform a trade (considering cooldowns and limits).
     *
     * @param playerId The player's UUID
     * @param shopId   The shop identifier
     * @param tradeKey The trade key
     * @return true if the player can trade, false otherwise
     */
    public boolean canTrade(UUID playerId, String shopId, String tradeKey) {
        // Dispatch to shared mode if applicable
        ShopConfig shopConfig = plugin.getConfigManager().getShop(shopId);
        if (shopConfig != null && shopConfig.isShared()) {
            return canTradeShared(playerId, shopId, tradeKey, shopConfig);
        }

        PlayerTradeData data = getTradeData(playerId, shopId, tradeKey);

        // First time trading - always allowed
        if (data == null) {
            return true;
        }

        long now = System.currentTimeMillis() / 1000;
        TradeConfig tradeConfig = getTradeConfig(shopId, tradeKey);
        CooldownMode mode = tradeConfig != null ? tradeConfig.getCooldownMode() : CooldownMode.ROLLING;

        switch (mode) {
            case DAILY:
            case WEEKLY:
                if (hasCooldownExpired(playerId, shopId, tradeKey)) {
                    data.setTradesUsed(0);
                    data.setLastResetEpoch(now);
                    markDirty(data.getCacheKey());
                    return true;
                }
                break;

            case NONE:
                // Never resets — just check if under limit
                break;

            case ROLLING:
            default:
                long elapsed = now - data.getLastResetEpoch();
                if (elapsed < 0) elapsed = 0;
                if (elapsed >= data.getCooldownSeconds()) {
                    data.setTradesUsed(0);
                    data.setLastResetEpoch(now);
                    markDirty(data.getCacheKey());
                    return true;
                }
                break;
        }

        int limit = getTradeLimit(shopId, tradeKey);
        return data.getTradesUsed() < limit;
    }

    /**
     * Checks if a player can trade in a shared-mode shop.
     */
    private boolean canTradeShared(UUID playerId, String shopId, String tradeKey, ShopConfig shopConfig) {
        TradeConfig tradeConfig = shopConfig.getTrade(tradeKey);
        if (tradeConfig == null) return true; // Untracked trade

        long now = System.currentTimeMillis() / 1000;

        // Check and potentially reset global stock
        GlobalTradeData globalData = getGlobalTradeData(shopId, tradeKey);
        if (globalData != null && tradeConfig.getCooldownMode() != CooldownMode.NONE) {
            if (isGlobalExpired(globalData, tradeConfig)) {
                globalData.setTradesUsed(0);
                globalData.setLastResetEpoch(now);
                globalDirtyKeys.add(globalData.getCacheKey());
            }
        }

        // Check global stock
        int globalUsed = globalData != null ? globalData.getTradesUsed() : 0;
        if (globalUsed >= tradeConfig.getMaxTrades()) {
            return false;
        }

        // Check per-player cap if configured
        int maxPerPlayer = tradeConfig.getMaxPerPlayer();
        if (maxPerPlayer > 0) {
            PlayerTradeData playerData = getTradeData(playerId, shopId, tradeKey);
            if (playerData != null) {
                // Reset per-player data if cooldown expired (and not NONE)
                if (tradeConfig.getCooldownMode() != CooldownMode.NONE && isExpired(playerData, tradeConfig)) {
                    playerData.setTradesUsed(0);
                    playerData.setLastResetEpoch(now);
                    markDirty(playerData.getCacheKey());
                } else if (playerData.getTradesUsed() >= maxPerPlayer) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Gets the number of remaining trades for a player.
     *
     * @param playerId The player's UUID
     * @param shopId   The shop identifier
     * @param tradeKey The trade key
     * @return Number of remaining trades
     */
    public int getRemainingTrades(UUID playerId, String shopId, String tradeKey) {
        // Dispatch to shared mode if applicable
        ShopConfig shopConfig = plugin.getConfigManager().getShop(shopId);
        if (shopConfig != null && shopConfig.isShared()) {
            return getRemainingTradesShared(playerId, shopId, tradeKey, shopConfig);
        }

        PlayerTradeData data = getTradeData(playerId, shopId, tradeKey);

        if (data == null) {
            return getTradeLimit(shopId, tradeKey);
        }

        if (hasCooldownExpired(playerId, shopId, tradeKey)) {
            return getTradeLimit(shopId, tradeKey);
        }

        int limit = getTradeLimit(shopId, tradeKey);
        return Math.max(0, limit - data.getTradesUsed());
    }

    /**
     * Gets remaining trades for a shared-mode shop.
     * Returns min(globalRemaining, playerCapRemaining) when per-player cap is set.
     */
    private int getRemainingTradesShared(UUID playerId, String shopId, String tradeKey, ShopConfig shopConfig) {
        TradeConfig tradeConfig = shopConfig.getTrade(tradeKey);
        if (tradeConfig == null) return 0;

        // Global remaining
        GlobalTradeData globalData = getGlobalTradeData(shopId, tradeKey);
        if (globalData != null && tradeConfig.getCooldownMode() != CooldownMode.NONE
                && isGlobalExpired(globalData, tradeConfig)) {
            // Expired — full stock
            return computeEffectiveRemaining(tradeConfig.getMaxTrades(), playerId, shopId, tradeKey, tradeConfig);
        }

        int globalUsed = globalData != null ? globalData.getTradesUsed() : 0;
        int globalRemaining = Math.max(0, tradeConfig.getMaxTrades() - globalUsed);

        // If per-player cap exists, cap the remaining
        int maxPerPlayer = tradeConfig.getMaxPerPlayer();
        if (maxPerPlayer > 0) {
            PlayerTradeData playerData = getTradeData(playerId, shopId, tradeKey);
            int playerUsed = 0;
            if (playerData != null) {
                if (tradeConfig.getCooldownMode() != CooldownMode.NONE && isExpired(playerData, tradeConfig)) {
                    playerUsed = 0;
                } else {
                    playerUsed = playerData.getTradesUsed();
                }
            }
            int playerRemaining = Math.max(0, maxPerPlayer - playerUsed);
            return Math.min(globalRemaining, playerRemaining);
        }

        return globalRemaining;
    }

    /**
     * Helper to compute effective remaining when global stock is full (after reset).
     */
    private int computeEffectiveRemaining(int globalMax, UUID playerId, String shopId,
                                           String tradeKey, TradeConfig tradeConfig) {
        int maxPerPlayer = tradeConfig.getMaxPerPlayer();
        if (maxPerPlayer > 0) {
            PlayerTradeData playerData = getTradeData(playerId, shopId, tradeKey);
            int playerUsed = 0;
            if (playerData != null && tradeConfig.getCooldownMode() != CooldownMode.NONE
                    && !isExpired(playerData, tradeConfig)) {
                playerUsed = playerData.getTradesUsed();
            }
            int playerRemaining = Math.max(0, maxPerPlayer - playerUsed);
            return Math.min(globalMax, playerRemaining);
        }
        return globalMax;
    }

    /**
     * Gets global remaining trades (without per-player cap consideration).
     * Used by PlaceholderAPI for explicit global stock display.
     *
     * @param shopId   The shop identifier
     * @param tradeKey The trade key
     * @return Global remaining trades
     */
    public int getGlobalRemainingTrades(String shopId, String tradeKey) {
        TradeConfig tradeConfig = getTradeConfig(shopId, tradeKey);
        if (tradeConfig == null) return 0;

        GlobalTradeData data = getGlobalTradeData(shopId, tradeKey);
        if (data == null) return tradeConfig.getMaxTrades();

        if (tradeConfig.getCooldownMode() != CooldownMode.NONE && isGlobalExpired(data, tradeConfig)) {
            return tradeConfig.getMaxTrades();
        }

        return Math.max(0, tradeConfig.getMaxTrades() - data.getTradesUsed());
    }

    /**
     * Records a trade (increments count and starts cooldown if limit reached).
     *
     * @param playerId The player's UUID
     * @param shopId   The shop identifier
     * @param tradeKey The trade key
     */
    public void recordTrade(UUID playerId, String shopId, String tradeKey) {
        // Dispatch to shared mode if applicable
        ShopConfig shopConfig = plugin.getConfigManager().getShop(shopId);
        if (shopConfig != null && shopConfig.isShared()) {
            recordSharedTrade(playerId, shopId, tradeKey, shopConfig);
            return;
        }

        PlayerTradeData data = getOrCreateTradeData(playerId, shopId, tradeKey);

        // Increment usage
        data.setTradesUsed(data.getTradesUsed() + 1);
        markDirty(data.getCacheKey());

        if (plugin.getConfigManager().isDebugMode()) {
            int limit = getTradeLimit(shopId, tradeKey);
            plugin.getLogger().info("Recorded trade for " + playerId + " at " + shopId + ":" + tradeKey +
                    " (used: " + data.getTradesUsed() + "/" + limit + ")");
        }
    }

    /**
     * Records a trade in a shared-mode shop.
     * Increments both the global counter and per-player counter (if per-player cap exists).
     */
    private void recordSharedTrade(UUID playerId, String shopId, String tradeKey, ShopConfig shopConfig) {
        // Increment global counter
        GlobalTradeData globalData = getOrCreateGlobalTradeData(shopId, tradeKey);
        globalData.setTradesUsed(globalData.getTradesUsed() + 1);
        globalDirtyKeys.add(globalData.getCacheKey());

        // Increment per-player counter if per-player cap is configured
        TradeConfig tradeConfig = shopConfig.getTrade(tradeKey);
        if (tradeConfig != null && tradeConfig.getMaxPerPlayer() > 0) {
            PlayerTradeData playerData = getOrCreateTradeData(playerId, shopId, tradeKey);
            playerData.setTradesUsed(playerData.getTradesUsed() + 1);
            markDirty(playerData.getCacheKey());
        }

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("Recorded shared trade for " + playerId + " at " + shopId + ":" + tradeKey +
                    " (global used: " + globalData.getTradesUsed() + "/" + getTradeLimit(shopId, tradeKey) + ")");
        }
    }

    // ===== Reset Time & Duration =====

    /**
     * Gets the reset time display string for a specific trade.
     */
    public String getResetTimeString(String shopId, String tradeKey) {
        TradeConfig tradeConfig = getTradeConfig(shopId, tradeKey);
        if (tradeConfig == null) return "";

        switch (tradeConfig.getCooldownMode()) {
            case DAILY:
                return tradeConfig.getResetTime();
            case WEEKLY:
                String day = tradeConfig.getResetDay().charAt(0)
                        + tradeConfig.getResetDay().substring(1).toLowerCase();
                return day + " " + tradeConfig.getResetTime();
            case NONE:
                return "Never";
            case ROLLING:
            default:
                return "";
        }
    }

    /**
     * Formats a duration in seconds to a human-readable string.
     */
    public String formatDuration(long seconds) {
        if (seconds < 0) return "Never";
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

    private long getNextResetTime(TradeConfig tradeConfig) {
        String[] parts = tradeConfig.getResetTime().split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);

        ZonedDateTime now = ZonedDateTime.now();

        if (tradeConfig.getCooldownMode() == CooldownMode.WEEKLY) {
            DayOfWeek targetDay = DayOfWeek.valueOf(tradeConfig.getResetDay());
            ZonedDateTime candidate = now
                    .with(TemporalAdjusters.nextOrSame(targetDay))
                    .withHour(hour).withMinute(minute).withSecond(0).withNano(0);

            if (!candidate.isAfter(now)) {
                candidate = now.with(TemporalAdjusters.next(targetDay))
                        .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
            }
            return candidate.toEpochSecond();
        }

        // DAILY mode
        ZonedDateTime todayReset = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        if (!now.isBefore(todayReset)) {
            todayReset = todayReset.plusDays(1);
        }
        return todayReset.toEpochSecond();
    }

    private long getPreviousResetTime(TradeConfig tradeConfig) {
        String[] parts = tradeConfig.getResetTime().split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);

        ZonedDateTime now = ZonedDateTime.now();

        if (tradeConfig.getCooldownMode() == CooldownMode.WEEKLY) {
            DayOfWeek targetDay = DayOfWeek.valueOf(tradeConfig.getResetDay());
            ZonedDateTime candidate = now
                    .with(TemporalAdjusters.previousOrSame(targetDay))
                    .withHour(hour).withMinute(minute).withSecond(0).withNano(0);

            if (candidate.isAfter(now)) {
                candidate = now.with(TemporalAdjusters.previous(targetDay))
                        .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
            }
            return candidate.toEpochSecond();
        }

        // DAILY mode
        ZonedDateTime todayReset = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        if (todayReset.isAfter(now)) {
            todayReset = todayReset.minusDays(1);
        }
        return todayReset.toEpochSecond();
    }

    // ===== Cooldown Checks =====

    /**
     * Checks if cooldown has expired for a specific trade.
     */
    public boolean hasCooldownExpired(UUID playerId, String shopId, String tradeKey) {
        PlayerTradeData data = getTradeData(playerId, shopId, tradeKey);

        if (data == null) {
            return true; // No data means no cooldown
        }

        TradeConfig tradeConfig = getTradeConfig(shopId, tradeKey);
        return isExpired(data, tradeConfig);
    }

    /**
     * Gets the time remaining until cooldown expires (in seconds).
     * Returns -1 for NONE mode (never resets).
     */
    public long getTimeUntilReset(UUID playerId, String shopId, String tradeKey) {
        PlayerTradeData data = getTradeData(playerId, shopId, tradeKey);

        if (data == null) {
            return 0;
        }

        long now = System.currentTimeMillis() / 1000;
        TradeConfig tradeConfig = getTradeConfig(shopId, tradeKey);
        CooldownMode mode = tradeConfig != null ? tradeConfig.getCooldownMode() : CooldownMode.ROLLING;

        switch (mode) {
            case DAILY:
            case WEEKLY: {
                long nextResetTime = getNextResetTime(tradeConfig);
                return Math.max(0, nextResetTime - now);
            }
            case NONE:
                return -1; // Never resets
            case ROLLING:
            default: {
                long elapsed = now - data.getLastResetEpoch();
                long remaining = data.getCooldownSeconds() - elapsed;
                return Math.max(0, remaining);
            }
        }
    }

    /**
     * Gets the time remaining for a shared shop's global cooldown.
     * Returns -1 for NONE mode.
     */
    public long getGlobalTimeUntilReset(String shopId, String tradeKey) {
        TradeConfig tradeConfig = getTradeConfig(shopId, tradeKey);
        if (tradeConfig == null) return 0;

        if (tradeConfig.getCooldownMode() == CooldownMode.NONE) return -1;

        GlobalTradeData data = getGlobalTradeData(shopId, tradeKey);
        if (data == null) return 0;

        long now = System.currentTimeMillis() / 1000;

        switch (tradeConfig.getCooldownMode()) {
            case DAILY:
            case WEEKLY: {
                long nextResetTime = getNextResetTime(tradeConfig);
                return Math.max(0, nextResetTime - now);
            }
            case ROLLING: {
                long elapsed = now - data.getLastResetEpoch();
                long remaining = data.getCooldownSeconds() - elapsed;
                return Math.max(0, remaining);
            }
            default:
                return 0;
        }
    }

    /**
     * Checks if a trade data entry's cooldown has expired.
     */
    private boolean isExpired(PlayerTradeData data, TradeConfig tradeConfig) {
        if (tradeConfig == null) {
            long elapsed = (System.currentTimeMillis() / 1000) - data.getLastResetEpoch();
            return elapsed >= data.getCooldownSeconds();
        }

        switch (tradeConfig.getCooldownMode()) {
            case DAILY:
            case WEEKLY: {
                long previousReset = getPreviousResetTime(tradeConfig);
                return data.getLastResetEpoch() < previousReset;
            }
            case NONE:
                return false; // Never expires
            case ROLLING:
            default: {
                long elapsed = (System.currentTimeMillis() / 1000) - data.getLastResetEpoch();
                return elapsed >= data.getCooldownSeconds();
            }
        }
    }

    /**
     * Checks if a trade data entry's cooldown has expired, using a cache for reset time computations.
     */
    private boolean isExpiredCached(PlayerTradeData data, TradeConfig tradeConfig, Map<String, Long> resetTimeCache) {
        if (tradeConfig == null) {
            long elapsed = (System.currentTimeMillis() / 1000) - data.getLastResetEpoch();
            return elapsed >= data.getCooldownSeconds();
        }

        switch (tradeConfig.getCooldownMode()) {
            case DAILY:
            case WEEKLY: {
                String configKey = tradeConfig.getCooldownMode() + ":" +
                        tradeConfig.getResetTime() + ":" + tradeConfig.getResetDay();
                long previousReset = resetTimeCache.computeIfAbsent(configKey,
                        k -> getPreviousResetTime(tradeConfig));
                return data.getLastResetEpoch() < previousReset;
            }
            case NONE:
                return false; // Never expires
            case ROLLING:
            default: {
                long elapsed = (System.currentTimeMillis() / 1000) - data.getLastResetEpoch();
                return elapsed >= data.getCooldownSeconds();
            }
        }
    }

    /**
     * Checks if global trade data's cooldown has expired.
     */
    private boolean isGlobalExpired(GlobalTradeData data, TradeConfig tradeConfig) {
        if (tradeConfig == null) return false;

        switch (tradeConfig.getCooldownMode()) {
            case DAILY:
            case WEEKLY: {
                long previousReset = getPreviousResetTime(tradeConfig);
                return data.getLastResetEpoch() < previousReset;
            }
            case NONE:
                return false;
            case ROLLING: {
                long elapsed = (System.currentTimeMillis() / 1000) - data.getLastResetEpoch();
                return elapsed >= data.getCooldownSeconds();
            }
            default:
                return false;
        }
    }

    // ===== Reset Commands =====

    /**
     * Resets a specific player's specific trade.
     */
    public void resetPlayerTrade(UUID playerId, String shopId, String tradeKey) {
        String cacheKey = buildCacheKey(playerId, shopId, tradeKey);
        tradeCache.remove(cacheKey);
        dirtyKeys.remove(cacheKey);
        untrackCacheKey(playerId, cacheKey);
        dataStore.deleteTradeData(playerId, shopId, tradeKey);

        plugin.getLogger().info("Reset trade " + tradeKey + " for player " + playerId + " in shop " + shopId);
    }

    /**
     * Resets all trades for a specific player in a specific shop.
     */
    public void resetPlayerShopTrades(UUID playerId, String shopId) {
        String prefix = playerId + ":" + shopId + ":";
        Set<String> keys = playerCacheKeys.get(playerId);
        if (keys != null) {
            keys.removeIf(key -> {
                if (key.startsWith(prefix)) {
                    tradeCache.remove(key);
                    dirtyKeys.remove(key);
                    return true;
                }
                return false;
            });
        }

        dataStore.deletePlayerShopData(playerId, shopId);
        plugin.getLogger().info("Reset all trades for player " + playerId + " in shop " + shopId);
    }

    /**
     * Resets all trades for a specific player.
     */
    public void resetPlayerTrades(UUID playerId) {
        Set<String> keys = playerCacheKeys.remove(playerId);
        if (keys != null) {
            for (String key : keys) {
                tradeCache.remove(key);
                dirtyKeys.remove(key);
            }
        }

        dataStore.deletePlayerData(playerId);
        plugin.getLogger().info("Reset all trades for player " + playerId);
    }

    /**
     * Restocks a specific trade in a shared-mode shop.
     * Resets global stock and all per-player purchase caps for this trade.
     */
    public void resetGlobalTrade(String shopId, String tradeKey) {
        String cacheKey = shopId + ":" + tradeKey;
        globalTradeCache.remove(cacheKey);
        globalDirtyKeys.remove(cacheKey);
        dataStore.deleteGlobalTradeData(shopId, tradeKey);

        // Evict per-player caps from cache for this trade
        tradeCache.entrySet().removeIf(e -> {
            PlayerTradeData data = e.getValue();
            if (data.getShopId().equals(shopId) && data.getTradeKey().equals(tradeKey)) {
                dirtyKeys.remove(e.getKey());
                untrackCacheKey(data.getPlayerId(), e.getKey());
                return true;
            }
            return false;
        });

        // Single-query delete of all player entries for this shop+trade
        dataStore.deleteShopTradeData(shopId, tradeKey);

        plugin.getLogger().info("Restocked trade " + tradeKey + " in shop " + shopId);
    }

    /**
     * Restocks all trades in a shared-mode shop.
     * Resets global stock and all per-player purchase caps.
     */
    public void resetGlobalShop(String shopId) {
        globalTradeCache.entrySet().removeIf(e -> e.getValue().getShopId().equals(shopId));
        globalDirtyKeys.removeIf(key -> key.startsWith(shopId + ":"));
        dataStore.deleteGlobalShopData(shopId);

        // Also reset per-player caps for this shop
        evictShopPlayerData(shopId);
        dataStore.deleteShopData(shopId);

        plugin.getLogger().info("Restocked all trades in shop " + shopId);
    }

    // ===== Data Retrieval =====

    /**
     * Gets all trade data for a specific player.
     */
    public List<PlayerTradeData> getPlayerTrades(UUID playerId) {
        List<PlayerTradeData> trades = dataStore.loadPlayerData(playerId);

        for (PlayerTradeData data : trades) {
            String cacheKey = data.getCacheKey();
            tradeCache.putIfAbsent(cacheKey, data);
            trackCacheKey(playerId, cacheKey);
        }

        return trades;
    }

    /**
     * Pre-loads all trade data for a player+shop into cache asynchronously.
     * For shared shops, also pre-loads global trade data.
     */
    public void preloadShopData(UUID playerId, String shopId) {
        ShopConfig shopConfig = plugin.getConfigManager().getShop(shopId);
        if (shopConfig == null) return;

        CompletableFuture.runAsync(() -> {
            // Always pre-load player data (for per-player mode or per-player caps in shared mode)
            List<PlayerTradeData> trades = dataStore.loadPlayerShopData(playerId, shopId);
            for (PlayerTradeData data : trades) {
                String cacheKey = data.getCacheKey();
                tradeCache.putIfAbsent(cacheKey, data);
                trackCacheKey(playerId, cacheKey);
            }

            // For shared shops, also pre-load global trade data
            if (shopConfig.isShared()) {
                List<GlobalTradeData> globalTrades = dataStore.loadGlobalShopData(shopId);
                for (GlobalTradeData data : globalTrades) {
                    globalTradeCache.putIfAbsent(data.getCacheKey(), data);
                }
            }

            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Pre-loaded " + trades.size() +
                        " trade entries for " + playerId + " in shop " + shopId);
            }
        });
    }

    // ===== Eviction & Cleanup =====

    /**
     * Evicts all data for a shop from cache and dirty tracking.
     */
    public void evictShop(String shopId) {
        evictShopPlayerData(shopId);

        // Also evict global data for the shop
        globalTradeCache.entrySet().removeIf(e -> e.getValue().getShopId().equals(shopId));
        globalDirtyKeys.removeIf(key -> key.startsWith(shopId + ":"));
    }

    /**
     * Evicts player trade data for a specific shop from cache.
     */
    private void evictShopPlayerData(String shopId) {
        tradeCache.entrySet().removeIf(e -> {
            if (e.getValue().getShopId().equals(shopId)) {
                untrackCacheKey(e.getValue().getPlayerId(), e.getKey());
                return true;
            }
            return false;
        });
        String suffix = ":" + shopId + ":";
        dirtyKeys.removeIf(key -> {
            int firstColon = key.indexOf(':');
            return firstColon >= 0 && key.startsWith(suffix, firstColon);
        });
    }

    /**
     * Evicts a player's data from cache (e.g., on player quit).
     */
    public void evictPlayer(UUID playerId) {
        flushPlayerData(playerId);

        Set<String> keys = playerCacheKeys.remove(playerId);
        if (keys != null) {
            for (String key : keys) {
                tradeCache.remove(key);
            }
        }

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("Evicted cache for player " + playerId);
        }
    }

    /**
     * Cleans up expired cooldowns from cache.
     * Skips NONE mode entries (they never expire).
     */
    public int cleanupExpiredCooldowns() {
        int cleaned = 0;

        // Clean up per-player entries
        List<String> toRemove = new ArrayList<>();
        Map<String, Long> resetTimeCache = new HashMap<>();

        for (Map.Entry<String, PlayerTradeData> entry : tradeCache.entrySet()) {
            PlayerTradeData data = entry.getValue();
            if (data.getTradesUsed() <= 0) continue;

            TradeConfig tradeConfig = getTradeConfig(data.getShopId(), data.getTradeKey());
            if (tradeConfig != null && tradeConfig.getCooldownMode() == CooldownMode.NONE) continue;

            if (isExpiredCached(data, tradeConfig, resetTimeCache)) {
                toRemove.add(entry.getKey());
            }
        }

        for (String key : toRemove) {
            PlayerTradeData data = tradeCache.remove(key);
            dirtyKeys.remove(key);
            if (data != null) {
                untrackCacheKey(data.getPlayerId(), key);
            }
        }
        cleaned += toRemove.size();

        // Clean up expired global entries
        List<String> globalToRemove = new ArrayList<>();
        for (Map.Entry<String, GlobalTradeData> entry : globalTradeCache.entrySet()) {
            GlobalTradeData data = entry.getValue();
            if (data.getTradesUsed() <= 0) continue;

            TradeConfig tradeConfig = getTradeConfig(data.getShopId(), data.getTradeKey());
            if (tradeConfig != null && tradeConfig.getCooldownMode() == CooldownMode.NONE) continue;

            if (isGlobalExpired(data, tradeConfig)) {
                globalToRemove.add(entry.getKey());
            }
        }

        for (String key : globalToRemove) {
            globalTradeCache.remove(key);
            globalDirtyKeys.remove(key);
        }
        cleaned += globalToRemove.size();

        if (plugin.getConfigManager().isDebugMode() && cleaned > 0) {
            plugin.getLogger().info("Cleaned up " + cleaned + " expired cooldown entries from cache");
        }

        return cleaned;
    }

    // ===== Auto-Purge =====

    /**
     * Purges trade data for players who haven't logged in within the configured threshold.
     *
     * @return CompletableFuture resolving to the number of players purged
     */
    public CompletableFuture<Integer> purgeInactivePlayers() {
        int purgeDays = plugin.getConfigManager().getPurgeInactiveDays();
        if (purgeDays <= 0) {
            return CompletableFuture.completedFuture(0);
        }

        long thresholdMillis = System.currentTimeMillis() - (purgeDays * 86_400_000L);

        return CompletableFuture.supplyAsync(() -> {
            List<UUID> allPlayers = dataStore.getAllPlayers();
            int purged = 0;

            for (UUID playerId : allPlayers) {
                long lastPlayed = Bukkit.getOfflinePlayer(playerId).getLastPlayed();

                if (lastPlayed > 0 && lastPlayed < thresholdMillis) {
                    // Evict from cache
                    Set<String> keys = playerCacheKeys.remove(playerId);
                    if (keys != null) {
                        for (String key : keys) {
                            tradeCache.remove(key);
                            dirtyKeys.remove(key);
                        }
                    }

                    // Delete from database
                    dataStore.deletePlayerData(playerId);
                    purged++;
                }
            }

            return purged;
        });
    }

    // ===== Flush / Persistence =====

    /**
     * Flushes dirty data to database asynchronously.
     */
    private void flushDirtyData() {
        // Flush per-player data
        if (!dirtyKeys.isEmpty()) {
            List<PlayerTradeData> dataToSave = new ArrayList<>();
            Iterator<String> it = dirtyKeys.iterator();
            while (it.hasNext()) {
                String key = it.next();
                it.remove();
                PlayerTradeData data = tradeCache.get(key);
                if (data != null) {
                    dataToSave.add(data);
                }
            }
            if (!dataToSave.isEmpty()) {
                dataStore.batchSaveTradeData(dataToSave);
            }
        }

        // Flush global data
        if (!globalDirtyKeys.isEmpty()) {
            List<GlobalTradeData> globalToSave = new ArrayList<>();
            Iterator<String> git = globalDirtyKeys.iterator();
            while (git.hasNext()) {
                String key = git.next();
                git.remove();
                GlobalTradeData data = globalTradeCache.get(key);
                if (data != null) {
                    globalToSave.add(data);
                }
            }
            if (!globalToSave.isEmpty()) {
                dataStore.batchSaveGlobalTradeData(globalToSave);
            }
        }
    }

    /**
     * Flushes a specific player's dirty data.
     */
    public void flushPlayerData(UUID playerId) {
        Set<String> keys = playerCacheKeys.get(playerId);
        if (keys == null || keys.isEmpty()) return;

        List<PlayerTradeData> dataToSave = new ArrayList<>();
        for (String key : keys) {
            if (dirtyKeys.remove(key)) {
                PlayerTradeData data = tradeCache.get(key);
                if (data != null) {
                    dataToSave.add(data);
                }
            }
        }

        if (!dataToSave.isEmpty()) {
            dataStore.batchSaveTradeData(dataToSave);
        }
    }

    /**
     * Flushes all dirty data synchronously (for shutdown).
     */
    private void flushAllDirtyData() {
        // Per-player data
        if (!dirtyKeys.isEmpty()) {
            plugin.getLogger().info("Flushing " + dirtyKeys.size() + " dirty player entries...");

            List<PlayerTradeData> dataToSave = new ArrayList<>();
            for (String key : dirtyKeys) {
                PlayerTradeData data = tradeCache.get(key);
                if (data != null) {
                    dataToSave.add(data);
                }
            }
            dirtyKeys.clear();

            if (!dataToSave.isEmpty()) {
                dataStore.batchSaveTradeData(dataToSave);
            }
            plugin.getLogger().info("Flushed " + dataToSave.size() + " player entries successfully");
        }

        // Global data
        if (!globalDirtyKeys.isEmpty()) {
            plugin.getLogger().info("Flushing " + globalDirtyKeys.size() + " dirty global entries...");

            List<GlobalTradeData> globalToSave = new ArrayList<>();
            for (String key : globalDirtyKeys) {
                GlobalTradeData data = globalTradeCache.get(key);
                if (data != null) {
                    globalToSave.add(data);
                }
            }
            globalDirtyKeys.clear();

            if (!globalToSave.isEmpty()) {
                dataStore.batchSaveGlobalTradeData(globalToSave);
            }
            plugin.getLogger().info("Flushed " + globalToSave.size() + " global entries successfully");
        }

        if (dirtyKeys.isEmpty() && globalDirtyKeys.isEmpty()) {
            plugin.getLogger().info("No dirty data to flush");
        }
    }

    // ===== Global Data Helpers =====

    /**
     * Gets global trade data from cache or database.
     */
    private GlobalTradeData getGlobalTradeData(String shopId, String tradeKey) {
        String cacheKey = shopId + ":" + tradeKey;
        GlobalTradeData cached = globalTradeCache.get(cacheKey);
        if (cached != null) return cached;

        GlobalTradeData data = dataStore.loadGlobalTradeData(shopId, tradeKey);
        if (data != null) {
            globalTradeCache.put(cacheKey, data);
        }
        return data;
    }

    /**
     * Gets or creates global trade data.
     */
    private GlobalTradeData getOrCreateGlobalTradeData(String shopId, String tradeKey) {
        GlobalTradeData data = getGlobalTradeData(shopId, tradeKey);
        if (data == null) {
            int cooldown = getCooldownSeconds(shopId, tradeKey);
            long now = System.currentTimeMillis() / 1000;
            data = new GlobalTradeData(shopId, tradeKey, 0, now, cooldown);
            globalTradeCache.put(data.getCacheKey(), data);
        }
        return data;
    }

    // ===== Per-Player Data Helpers =====

    private PlayerTradeData getTradeData(UUID playerId, String shopId, String tradeKey) {
        String cacheKey = buildCacheKey(playerId, shopId, tradeKey);

        PlayerTradeData cached = tradeCache.get(cacheKey);
        if (cached != null) return cached;

        PlayerTradeData data = dataStore.loadTradeData(playerId, shopId, tradeKey);
        if (data != null) {
            tradeCache.put(cacheKey, data);
            trackCacheKey(playerId, cacheKey);
        }

        return data;
    }

    private PlayerTradeData getOrCreateTradeData(UUID playerId, String shopId, String tradeKey) {
        PlayerTradeData data = getTradeData(playerId, shopId, tradeKey);

        if (data == null) {
            int cooldown = getCooldownSeconds(shopId, tradeKey);
            long now = System.currentTimeMillis() / 1000;
            data = new PlayerTradeData(playerId, shopId, tradeKey, 0, now, cooldown);
            String cacheKey = data.getCacheKey();
            tradeCache.put(cacheKey, data);
            trackCacheKey(playerId, cacheKey);
        }

        return data;
    }

    // ===== Config Helpers =====

    private TradeConfig getTradeConfig(String shopId, String tradeKey) {
        ShopConfig shop = plugin.getConfigManager().getShop(shopId);
        if (shop == null) return null;
        return shop.getTrade(tradeKey);
    }

    private int getTradeLimit(String shopId, String tradeKey) {
        ShopConfig shop = plugin.getConfigManager().getShop(shopId);
        if (shop == null) return 1;

        TradeConfig trade = shop.getTrade(tradeKey);
        if (trade == null) return 1;

        return trade.getMaxTrades();
    }

    private int getCooldownSeconds(String shopId, String tradeKey) {
        ShopConfig shop = plugin.getConfigManager().getShop(shopId);
        if (shop == null) return 86400;

        TradeConfig trade = shop.getTrade(tradeKey);
        if (trade == null) return 86400;

        return trade.getCooldownSeconds();
    }

    // ===== Cache Key Helpers =====

    private void markDirty(String cacheKey) {
        dirtyKeys.add(cacheKey);
    }

    private String buildCacheKey(UUID playerId, String shopId, String tradeKey) {
        return playerId + ":" + shopId + ":" + tradeKey;
    }

    private void trackCacheKey(UUID playerId, String cacheKey) {
        playerCacheKeys.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(cacheKey);
    }

    private void untrackCacheKey(UUID playerId, String cacheKey) {
        Set<String> keys = playerCacheKeys.get(playerId);
        if (keys != null) {
            keys.remove(cacheKey);
            if (keys.isEmpty()) {
                playerCacheKeys.remove(playerId);
            }
        }
    }
}
