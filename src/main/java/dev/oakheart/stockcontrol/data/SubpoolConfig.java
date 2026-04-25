package dev.oakheart.stockcontrol.data;

import org.bukkit.inventory.ItemStack;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for a subpool inside a {@link PoolConfig}.
 * <p>
 * When a pool declares subpools, each rotation period spotlights exactly one
 * subpool: only items from that subpool are visible to players for the period.
 * This produces themed rotations (e.g. "March is Dogs month") rather than random
 * mixing across all pool items.
 * <p>
 * A subpool may override the parent pool's {@code visible} count — useful when
 * a small themed pack should show all of its items at once during its spotlight
 * month, while a large pack rotates a subset.
 */
public class SubpoolConfig {
    private final String name;
    private final @Nullable Integer visibleOverride;
    private final Map<String, PoolItemConfig> items;
    private final @Nullable ItemStack priceOverride;

    /**
     * @param name             Subpool name (unique within the parent pool)
     * @param visibleOverride  Optional override for parent pool's visible count.
     *                         Null means "use parent pool's visible". Useful when
     *                         a small subpool wants to show all its items at once.
     * @param items            Subpool items keyed by itemKey (insertion order preserved)
     * @param priceOverride    Optional override for parent pool's default price.
     *                         Null means "use parent pool's defaultPrice".
     */
    public SubpoolConfig(String name,
                         @Nullable Integer visibleOverride,
                         Map<String, PoolItemConfig> items,
                         @Nullable ItemStack priceOverride) {
        this.name = name;
        this.visibleOverride = visibleOverride;
        this.items = new LinkedHashMap<>(items);
        this.priceOverride = priceOverride == null ? null : priceOverride.clone();
    }

    public String getName() {
        return name;
    }

    public @Nullable Integer getVisibleOverride() {
        return visibleOverride;
    }

    public Map<String, PoolItemConfig> getItems() {
        return Collections.unmodifiableMap(items);
    }

    public PoolItemConfig getItem(String itemKey) {
        return items.get(itemKey);
    }

    public @Nullable ItemStack getPriceOverride() {
        return priceOverride == null ? null : priceOverride.clone();
    }

    /**
     * Returns the effective visible count for this subpool, falling back to the
     * parent pool's count when no override is specified.
     */
    public int effectiveVisible(PoolConfig parent) {
        return visibleOverride != null ? visibleOverride : parent.getVisible();
    }

    /**
     * Returns the effective price for this subpool, falling back to the parent
     * pool's default price when no override is specified.
     */
    public @Nullable ItemStack effectivePrice(PoolConfig parent) {
        return priceOverride != null ? priceOverride.clone() : parent.getDefaultPrice();
    }

    @Override
    public String toString() {
        return "SubpoolConfig{" +
                "name='" + name + '\'' +
                ", items=" + items.size() +
                ", visibleOverride=" + visibleOverride +
                '}';
    }
}
