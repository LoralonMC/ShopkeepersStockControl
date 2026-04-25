package dev.oakheart.stockcontrol.data;

import org.bukkit.inventory.ItemStack;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for a single rotation pool within a shop.
 * A pool owns a set of UI slots and cycles its items through them on a schedule.
 */
public class PoolConfig {
    private final String name;
    private final List<Integer> uiSlots;
    private final int visible;
    private final RotationMode mode;
    private final RotationSchedule schedule;
    private final String resetTime;
    private final String resetDay;
    private final long intervalSeconds;
    private final Map<String, PoolItemConfig> items;
    private final @Nullable ItemStack defaultPrice;
    private final Map<String, SubpoolConfig> subpools;

    /**
     * @param name            Pool name (unique within the shop)
     * @param uiSlots         UI positions this pool occupies (player-facing)
     * @param visible         How many items are active per period (must equal uiSlots.size())
     * @param mode            Selection mode (RANDOM or SEQUENTIAL)
     * @param schedule        Advance schedule (DAILY, WEEKLY, MONTHLY, or INTERVAL)
     * @param resetTime       Reset time in HH:mm format (for DAILY/WEEKLY/MONTHLY, and interval anchor)
     * @param resetDay        Day of week for WEEKLY schedule (e.g. "MONDAY")
     * @param intervalSeconds Period length for INTERVAL schedule (ignored otherwise)
     * @param items           Pool items keyed by itemKey (insertion order preserved). Used for flat pools.
     * @param defaultPrice    Default emerald (or other) cost for items in this pool. Used by bulk-add command. Nullable.
     * @param subpools        Optional subpools — when non-empty, one subpool is spotlighted per rotation period (overrides flat items).
     */
    public PoolConfig(String name, List<Integer> uiSlots, int visible,
                      RotationMode mode, RotationSchedule schedule,
                      String resetTime, String resetDay, long intervalSeconds,
                      Map<String, PoolItemConfig> items,
                      @Nullable ItemStack defaultPrice,
                      Map<String, SubpoolConfig> subpools) {
        this.name = name;
        this.uiSlots = List.copyOf(uiSlots);
        this.visible = visible;
        this.mode = mode;
        this.schedule = schedule;
        this.resetTime = resetTime;
        this.resetDay = resetDay;
        this.intervalSeconds = intervalSeconds;
        // LinkedHashMap preserves declaration order — matters for SEQUENTIAL mode.
        this.items = new LinkedHashMap<>(items);
        this.defaultPrice = defaultPrice == null ? null : defaultPrice.clone();
        this.subpools = subpools == null ? Collections.emptyMap() : new LinkedHashMap<>(subpools);
    }

    public String getName() {
        return name;
    }

    public List<Integer> getUiSlots() {
        return uiSlots;
    }

    public int getVisible() {
        return visible;
    }

    public RotationMode getMode() {
        return mode;
    }

    public RotationSchedule getSchedule() {
        return schedule;
    }

    public String getResetTime() {
        return resetTime;
    }

    public String getResetDay() {
        return resetDay;
    }

    public long getIntervalSeconds() {
        return intervalSeconds;
    }

    public Map<String, PoolItemConfig> getItems() {
        return Collections.unmodifiableMap(items);
    }

    /**
     * Returns the {@link PoolItemConfig} for this key, looking in the flat items list
     * first and then in every subpool. Mirrors {@code selectActive}'s behavior, which
     * picks from across the whole effective item set — so any caller that gets an item
     * key from rotation state can resolve it back here regardless of where it lives.
     */
    public PoolItemConfig getItem(String itemKey) {
        PoolItemConfig flat = items.get(itemKey);
        if (flat != null) return flat;
        for (SubpoolConfig sub : subpools.values()) {
            PoolItemConfig fromSub = sub.getItems().get(itemKey);
            if (fromSub != null) return fromSub;
        }
        return null;
    }

    /**
     * @return The default emerald (or other) cost for items in this pool.
     *         Returned as a clone for safety. {@code null} if not configured.
     */
    public @Nullable ItemStack getDefaultPrice() {
        return defaultPrice == null ? null : defaultPrice.clone();
    }

    /**
     * @return Subpools defined for this pool (insertion order preserved).
     *         Empty map when the pool is flat.
     */
    public Map<String, SubpoolConfig> getSubpools() {
        return Collections.unmodifiableMap(subpools);
    }

    public SubpoolConfig getSubpool(String name) {
        return subpools.get(name);
    }

    /**
     * @return {@code true} when this pool has at least one subpool configured —
     *         in that case rotation spotlights one subpool at a time instead
     *         of randomly mixing items from the flat list.
     */
    public boolean hasSubpools() {
        return !subpools.isEmpty();
    }

    @Override
    public String toString() {
        return "PoolConfig{" +
                "name='" + name + '\'' +
                ", uiSlots=" + uiSlots +
                ", visible=" + visible +
                ", mode=" + mode +
                ", schedule=" + schedule +
                ", items=" + items.size() +
                ", subpools=" + subpools.size() +
                ", price=" + (defaultPrice == null ? "none" : defaultPrice.getType()) +
                '}';
    }
}
