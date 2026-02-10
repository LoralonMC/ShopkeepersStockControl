package dev.oakheart.stockcontrol.managers;

import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * Manages periodic cleanup of expired cooldowns and cached data.
 * Helps keep memory usage efficient by removing stale entries.
 */
public class CooldownManager {

    private final ShopkeepersStockControl plugin;
    private final TradeDataManager tradeDataManager;

    private BukkitTask cleanupTask;

    public CooldownManager(ShopkeepersStockControl plugin, TradeDataManager tradeDataManager) {
        this.plugin = plugin;
        this.tradeDataManager = tradeDataManager;
    }

    /**
     * Initializes the cooldown manager and starts the cleanup task.
     */
    public void initialize() {
        int interval = plugin.getConfigManager().getCooldownCheckInterval();

        // Schedule cleanup task to run periodically
        cleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::performCleanup,
                interval * 20L, // Convert seconds to ticks (initial delay)
                interval * 20L  // Convert seconds to ticks (period)
        );

        plugin.getLogger().info("CooldownManager initialized with cleanup interval: " + interval + "s");
    }

    /**
     * Shuts down the cooldown manager and cancels the cleanup task.
     */
    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }

        plugin.getLogger().info("CooldownManager shutdown complete");
    }

    /**
     * Performs cleanup of expired cooldowns and spam cache.
     * This is called periodically by the scheduled task.
     */
    private void performCleanup() {
        try {
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Running periodic cooldown cleanup...");
            }

            // Clean up expired trade cooldowns from cache
            int cleanedCooldowns = tradeDataManager.cleanupExpiredCooldowns();

            // Log statistics if anything was cleaned up
            if (cleanedCooldowns > 0 || plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Cleanup complete: " + cleanedCooldowns + " expired cooldowns removed");
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Error during periodic cleanup: " + e.getMessage());
            if (plugin.getConfigManager().isDebugMode()) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Manually triggers a cleanup cycle (useful for commands).
     *
     * @return Number of entries cleaned up
     */
    public int triggerManualCleanup() {
        plugin.getLogger().info("Manual cleanup triggered");
        int cleaned = tradeDataManager.cleanupExpiredCooldowns();
        plugin.getLogger().info("Manual cleanup complete: " + cleaned + " entries removed");
        return cleaned;
    }
}
