package dev.oakheart.stockcontrol.api;

import org.bukkit.inventory.ItemStack;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One currently-spotlighted item of a rotation pool, resolved all the way from its
 * config key to the real {@link ItemStack} players see in the merchant UI.
 *
 * <p>Resolution walks the pool item's {@code source} slot into the backing Shopkeepers
 * admin shop and takes that trade offer's result item, so the stack carries the item's
 * true name, lore, and custom model regardless of how the item key was named.</p>
 */
public final class ActiveItem {

    private final String itemKey;
    private final int sourceSlot;
    private final ItemStack item;
    private final @Nullable String nexoId;
    private final @Nullable ItemStack price;

    /**
     * @param itemKey    The pool item key from trades.yml
     * @param sourceSlot The Shopkeepers editor slot the item was resolved from
     * @param item       The trade's result item
     * @param nexoId     The Nexo item ID backing the stack, or null if it isn't a Nexo item
     * @param price      The trade's cost, or null when it could not be resolved
     */
    public ActiveItem(String itemKey, int sourceSlot, ItemStack item,
                      @Nullable String nexoId, @Nullable ItemStack price) {
        this.itemKey = itemKey;
        this.sourceSlot = sourceSlot;
        this.item = item.clone();
        this.nexoId = nexoId;
        this.price = price == null ? null : price.clone();
    }

    public String getItemKey() {
        return itemKey;
    }

    public int getSourceSlot() {
        return sourceSlot;
    }

    /**
     * @return A copy of the result item. Mutating it does not affect the shop.
     */
    public ItemStack getItem() {
        return item.clone();
    }

    /**
     * @return The Nexo item ID for this stack, or null when the item isn't Nexo-backed
     *         (a vanilla material, or Nexo simply isn't installed).
     */
    public @Nullable String getNexoId() {
        return nexoId;
    }

    /**
     * @return A copy of the trade's cost, or null when the pool declared no price.
     */
    public @Nullable ItemStack getPrice() {
        return price == null ? null : price.clone();
    }

    @Override
    public String toString() {
        return "ActiveItem{" +
                "itemKey='" + itemKey + '\'' +
                ", sourceSlot=" + sourceSlot +
                ", material=" + item.getType() +
                ", nexoId=" + nexoId +
                '}';
    }
}
