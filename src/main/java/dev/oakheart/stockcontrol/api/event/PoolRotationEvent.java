package dev.oakheart.stockcontrol.api.event;

import dev.oakheart.stockcontrol.api.RotationCause;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Fired after a rotation pool's active-item list changes.
 *
 * <p>Always delivered on the main server thread, even though the rotation check itself runs
 * asynchronously — listeners are free to touch the world directly.</p>
 *
 * <p>This is a notification, not a veto: the new rotation is already persisted and already
 * pushed to anyone viewing the shop by the time listeners run. The event exists so consumers
 * that mirror rotation somewhere outside the merchant UI (world displays, signage, web caches)
 * can update without polling.</p>
 */
public class PoolRotationEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String shopId;
    private final String shopName;
    private final String poolName;
    private final long periodIndex;
    private final long advancesAt;
    private final List<String> activeItems;
    private final RotationCause cause;

    /**
     * @param shopId      Shopkeeper UUID string identifying the shop in trades.yml
     * @param shopName    The shop's configured display name
     * @param poolName    The pool that changed
     * @param periodIndex The pool's new period index
     * @param advancesAt  Epoch second at which this pool next rotates
     * @param activeItems The new active item keys, ordered to match the pool's ui-slots.
     *                    May be shorter than the pool's {@code visible} count when the
     *                    spotlighted subpool holds fewer items than there are slots.
     * @param cause       What triggered the change
     */
    public PoolRotationEvent(String shopId, String shopName, String poolName,
                             long periodIndex, long advancesAt,
                             List<String> activeItems, RotationCause cause) {
        this.shopId = shopId;
        this.shopName = shopName;
        this.poolName = poolName;
        this.periodIndex = periodIndex;
        this.advancesAt = advancesAt;
        this.activeItems = List.copyOf(activeItems);
        this.cause = cause;
    }

    public String getShopId() {
        return shopId;
    }

    public String getShopName() {
        return shopName;
    }

    public String getPoolName() {
        return poolName;
    }

    public long getPeriodIndex() {
        return periodIndex;
    }

    /**
     * @return Epoch second of this pool's next rotation boundary
     */
    public long getAdvancesAt() {
        return advancesAt;
    }

    /**
     * @return The new active item keys, ordered to match the pool's ui-slots. Never null;
     *         may be empty, and may be shorter than the pool's {@code visible} count.
     */
    public List<String> getActiveItems() {
        return activeItems;
    }

    public RotationCause getCause() {
        return cause;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
