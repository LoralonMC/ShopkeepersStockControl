package dev.oakheart.stockcontrol.managers;

import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.CooldownMode;
import dev.oakheart.stockcontrol.data.DataStore;
import dev.oakheart.stockcontrol.data.PlayerTradeData;
import dev.oakheart.stockcontrol.data.ShopConfig;
import dev.oakheart.stockcontrol.data.TradeConfig;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player trade data including cooldowns, limits, and persistence.
 * Uses in-memory caching with dirty tracking for performance.
 */
public class TradeDataManager {

    private final ShopkeepersStockControl plugin;
    private final DataStore dataStore;

    // In-memory cache: cacheKey -> PlayerTradeData
    private final Map<String, PlayerTradeData> tradeCache;

    // Dirty tracking for batch writes
    private final Set<String> dirtyKeys;

    // Scheduled tasks
    private BukkitTask batchWriteTask;

    public TradeDataManager(ShopkeepersStockControl plugin, DataStore dataStore) {
        this.plugin = plugin;
        this.dataStore = dataStore;
        this.tradeCache = new ConcurrentHashMap<>();
        this.dirtyKeys = ConcurrentHashMap.newKeySet();
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

    /**
     * Checks if a player can perform a trade (considering cooldowns and limits).
     * This implements the critical cooldown logic from the design document.
     *
     * @param playerId The player's UUID
     * @param shopId   The shop identifier
     * @param tradeKey The trade key
     * @return true if the player can trade, false otherwise
     */
    public boolean canTrade(UUID playerId, String shopId, String tradeKey) {
        PlayerTradeData data = getTradeData(playerId, shopId, tradeKey);

        // First time trading - always allowed
        if (data == null) {
            return true;
        }

        long now = System.currentTimeMillis() / 1000; // Current epoch seconds
        TradeConfig tradeConfig = getTradeConfig(shopId, tradeKey);
        CooldownMode mode = tradeConfig != null ? tradeConfig.getCooldownMode() : CooldownMode.ROLLING;

        switch (mode) {
            case DAILY:
            case WEEKLY:
                // Check if we've passed the most recent reset time
                if (hasCooldownExpired(playerId, shopId, tradeKey)) {
                    data.setTradesUsed(0);
                    data.setLastResetEpoch(now);
                    markDirty(data.getCacheKey());
                    return true;
                }
                break;

            case ROLLING:
            default:
                // Use rolling cooldown logic
                long elapsed = now - data.getLastResetEpoch();

                // Clamp negative elapsed (clock jumped backwards)
                if (elapsed < 0) {
                    elapsed = 0;
                    if (plugin.getConfigManager().isDebugMode()) {
                        plugin.getLogger().warning("Clock jumped backwards for player " + playerId);
                    }
                }

                // If cooldown expired, reset trades
                if (elapsed >= data.getCooldownSeconds()) {
                    data.setTradesUsed(0);
                    data.setLastResetEpoch(now);
                    markDirty(data.getCacheKey());
                    return true;
                }
                break;
        }

        // Check if under limit
        int limit = getTradeLimit(shopId, tradeKey);
        return data.getTradesUsed() < limit;
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
        PlayerTradeData data = getTradeData(playerId, shopId, tradeKey);

        // First time trading - return full limit
        if (data == null) {
            return getTradeLimit(shopId, tradeKey);
        }

        // If cooldown expired, return full limit
        if (hasCooldownExpired(playerId, shopId, tradeKey)) {
            return getTradeLimit(shopId, tradeKey);
        }

        // Return remaining trades
        int limit = getTradeLimit(shopId, tradeKey);
        int remaining = limit - data.getTradesUsed();
        return Math.max(0, remaining);
    }

    /**
     * Records a trade (increments count and starts cooldown if limit reached).
     *
     * @param playerId The player's UUID
     * @param shopId   The shop identifier
     * @param tradeKey The trade key
     */
    public void recordTrade(UUID playerId, String shopId, String tradeKey) {
        PlayerTradeData data = getOrCreateTradeData(playerId, shopId, tradeKey);

        // Increment usage. lastResetEpoch stays at creation time (first trade) — the rolling
        // cooldown window always starts from the first trade, not from when the limit is hit.
        data.setTradesUsed(data.getTradesUsed() + 1);

        markDirty(data.getCacheKey());

        if (plugin.getConfigManager().isDebugMode()) {
            int limit = getTradeLimit(shopId, tradeKey);
            plugin.getLogger().info("Recorded trade for " + playerId + " at " + shopId + ":" + tradeKey +
                    " (used: " + data.getTradesUsed() + "/" + limit + ")");
        }
    }

    /**
     * Gets the reset time display string for a specific trade.
     * Returns the configured reset time for daily/weekly modes, or empty string for rolling.
     *
     * @param shopId   The shop identifier
     * @param tradeKey The trade key
     * @return Reset time string (e.g., "00:00", "Monday 00:00") or empty string for rolling
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
            case ROLLING:
            default:
                return "";
        }
    }

    /**
     * Formats a duration in seconds to a human-readable string.
     *
     * @param seconds Duration in seconds
     * @return Formatted string (e.g., "23h 45m", "6d 12h")
     */
    public String formatDuration(long seconds) {
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

    /**
     * Calculates the next reset time in epoch seconds for a trade's cooldown mode.
     * Supports daily (next occurrence of HH:mm) and weekly (next occurrence of day + HH:mm).
     *
     * @param tradeConfig The trade configuration
     * @return Epoch seconds for the next reset time
     */
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

            // If the candidate is today but the time has already passed, jump to next week
            if (!candidate.isAfter(now)) {
                candidate = now.with(TemporalAdjusters.next(targetDay))
                        .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
            }
            return candidate.toEpochSecond();
        }

        // DAILY mode
        ZonedDateTime todayReset = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        if (now.isAfter(todayReset)) {
            todayReset = todayReset.plusDays(1);
        }
        return todayReset.toEpochSecond();
    }

