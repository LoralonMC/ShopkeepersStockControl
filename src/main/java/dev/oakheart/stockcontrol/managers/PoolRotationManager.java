package dev.oakheart.stockcontrol.managers;

import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.DataStore;
import dev.oakheart.stockcontrol.data.PoolConfig;
import dev.oakheart.stockcontrol.data.RotationState;
import dev.oakheart.stockcontrol.data.ShopConfig;
import dev.oakheart.stockcontrol.util.RotationScheduler;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Owns the lifecycle of rotation pool states.
 *
 * <ul>
 *   <li>Loads persisted snapshots at startup and reconciles them against current configs.</li>
 *   <li>Runs a scheduled async check that advances a pool when its scheduled boundary passes.</li>
 *   <li>On advance, wipes per-item counters (per user preference: "every rotation tick resets
 *       all currently-active items") and persists the new snapshot.</li>
 * </ul>
 *
 * Reload intentionally does not re-advance — players mid-session keep their current rotation
 * until the next scheduled boundary.
 */
public class PoolRotationManager {

    // Check cadence in ticks. One second is cheap (iterate pools, compare timestamps, all async)
    // and gives ~1s accuracy even for short interval schedules like "every: 10s".
    // Sub-second precision isn't supported by the YAML duration syntax anyway.
    private static final long CHECK_INTERVAL_TICKS = 20L;

    private final ShopkeepersStockControl plugin;
    private final DataStore dataStore;
    private final TradeDataManager tradeDataManager;

    // Map<shopId, Map<poolName, RotationState>>
    private final Map<String, Map<String, RotationState>> states = new ConcurrentHashMap<>();

    private BukkitTask checkTask;

    public PoolRotationManager(ShopkeepersStockControl plugin,
                               DataStore dataStore,
                               TradeDataManager tradeDataManager) {
        this.plugin = plugin;
        this.dataStore = dataStore;
        this.tradeDataManager = tradeDataManager;
    }

    /**
     * Loads persisted states, drops stale ones for removed pools, seeds missing ones for newly
     * configured pools, and advances any pool whose boundary already passed while offline.
     */
    public void initialize() {
        for (RotationState state : dataStore.loadAllRotationStates()) {
            states.computeIfAbsent(state.getShopId(), k -> new ConcurrentHashMap<>())
                    .put(state.getPoolName(), state);
        }

        reconcileWithConfig();
        startCheckTask();

        int poolCount = states.values().stream().mapToInt(Map::size).sum();
        plugin.getLogger().info("PoolRotationManager initialized (" + poolCount + " active pool state(s))");
    }

    /**
     * Cancels the scheduled task and forgets in-memory state (persisted state stays on disk).
     */
    public void shutdown() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
        states.clear();
        plugin.getLogger().info("PoolRotationManager shutdown complete");
    }

    /**
     * Reconciles in-memory state with the current configured pools. Called on startup and
     * on reload — never advances an already-current pool, but will seed a fresh state for a
     * newly configured pool (one that has no prior persistence).
     */
    public void reconcileWithConfig() {
        ZonedDateTime now = ZonedDateTime.now();
        Map<String, ShopConfig> shops = plugin.getConfigManager().getShops();

        // Forget states whose shop or pool no longer exists in config.
        Set<String> staleShops = new HashSet<>();
        for (Map.Entry<String, Map<String, RotationState>> shopEntry : states.entrySet()) {
            String shopId = shopEntry.getKey();
            ShopConfig shop = shops.get(shopId);
            if (shop == null) {
                staleShops.add(shopId);
                for (String poolName : shopEntry.getValue().keySet()) {
                    dataStore.deleteRotationState(shopId, poolName);
                }
                continue;
            }
            Set<String> stalePools = new HashSet<>();
            for (String poolName : shopEntry.getValue().keySet()) {
                if (!shop.getPools().containsKey(poolName)) {
                    stalePools.add(poolName);
                    dataStore.deleteRotationState(shopId, poolName);
                }
            }
            stalePools.forEach(shopEntry.getValue()::remove);
        }
        staleShops.forEach(states::remove);

        // Seed new pools, advance stale ones.
        for (ShopConfig shop : shops.values()) {
            for (PoolConfig pool : shop.getPools().values()) {
                RotationState existing = getState(shop.getShopId(), pool.getName());
                long expectedPeriod = RotationScheduler.currentPeriodIndex(pool, now);

                if (existing == null) {
                    // First time seeing this pool — seed without wiping counters.
                    // (A fresh pool has no prior usage to invalidate.)
                    seedInitial(shop.getShopId(), pool, expectedPeriod, now);
                } else if (existing.getPeriodIndex() < expectedPeriod) {
                    // Boundary passed while we were offline — catch up.
                    advance(shop.getShopId(), pool, expectedPeriod, now);
                } else {
                    // Stored period is current or ahead of the clock (typically from a prior
                    // force-advance). Repair advancesAt if it drifted past the next natural
                    // boundary, otherwise scheduled rotations would sleep until the clock
                    // catches up to the forced period.
                    long naturalAdvancesAt = RotationScheduler.advancesAt(pool, existing.getPeriodIndex(), now);
                    if (existing.getAdvancesAt() > naturalAdvancesAt) {
                        RotationState refreshed = new RotationState(
                                shop.getShopId(), pool.getName(),
                                existing.getPeriodIndex(), existing.getActiveItems(),
                                naturalAdvancesAt);
                        storeState(refreshed);
                        plugin.getLogger().info("Repaired stale advancesAt for pool '"
                                + pool.getName() + "' in shop " + shop.getShopId()
                                + " (was " + existing.getAdvancesAt() + ", now " + naturalAdvancesAt + ")");
                    }
                }
            }
        }
    }

    /**
     * Returns the in-memory rotation state for a pool, or null if none exists yet.
     */
    public RotationState getState(String shopId, String poolName) {
        Map<String, RotationState> poolStates = states.get(shopId);
        return poolStates == null ? null : poolStates.get(poolName);
    }

    /**
     * Returns a flat view of every currently-stored rotation state across all shops.
     * Intended for diagnostic commands; do not mutate.
     */
    public List<RotationState> allStates() {
        List<RotationState> all = new java.util.ArrayList<>();
        for (Map<String, RotationState> perShop : states.values()) {
            all.addAll(perShop.values());
        }
        return all;
    }

    /**
     * Forces an advance for every pool of the given shop, or one specific pool when poolName
     * is non-null. Counters are wiped the same as a natural boundary crossing.
     *
     * @param shopId   The owning shop ID
     * @param poolName Specific pool to advance, or null for all
     * @return Number of pools advanced
     */
    public int forceAdvance(String shopId, String poolName) {
        ShopConfig shop = plugin.getConfigManager().getShop(shopId);
        if (shop == null) return 0;
        ZonedDateTime now = ZonedDateTime.now();

        int advanced = 0;
        for (PoolConfig pool : shop.getPools().values()) {
            if (poolName != null && !pool.getName().equals(poolName)) continue;
            // Increment from the stored period so repeated forces keep stepping forward,
            // producing a fresh random seed each call. Falling back to the clock period
            // is only relevant if no state exists yet.
            RotationState existing = getState(shopId, pool.getName());
            long basePeriod = existing != null
                    ? existing.getPeriodIndex()
                    : RotationScheduler.currentPeriodIndex(pool, now);
            advance(shopId, pool, basePeriod + 1, now);
            advanced++;
        }
        return advanced;
    }

    private void startCheckTask() {
        checkTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::performCheck,
                CHECK_INTERVAL_TICKS,
                CHECK_INTERVAL_TICKS
        );
    }

    private void performCheck() {
        try {
            ZonedDateTime now = ZonedDateTime.now();
            long nowEpoch = now.toEpochSecond();
            Map<String, ShopConfig> shops = plugin.getConfigManager().getShops();

            for (ShopConfig shop : shops.values()) {
                for (PoolConfig pool : shop.getPools().values()) {
                    RotationState state = getState(shop.getShopId(), pool.getName());
                    if (state == null) continue; // seeded lazily by reconcile
                    if (nowEpoch >= state.getAdvancesAt()) {
                        long expectedPeriod = RotationScheduler.currentPeriodIndex(pool, now);
                        advance(shop.getShopId(), pool, expectedPeriod, now);
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error during pool rotation check", e);
        }
    }

    private void seedInitial(String shopId, PoolConfig pool, long periodIndex, ZonedDateTime now) {
        List<String> active = RotationScheduler.selectActive(shopId, pool, periodIndex);
        long advancesAt = RotationScheduler.advancesAt(pool, periodIndex, now);
        RotationState fresh = new RotationState(shopId, pool.getName(), periodIndex, active, advancesAt);
        storeState(fresh);

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("Seeded pool '" + pool.getName() + "' in shop " + shopId
                    + " at period " + periodIndex + " with active=" + active);
        }
    }

    private void advance(String shopId, PoolConfig pool, long newPeriodIndex, ZonedDateTime now) {
        List<String> newActive = RotationScheduler.selectActive(shopId, pool, newPeriodIndex);
        long advancesAt = RotationScheduler.advancesAt(pool, newPeriodIndex, now);

        // Per user preference: every rotation tick resets all currently-active items.
        // Use resetGlobalTrade — it wipes both per-player and global DB rows + cache entries,
        // and is a safe no-op for per-player shops (global side is empty there).
        for (String itemKey : newActive) {
            tradeDataManager.resetGlobalTrade(shopId, itemKey);
        }

        RotationState fresh = new RotationState(shopId, pool.getName(), newPeriodIndex, newActive, advancesAt);
        storeState(fresh);

        // Live-update anyone currently viewing the shop so their merchant UI reflects the new
        // rotation without requiring a close + reopen. Uses the rotation-specific push which
        // additionally returns any in-progress trade inputs to the viewer's inventory, so a
        // previously-selected (now stale) trade can't complete against the new item.
        plugin.getPacketManager().scheduleRotationPush(shopId);

        plugin.getLogger().info("Pool '" + pool.getName() + "' in shop " + shopId
                + " advanced to period " + newPeriodIndex + " (active: " + newActive + ")");
    }

    private void storeState(RotationState state) {
        states.computeIfAbsent(state.getShopId(), k -> new ConcurrentHashMap<>())
                .put(state.getPoolName(), state);
        dataStore.saveRotationState(state);
    }
}
