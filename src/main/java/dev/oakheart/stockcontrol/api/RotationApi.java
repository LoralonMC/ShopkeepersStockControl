package dev.oakheart.stockcontrol.api;

import com.nisovin.shopkeepers.api.ShopkeepersAPI;
import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import com.nisovin.shopkeepers.api.shopkeeper.admin.regular.RegularAdminShopkeeper;
import com.nisovin.shopkeepers.api.shopkeeper.offers.TradeOffer;
import com.nisovin.shopkeepers.api.util.UnmodifiableItemStack;
import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.PoolConfig;
import dev.oakheart.stockcontrol.data.PoolItemConfig;
import dev.oakheart.stockcontrol.data.RotationState;
import dev.oakheart.stockcontrol.data.ShopConfig;
import dev.oakheart.stockcontrol.data.SubpoolConfig;
import dev.oakheart.stockcontrol.util.RotationScheduler;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Public read-only facade over rotation state for other plugins.
 *
 * <p>Rotation state on its own is just a list of config keys. Turning those into something
 * usable means walking each key's {@code source} slot into the backing Shopkeepers admin shop
 * and reading that offer's result item, which is fiddly enough that every consumer reimplementing
 * it is a liability. This class does it once.</p>
 *
 * <p>Obtain an instance via {@code ShopkeepersStockControl#getRotationApi()}. All methods are
 * safe to call from the main thread; the Shopkeepers lookups they perform are not thread-safe,
 * so do not call them asynchronously.</p>
 */
public class RotationApi {

    private final ShopkeepersStockControl plugin;

    public RotationApi(ShopkeepersStockControl plugin) {
        this.plugin = plugin;
    }

    /**
     * Resolves a shop by either its trades.yml shop ID (the Shopkeeper UUID) or its
     * configured display name.
     *
     * @param shopIdOrName Shop ID or display name
     * @return The shop config, or null if no shop matches
     */
    public @Nullable ShopConfig findShop(String shopIdOrName) {
        if (shopIdOrName == null || shopIdOrName.isEmpty()) return null;
        ShopConfig byId = plugin.getConfigManager().getShop(shopIdOrName);
        return byId != null ? byId : plugin.getConfigManager().getShopByName(shopIdOrName);
    }

    /**
     * Returns the currently active item keys for a pool.
     *
     * <p>The list is ordered to match the pool's {@code ui-slots}. It can be <em>shorter</em>
     * than the pool's {@code visible} count: when the spotlighted subpool holds fewer items
     * than there are slots, every item in it is shown and the remaining slots stay empty.
     * Callers rendering to fixed positions must tolerate a short list.</p>
     *
     * @param shopIdOrName Shop ID or display name
     * @param poolName     Pool name
     * @return Ordered active item keys; empty if the shop, pool, or state is missing
     */
    public List<String> getActiveKeys(String shopIdOrName, String poolName) {
        ShopConfig shop = findShop(shopIdOrName);
        if (shop == null || shop.getPool(poolName) == null) return List.of();
        RotationState state = plugin.getPoolRotationManager().getState(shop.getShopId(), poolName);
        return state == null ? List.of() : List.copyOf(state.getActiveItems());
    }

    /**
     * Returns the currently active items for a pool, fully resolved to real item stacks.
     *
     * <p>Entries whose {@code source} slot has no backing Shopkeepers offer are skipped rather
     * than returned as nulls, so the result may be shorter than {@link #getActiveKeys}. That
     * only happens when trades.yml points at a slot the shop doesn't have, which is a config
     * error worth a warning in the log.</p>
     *
     * @param shopIdOrName Shop ID or display name
     * @param poolName     Pool name
     * @return Ordered resolved items; empty if the shop, pool, or state is missing
     */
    public List<ActiveItem> getActiveItems(String shopIdOrName, String poolName) {
        ShopConfig shop = findShop(shopIdOrName);
        if (shop == null) return List.of();
        PoolConfig pool = shop.getPool(poolName);
        if (pool == null) return List.of();

        RotationState state = plugin.getPoolRotationManager().getState(shop.getShopId(), poolName);
        if (state == null || state.getActiveItems().isEmpty()) return List.of();

        Map<Integer, TradeOffer> offers = lookupOffers(shop.getShopId());
        if (offers.isEmpty()) return List.of();

        ItemStack fallbackPrice = resolveFallbackPrice(shop, pool, state);

        List<ActiveItem> resolved = new ArrayList<>(state.getActiveItems().size());
        for (String key : state.getActiveItems()) {
            PoolItemConfig itemConfig = pool.getItem(key);
            if (itemConfig == null) continue;

            TradeOffer offer = offers.get(itemConfig.getSourceSlot());
            if (offer == null) {
                plugin.getLogger().warning("Pool item '" + key + "' in pool '" + poolName
                        + "' points at source slot " + itemConfig.getSourceSlot()
                        + ", which has no Shopkeepers offer — skipping.");
                continue;
            }

            ItemStack result = offer.getResultItem().copy();
            ItemStack price = priceOf(offer, fallbackPrice);
            resolved.add(new ActiveItem(key, itemConfig.getSourceSlot(), result, nexoIdOf(result), price));
        }
        return resolved;
    }

    /**
     * @param shopIdOrName Shop ID or display name
     * @param poolName     Pool name
     * @return Epoch second at which the pool next rotates, or 0 when unknown
     */
    public long getAdvancesAt(String shopIdOrName, String poolName) {
        ShopConfig shop = findShop(shopIdOrName);
        if (shop == null) return 0L;
        RotationState state = plugin.getPoolRotationManager().getState(shop.getShopId(), poolName);
        return state == null ? 0L : state.getAdvancesAt();
    }

    /**
     * Returns the subpool spotlighted for the pool's current period, or null when the pool
     * is flat (no subpools) or has no state yet.
     *
     * @param shopIdOrName Shop ID or display name
     * @param poolName     Pool name
     * @return The spotlighted subpool, or null
     */
    public @Nullable SubpoolConfig getSpotlightSubpool(String shopIdOrName, String poolName) {
        ShopConfig shop = findShop(shopIdOrName);
        if (shop == null) return null;
        PoolConfig pool = shop.getPool(poolName);
        if (pool == null || !pool.hasSubpools()) return null;
        RotationState state = plugin.getPoolRotationManager().getState(shop.getShopId(), poolName);
        if (state == null) return null;
        return RotationScheduler.selectActiveSubpool(shop.getShopId(), pool, state.getPeriodIndex());
    }

    // ---- Internals ----

    /**
     * Reads the backing admin shop's trade offers indexed by their editor slot.
     */
    private Map<Integer, TradeOffer> lookupOffers(String shopId) {
        Map<Integer, TradeOffer> bySlot = new HashMap<>();
        try {
            Shopkeeper sk = ShopkeepersAPI.getShopkeeperRegistry()
                    .getShopkeeperByUniqueId(UUID.fromString(shopId));
            if (sk instanceof RegularAdminShopkeeper admin) {
                List<? extends TradeOffer> offers = admin.getOffers();
                for (int i = 0; i < offers.size(); i++) {
                    bySlot.put(i, offers.get(i));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to read Shopkeepers offers for shop " + shopId, e);
        }
        return bySlot;
    }

    /**
     * The offer's own first cost item is the real price. Falls back to the pool's configured
     * default price only when the offer somehow carries no cost.
     */
    private @Nullable ItemStack priceOf(TradeOffer offer, @Nullable ItemStack fallback) {
        UnmodifiableItemStack item1 = offer.getItem1();
        if (item1 != null) return item1.copy();
        return fallback == null ? null : fallback.clone();
    }

    /**
     * The spotlighted subpool may override the parent pool's default price.
     */
    private @Nullable ItemStack resolveFallbackPrice(ShopConfig shop, PoolConfig pool, RotationState state) {
        if (pool.hasSubpools()) {
            SubpoolConfig sub = RotationScheduler.selectActiveSubpool(
                    shop.getShopId(), pool, state.getPeriodIndex());
            if (sub != null) return sub.effectivePrice(pool);
        }
        return pool.getDefaultPrice();
    }

    /**
     * Nexo is an optional dependency, so the class is only touched once the plugin is
     * confirmed present.
     */
    private @Nullable String nexoIdOf(ItemStack stack) {
        if (Bukkit.getPluginManager().getPlugin("Nexo") == null) return null;
        try {
            return com.nexomc.nexo.api.NexoItems.idFromItem(stack);
        } catch (Exception e) {
            return null;
        }
    }
}