    /**
     * Checks if cooldown has expired for a specific trade.
     *
     * @param playerId The player's UUID
     * @param shopId   The shop identifier
     * @param tradeKey The trade key
     * @return true if cooldown has expired, false otherwise
     */
    public boolean hasCooldownExpired(UUID playerId, String shopId, String tradeKey) {
        PlayerTradeData data = getTradeData(playerId, shopId, tradeKey);

        if (data == null) {
            return true; // No data means no cooldown
        }

        long now = System.currentTimeMillis() / 1000;
        TradeConfig tradeConfig = getTradeConfig(shopId, tradeKey);
        CooldownMode mode = tradeConfig != null ? tradeConfig.getCooldownMode() : CooldownMode.ROLLING;

        switch (mode) {
            case DAILY: {
                long nextResetTime = getNextResetTime(tradeConfig);
                long lastResetTime = nextResetTime - 86400; // Previous day's reset
                return data.getLastResetEpoch() < lastResetTime;
            }
            case WEEKLY: {
                long nextResetTime = getNextResetTime(tradeConfig);
                long lastResetTime = nextResetTime - (7 * 86400); // Previous week's reset
                return data.getLastResetEpoch() < lastResetTime;
            }
            case ROLLING:
            default: {
                long elapsed = now - data.getLastResetEpoch();
                return elapsed >= data.getCooldownSeconds();
            }
        }
    }

    /**
     * Gets the time remaining until cooldown expires (in seconds).
     *
     * @param playerId The player's UUID
     * @param shopId   The shop identifier
     * @param tradeKey The trade key
     * @return Seconds remaining, or 0 if no cooldown
     */
    public long getTimeUntilReset(UUID playerId, String shopId, String tradeKey) {
        PlayerTradeData data = getTradeData(playerId, shopId, tradeKey);

        if (data == null) {
            return 0; // No data means no cooldown
        }

        long now = System.currentTimeMillis() / 1000;
        TradeConfig tradeConfig = getTradeConfig(shopId, tradeKey);
        CooldownMode mode = tradeConfig != null ? tradeConfig.getCooldownMode() : CooldownMode.ROLLING;

        switch (mode) {
            case DAILY:
            case WEEKLY: {
                long nextResetTime = getNextResetTime(tradeConfig);
                long remaining = nextResetTime - now;
                return Math.max(0, remaining);
            }
            case ROLLING:
            default: {
                long elapsed = now - data.getLastResetEpoch();
                long remaining = data.getCooldownSeconds() - elapsed;
                return Math.max(0, remaining);
            }
        }
    }

