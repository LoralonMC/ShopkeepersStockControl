package dev.oakheart.stockcontrol.managers;

import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;

/**
 * Manages periodic cleanup of expired cooldowns and cached data.
 * Helps keep memory usage efficient by removing stale entries.
 */
public class CooldownManager {

    private final ShopkeepersStockControl plugin;
    private final TradeDataManager tradeDataManager;

    private BukkitTask cleanupTask;
    private BukkitTask purgeTask;

    public CooldownManager(ShopkeepersStockControl plugin, TradeDataManager tradeDataManager) {
        this.plugin = plugin;
        this.tradeDataManager = tradeDataManager;
    }

    /**
     * Initializes the cooldown manager and starts the cleanup task.
     * Also schedules auto-purge of inactive player data if configured.
     */
    public void initialize() {
        startCleanupTask();
        startPurgeTask();
        plugin.getLogger().info("CooldownManager initialized with cleanup interval: "
                + plugin.getConfigManager().getCooldownCheckInterval() + "s");
    }

    /**
     * Restarts the cleanup task with the current config interval.
     * Called after config reload to pick up interval changes.
     */
    public void restart() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        if (purgeTask != null) {
            purgeTask.cancel();
            purgeTask = null;
        }
        startCleanupTask();
        startPurgeTask();
        plugin.getLogger().info("Cleanup task restarted with interval: "
                + plugin.getConfigManager().getCooldownCheckInterval() + "s");
    }

    /**
     * Shuts down the cooldown manager and cancels all tasks.
     */
    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        if (purgeTask != null) {
            purgeTask.cancel();
            purgeTask = null;
        }

        plugin.getLogger().info("CooldownManager shutdown complete");
    }

    private void startCleanupTask() {
        int interval = plugin.getConfigManager().getCooldownCheckInterval();
        cleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::performCleanup,
                interval * 20L,
                interval * 20L
        );
    }

    /**
     * Performs cleanup of expired cooldowns.
     * This is called periodically by the scheduled task.
     */
    private void performCleanup() {
        try {
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Running periodic cooldown cleanup...");
            }

            int cleanedCooldowns = tradeDataManager.cleanupExpiredCooldowns();

            if (cleanedCooldowns > 0 || plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Cleanup complete: " + cleanedCooldowns + " expired cooldowns removed");
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error during periodic cleanup", e);
        }
    }

    /**
     * Starts the auto-purge task for inactive player data.
     * Runs an initial purge 5 seconds after startup, then daily thereafter.
     */
    private void startPurgeTask() {
        int purgeDays = plugin.getConfigManager().getPurgeInactiveDays();
        if (purgeDays <= 0) return;

        // Initial purge 5 seconds after startup
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::performPurge, 100L);

        // Daily repeating purge (24 hours = 1,728,000 ticks)
        purgeTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::performPurge,
                1_728_000L,
                1_728_000L
        );

        plugin.getLogger().info("Auto-purge enabled: removing data for players inactive > " + purgeDays + " days");
    }

    /**
     * Performs the auto-purge of inactive player data.
     */
    private void performPurge() {
        try {
            tradeDataManager.purgeInactivePlayers().thenAccept(purged -> {
                if (purged > 0) {
                    plugin.getLogger().info("Auto-purge: removed data for " + purged + " inactive player(s)");
                } else if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().info("Auto-purge: no inactive players to purge");
                }
            });
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error during auto-purge", e);
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
