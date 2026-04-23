package dev.oakheart.stockcontrol.data;

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

    /**
     * @param name            Pool name (unique within the shop)
     * @param uiSlots         UI positions this pool occupies (player-facing)
     * @param visible         How many items are active per period (must equal uiSlots.size())
     * @param mode            Selection mode (RANDOM or SEQUENTIAL)
     * @param schedule        Advance schedule (DAILY, WEEKLY, or INTERVAL)
     * @param resetTime       Reset time in HH:mm format (for DAILY/WEEKLY, and interval anchor)
     * @param resetDay        Day of week for WEEKLY schedule (e.g. "MONDAY")
     * @param intervalSeconds Period length for INTERVAL schedule (ignored otherwise)
     * @param items           Pool items keyed by itemKey (insertion order preserved)
     */
    public PoolConfig(String name, List<Integer> uiSlots, int visible,
                      RotationMode mode, RotationSchedule schedule,
                      String resetTime, String resetDay, long intervalSeconds,
                      Map<String, PoolItemConfig> items) {
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

    public PoolItemConfig getItem(String itemKey) {
        return items.get(itemKey);
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
                '}';
    }
}