    /**
     * Resets a specific player's specific trade.
     *
     * @param playerId The player's UUID
     * @param shopId   The shop identifier
     * @param tradeKey The trade key
     */
    public void resetPlayerTrade(UUID playerId, String shopId, String tradeKey) {
        String cacheKey = buildCacheKey(playerId, shopId, tradeKey);
        tradeCache.remove(cacheKey);
        dirtyKeys.remove(cacheKey);
        dataStore.deleteTradeData(playerId, shopId, tradeKey);

        plugin.getLogger().info("Reset trade " + tradeKey + " for player " + playerId + " in shop " + shopId);
    }

    /**
     * Resets all trades for a specific player.
     *
     * @param playerId The player's UUID
     */
    public void resetPlayerTrades(UUID playerId) {
        // Remove from cache
        tradeCache.entrySet().removeIf(entry -> entry.getValue().getPlayerId().equals(playerId));
        dirtyKeys.removeIf(key -> key.startsWith(playerId.toString() + ":"));

        // Delete from database
        dataStore.deletePlayerData(playerId);

        plugin.getLogger().info("Reset all trades for player " + playerId);
    }

    /**
     * Gets all trade data for a specific player.
     *
     * @param playerId The player's UUID
     * @return List of PlayerTradeData
     */
    public List<PlayerTradeData> getPlayerTrades(UUID playerId) {
        // Load from database if not in cache
        List<PlayerTradeData> trades = dataStore.loadPlayerData(playerId);

        // Update cache
        for (PlayerTradeData data : trades) {
            tradeCache.putIfAbsent(data.getCacheKey(), data);
        }

        return trades;
    }

