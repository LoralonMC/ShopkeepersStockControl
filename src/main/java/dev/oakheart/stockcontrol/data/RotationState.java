package dev.oakheart.stockcontrol.data;

import java.util.Collections;
import java.util.List;

/**
 * Persisted snapshot of one pool's current rotation.
 * The snapshot is refreshed when the pool crosses a scheduled boundary, not on reload —
 * so players mid-session see a stable set until the next rotation fires.
 */
public final class RotationState {

    private final String shopId;
    private final String poolName;
    private final long periodIndex;
    private final List<String> activeItems;
    private final long advancesAt;

    public RotationState(String shopId, String poolName, long periodIndex,
                         List<String> activeItems, long advancesAt) {
        this.shopId = shopId;
        this.poolName = poolName;
        this.periodIndex = periodIndex;
        this.activeItems = List.copyOf(activeItems);
        this.advancesAt = advancesAt;
    }

    public String getShopId() {
        return shopId;
    }

    public String getPoolName() {
        return poolName;
    }

    public long getPeriodIndex() {
        return periodIndex;
    }

    public List<String> getActiveItems() {
        return Collections.unmodifiableList(activeItems);
    }

    public long getAdvancesAt() {
        return advancesAt;
    }
}
