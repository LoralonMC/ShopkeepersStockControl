package dev.oakheart.stockcontrol.managers;

import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.DataStore;
import dev.oakheart.stockcontrol.data.PlayerTradeData;
import dev.oakheart.stockcontrol.data.ShopConfig;
import dev.oakheart.stockcontrol.data.TradeConfig;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

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

        long now = System.currentTimeMillis() / 1000;
        long elapsed = now - data.getLastResetEpoch();

        // Clamp negative elapsed
        if (elapsed < 0) {
            elapsed = 0;
        }

        // If cooldown expired, return full limit
        if (elapsed >= data.getCooldownSeconds()) {
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
        long now = System.currentTimeMillis() / 1000;

        // Increment usage
        data.setTradesUsed(data.getTradesUsed() + 1);

        // If limit just reached, start cooldown timer
        int limit = getTradeLimit(shopId, tradeKey);
        if (data.getTradesUsed() >= limit) {
            data.setLastResetEpoch(now); // Cooldown starts NOW
        }

        markDirty(data.getCacheKey());

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("Recorded trade for " + playerId + " at " + shopId + ":" + tradeKey +
                    " (used: " + data.getTradesUsed() + "/" + limit + ")");
        }
    }

    /**
     * Gets the formatted reset time string for display.
     * Returns " - Resets at HH:mm" if using fixed daily reset, or empty string for rolling cooldowns.
     *
     * @return Formatted reset time string or empty string
     */
    public String getResetTimeString() {
        if (plugin.getConfigManager().isFixedDailyReset()) {
            return " - Resets at " + plugin.getConfigManager().getDailyResetTime();
        }
        return "";
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
     * Calculates the next reset time in epoch seconds based on configured daily reset time.
     *
     * @return Epoch seconds for the next reset time (today or tomorrow)
     */
    private long getNextDailyResetTime() {
        String resetTimeStr = plugin.getConfigManager().getDailyResetTime();
        String[] parts = resetTimeStr.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);

        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        java.time.ZonedDateTime todayReset = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);

        // If today's reset time has already passed, use tomorrow's reset time
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

        // Use fixed daily reset if enabled
        if (plugin.getConfigManager().isFixedDailyReset()) {
            // Check if the last trade was before the most recent reset time
            long nextResetTime = getNextDailyResetTime();
            long lastResetTime = nextResetTime - 86400; // Yesterday's reset time

            return data.getLastResetEpoch() < lastResetTime;
        } else {
            // Use rolling cooldown
            long elapsed = now - data.getLastResetEpoch();
            return elapsed >= data.getCooldownSeconds();
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

        // Use fixed daily reset if enabled
        if (plugin.getConfigManager().isFixedDailyReset()) {
            long nextResetTime = getNextDailyResetTime();
            long remaining = nextResetTime - now;
            return Math.max(0, remaining);
        } else {
            // Use rolling cooldown
            long elapsed = now - data.getLastResetEpoch();
            long remaining = data.getCooldownSeconds() - elapsed;
            return Math.max(0, remaining);
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
        long now = System.currentTimeMillis() / 1000;
        List<String> toRemove = new ArrayList<>();

        // Find expired entries
        for (Map.Entry<String, PlayerTradeData> entry : tradeCache.entrySet()) {
            PlayerTradeData data = entry.getValue();
            long elapsed = now - data.getLastResetEpoch();

            // If cooldown has expired and the trade has been used
            if (elapsed >= data.getCooldownSeconds() && data.getTradesUsed() > 0) {
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
     */
    private void flushDirtyData() {
        if (dirtyKeys.isEmpty()) return;

        Set<String> toFlush = new HashSet<>(dirtyKeys);
        dirtyKeys.removeAll(toFlush);

        List<PlayerTradeData> dataToSave = new ArrayList<>();
        for (String key : toFlush) {
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