    /**
     * Pre-loads all trade data for a player+shop into cache.
     * Called on shop open (main thread) to ensure the packet thread never hits the database.
     *
     * @param playerId The player's UUID
     * @param shopId   The shop identifier
     */
    public void preloadShopData(UUID playerId, String shopId) {
        ShopConfig shopConfig = plugin.getConfigManager().getShop(shopId);
        if (shopConfig == null) return;

        for (TradeConfig trade : shopConfig.getTrades().values()) {
            String cacheKey = buildCacheKey(playerId, shopId, trade.getTradeKey());
            if (!tradeCache.containsKey(cacheKey)) {
                PlayerTradeData data = dataStore.loadTradeData(playerId, shopId, trade.getTradeKey());
                if (data != null) {
                    tradeCache.put(cacheKey, data);
                }
            }
        }

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("Pre-loaded trade data for " + playerId + " in shop " + shopId);
        }
    }

    /**
     * Evicts all data for a shop from cache and dirty tracking.
     * Used when a shop is removed from config to clean up orphaned data.
     *
     * @param shopId The shop identifier
     */
    public void evictShop(String shopId) {
        tradeCache.entrySet().removeIf(e -> e.getValue().getShopId().equals(shopId));
        // Cache key format: "playerUUID:shopId:tradeKey" — match shopId between first and second colon
        String suffix = ":" + shopId + ":";
        dirtyKeys.removeIf(key -> {
            int firstColon = key.indexOf(':');
            return firstColon >= 0 && key.startsWith(suffix, firstColon);
        });
    }

    /**
     * Evicts a player's data from cache (e.g., on player quit).
     *
     * @param playerId The player's UUID
     */
    public void evictPlayer(UUID playerId) {
        // Flush dirty data for this player first
        flushPlayerData(playerId);

        // Remove from cache
        tradeCache.entrySet().removeIf(entry -> entry.getValue().getPlayerId().equals(playerId));

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("Evicted cache for player " + playerId);
        }
    }

    /**
     * Cleans up expired cooldowns from cache and optionally from database.
     * This helps keep memory usage low by removing stale data.
     *
     * @return Number of entries cleaned up
     */
    public int cleanupExpiredCooldowns() {
        List<String> toRemove = new ArrayList<>();

        // Find expired entries
        for (Map.Entry<String, PlayerTradeData> entry : tradeCache.entrySet()) {
            PlayerTradeData data = entry.getValue();

            // If cooldown has expired and the trade has been used
            if (hasCooldownExpired(data.getPlayerId(), data.getShopId(), data.getTradeKey())
                    && data.getTradesUsed() > 0) {
                toRemove.add(entry.getKey());
            }
        }

        // Remove from cache
        for (String key : toRemove) {
            tradeCache.remove(key);
            dirtyKeys.remove(key); // No need to save expired data
        }

        if (plugin.getConfigManager().isDebugMode() && !toRemove.isEmpty()) {
            plugin.getLogger().info("Cleaned up " + toRemove.size() + " expired cooldown entries from cache");
        }

        return toRemove.size();
    }

    /**
     * Gets trade data from cache or database.
     */
    private PlayerTradeData getTradeData(UUID playerId, String shopId, String tradeKey) {
        String cacheKey = buildCacheKey(playerId, shopId, tradeKey);

        // Check cache first
        PlayerTradeData cached = tradeCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // Load from database
        PlayerTradeData data = dataStore.loadTradeData(playerId, shopId, tradeKey);
        if (data != null) {
            tradeCache.put(cacheKey, data);
        }

        return data;
    }

    /**
     * Gets or creates trade data.
     */
    private PlayerTradeData getOrCreateTradeData(UUID playerId, String shopId, String tradeKey) {
        PlayerTradeData data = getTradeData(playerId, shopId, tradeKey);

        if (data == null) {
            // Create new data
            int cooldown = getCooldownSeconds(shopId, tradeKey);
            long now = System.currentTimeMillis() / 1000;
            data = new PlayerTradeData(playerId, shopId, tradeKey, 0, now, cooldown);
            tradeCache.put(data.getCacheKey(), data);
        }

        return data;
    }

    /**
     * Gets the TradeConfig for a specific shop and trade.
     */
    private TradeConfig getTradeConfig(String shopId, String tradeKey) {
        ShopConfig shop = plugin.getConfigManager().getShop(shopId);
        if (shop == null) return null;
        return shop.getTrade(tradeKey);
    }

    /**
     * Gets the trade limit from configuration.
     */
    private int getTradeLimit(String shopId, String tradeKey) {
        ShopConfig shop = plugin.getConfigManager().getShop(shopId);
        if (shop == null) return 1; // Default

        TradeConfig trade = shop.getTrade(tradeKey);
        if (trade == null) return 1; // Default

        return trade.getMaxTrades();
    }

    /**
     * Gets the cooldown duration from configuration.
     */
    private int getCooldownSeconds(String shopId, String tradeKey) {
        ShopConfig shop = plugin.getConfigManager().getShop(shopId);
        if (shop == null) return 86400; // Default 24 hours

        TradeConfig trade = shop.getTrade(tradeKey);
        if (trade == null) return 86400; // Default 24 hours

        return trade.getCooldownSeconds();
    }

    /**
     * Marks a cache entry as dirty for batch writing.
     */
    private void markDirty(String cacheKey) {
        dirtyKeys.add(cacheKey);
    }

    /**
     * Flushes dirty data to database asynchronously.
     * Uses atomic per-key removal to prevent race conditions with concurrent markDirty() calls.
     */
    private void flushDirtyData() {
        if (dirtyKeys.isEmpty()) return;

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

    /**
     * Flushes a specific player's dirty data to ensure database is up-to-date.
     * Used by commands that need to read the latest data.
     *
     * @param playerId The player's UUID
     */
    public void flushPlayerData(UUID playerId) {
        String prefix = playerId.toString() + ":";
        Set<String> playerDirtyKeys = new HashSet<>();

        for (String key : dirtyKeys) {
            if (key.startsWith(prefix)) {
                playerDirtyKeys.add(key);
            }
        }

        if (playerDirtyKeys.isEmpty()) return;

        List<PlayerTradeData> dataToSave = new ArrayList<>();
        for (String key : playerDirtyKeys) {
            PlayerTradeData data = tradeCache.get(key);
            if (data != null) {
                dataToSave.add(data);
            }
        }

        dirtyKeys.removeAll(playerDirtyKeys);

        if (!dataToSave.isEmpty()) {
            dataStore.batchSaveTradeData(dataToSave);
        }
    }

    /**
     * Flushes all dirty data synchronously (for shutdown).
     */
    private void flushAllDirtyData() {
        if (dirtyKeys.isEmpty()) {
            plugin.getLogger().info("No dirty data to flush");
            return;
        }

        plugin.getLogger().info("Flushing " + dirtyKeys.size() + " dirty entries...");

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

        plugin.getLogger().info("Flushed " + dataToSave.size() + " entries successfully");
    }

    /**
     * Builds a cache key from player, shop, and trade identifiers.
     */
    private String buildCacheKey(UUID playerId, String shopId, String tradeKey) {
        return playerId + ":" + shopId + ":" + tradeKey;
    }
}
