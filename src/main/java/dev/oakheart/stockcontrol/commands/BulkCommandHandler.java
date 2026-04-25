package dev.oakheart.stockcontrol.commands;

import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.items.ItemBuilder;
import com.nisovin.shopkeepers.api.ShopkeepersAPI;
import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import com.nisovin.shopkeepers.api.shopkeeper.admin.regular.RegularAdminShopkeeper;
import com.nisovin.shopkeepers.api.shopkeeper.offers.TradeOffer;
import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.PoolConfig;
import dev.oakheart.stockcontrol.data.ShopConfig;
import dev.oakheart.stockcontrol.data.SubpoolConfig;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Bulk-import command for Shopkeepers admin shops.
 * <p>
 * Reads a YAML file listing Nexo item IDs, resolves each via the Nexo API, then
 * uses the Shopkeepers public API ({@link RegularAdminShopkeeper#addOffers}) to
 * add a {@link TradeOffer} per item using the parent pool's default price.
 * <p>
 * The operator runs this once per (sub)pool during initial setup and again only
 * when adding new content. Output includes a paste-ready YAML snippet showing
 * each item's source slot — the operator copies that into {@code trades.yml}
 * under the matching pool/subpool's {@code items:} section.
 */
public final class BulkCommandHandler {

    private final ShopkeepersStockControl plugin;

    public BulkCommandHandler(ShopkeepersStockControl plugin) {
        this.plugin = plugin;
    }

    /**
     * Adds all items from the given YAML file as Shopkeepers offers in the named admin shop.
     *
     * @param sender       Command sender (for feedback messages)
     * @param shopName     Shopkeeper name to look up (matched case-insensitively)
     * @param poolName     Pool name from trades.yml (must already be configured with a price)
     * @param subpoolName  Optional subpool name; null if the pool is flat
     * @param itemsFile    Path to YAML file containing items list, relative to plugin data folder
     */
    public void handleBulkAdd(CommandSender sender, String shopName, String poolName,
                              @Nullable String subpoolName, String itemsFile) {
        if (Bukkit.getPluginManager().getPlugin("Nexo") == null) {
            sender.sendMessage("§cNexo plugin not loaded — bulk-add requires Nexo for item resolution.");
            return;
        }

        ShopConfig shopConfig = resolveShopConfig(shopName);
        if (shopConfig == null) {
            sender.sendMessage("§cShop not found: " + shopName);
            return;
        }

        PoolConfig pool = shopConfig.getPools().get(poolName);
        if (pool == null) {
            sender.sendMessage("§cPool '" + poolName + "' not configured in shop '" + shopConfig.getName() + "'.");
            return;
        }

        ItemStack price = resolvePrice(pool, subpoolName);
        if (price == null) {
            sender.sendMessage("§cNo price configured for pool '" + poolName
                    + (subpoolName != null ? "', subpool '" + subpoolName : "")
                    + "'. Add a 'price' field to the pool or subpool in trades.yml.");
            return;
        }

        File file = resolveItemsFile(itemsFile);
        if (file == null || !file.isFile()) {
            sender.sendMessage("§cItems file not found: " + itemsFile
                    + " (looked relative to " + plugin.getDataFolder().getAbsolutePath() + ")");
            return;
        }

        List<String> nexoIds = readNexoIds(file);
        if (nexoIds.isEmpty()) {
            sender.sendMessage("§cItems file is empty or unreadable: " + file.getName());
            return;
        }

        Shopkeeper sk = findShopkeeperByName(shopConfig.getShopId(), shopConfig.getName());
        if (!(sk instanceof RegularAdminShopkeeper admin)) {
            sender.sendMessage("§cShopkeeper UUID " + shopConfig.getShopId()
                    + " is not a regular admin shop, or no Shopkeeper with that UUID exists yet."
                    + " Create the admin shop in-game first via /shopkeeper.");
            return;
        }

        int existingOfferCount = admin.getOffers().size();
        List<TradeOffer> newOffers = new ArrayList<>(nexoIds.size());
        Map<String, Integer> resolvedKeyToSlot = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();

        int slot = existingOfferCount;
        for (String nexoId : nexoIds) {
            ItemBuilder builder = NexoItems.itemFromId(nexoId);
            if (builder == null) {
                failed.add(nexoId);
                continue;
            }
            ItemStack result;
            try {
                result = builder.build();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to build Nexo item " + nexoId, e);
                failed.add(nexoId);
                continue;
            }
            if (result == null || result.getType() == Material.AIR) {
                failed.add(nexoId);
                continue;
            }
            newOffers.add(TradeOffer.create(result, price.clone(), null));
            resolvedKeyToSlot.put(deriveItemKey(nexoId), slot++);
        }

        if (newOffers.isEmpty()) {
            sender.sendMessage("§cNothing to add — every Nexo ID failed to resolve."
                    + (failed.isEmpty() ? "" : " First failure: " + failed.get(0)));
            return;
        }

        admin.addOffers(newOffers);
        plugin.getLogger().info("Bulk-added " + newOffers.size() + " offers to shop '"
                + shopConfig.getName() + "' (existing offers: " + existingOfferCount + ")");

        sender.sendMessage("§aAdded " + newOffers.size() + " offers to shop '" + shopConfig.getName()
                + "'. Slot range: " + existingOfferCount + " — " + (slot - 1) + ".");
        if (!failed.isEmpty()) {
            sender.sendMessage("§eFailed to resolve " + failed.size() + " ID(s) — first few: "
                    + String.join(", ", failed.subList(0, Math.min(5, failed.size()))));
        }

        // Write entries directly into trades.yml via the OakheartLib wrapper, which preserves
        // comments and formatting on save. Commands run on the main thread, so this is safe.
        dev.oakheart.config.ConfigManager trades = plugin.getConfigManager().getTradesConfig();
        String shopUuid = shopConfig.getShopId();

        // Empty flow-style placeholders like `subpools: {}` aren't extensible by `set()` —
        // the wrapper sees them as scalar empty-maps rather than block sections. Strip the
        // placeholder so `set()` can recreate as a proper section.
        ensureExtensibleSection(trades, "shops." + shopUuid + ".pools." + poolName + ".subpools");
        ensureExtensibleSection(trades, "shops." + shopUuid + ".pools." + poolName + ".items");

        String basePath;
        if (subpoolName != null) {
            String subpoolPath = "shops." + shopUuid + ".pools." + poolName + ".subpools." + subpoolName;
            ensureExtensibleSection(trades, subpoolPath + ".items");
            // Set visible only when the subpool is brand-new (avoid clobbering the operator's
            // hand-tuned visible if they already declared the subpool).
            if (!trades.contains(subpoolPath + ".visible")) {
                trades.set(subpoolPath + ".visible", Math.min(resolvedKeyToSlot.size(), 6));
            }
            basePath = subpoolPath + ".items";
        } else {
            basePath = "shops." + shopUuid + ".pools." + poolName + ".items";
        }
        for (Map.Entry<String, Integer> e : resolvedKeyToSlot.entrySet()) {
            String itemPath = basePath + "." + e.getKey();
            // max-trades=-1 means "unlimited" — the rotation IS the limit for collectibles,
            // not a per-period purchase cap. Validation rejects 0 (a global cap of zero
            // would block every trade). Existing values are left alone so re-runs preserve
            // hand-tuned caps.
            trades.set(itemPath + ".source", e.getValue());
            if (!trades.contains(itemPath + ".max-trades")) {
                trades.set(itemPath + ".max-trades", -1);
            }
        }
        try {
            trades.save();
            sender.sendMessage("§atrades.yml updated. Run §f/ssc reload §ato pick up the new "
                    + (subpoolName != null ? "subpool '" + subpoolName + "'" : "items") + ".");
        } catch (java.io.IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save trades.yml after bulk-add", e);
            sender.sendMessage("§cBulk-add succeeded on Shopkeepers' side but failed to save trades.yml. "
                    + "Check the console — you may need to add the subpool entries by hand.");
        }
    }

    /**
     * Removes the trailing N Shopkeepers offers from the named admin shop.
     * Useful for re-running bulk-add cleanly when a pool's item list changes.
     *
     * @param sender    Command sender
     * @param shopName  Shopkeeper name (case-insensitive)
     * @param count     Number of offers to remove from the end of the offers list
     */
    public void handleBulkClear(CommandSender sender, String shopName, int count) {
        ShopConfig shopConfig = resolveShopConfig(shopName);
        if (shopConfig == null) {
            sender.sendMessage("§cShop not found: " + shopName);
            return;
        }

        Shopkeeper sk = findShopkeeperByName(shopConfig.getShopId(), shopConfig.getName());
        if (!(sk instanceof RegularAdminShopkeeper admin)) {
            sender.sendMessage("§cShop is not a regular admin shop.");
            return;
        }

        List<? extends TradeOffer> existing = admin.getOffers();
        int existingCount = existing.size();
        if (existingCount == 0) {
            sender.sendMessage("§7No offers to remove.");
            return;
        }
        int toKeep = Math.max(0, existingCount - count);
        List<TradeOffer> kept = new ArrayList<>(existing.subList(0, toKeep));
        admin.setOffers(kept);
        sender.sendMessage("§aRemoved " + (existingCount - toKeep) + " offer(s) from '"
                + shopConfig.getName() + "'. " + toKeep + " remain.");
        plugin.getLogger().info("Bulk-cleared " + (existingCount - toKeep) + " offers from shop '"
                + shopConfig.getName() + "'");
    }

    // ---------- Helpers ----------

    private @Nullable ShopConfig resolveShopConfig(String shopName) {
        Map<String, ShopConfig> shops = plugin.getConfigManager().getShops();
        // First: exact match by config UUID key.
        ShopConfig direct = shops.get(shopName);
        if (direct != null) return direct;
        // Otherwise: case-insensitive name match.
        for (ShopConfig shop : shops.values()) {
            if (shop.getName().equalsIgnoreCase(shopName)) return shop;
        }
        return null;
    }

    private @Nullable Shopkeeper findShopkeeperByName(String shopUuid, String shopName) {
        // Prefer UUID lookup (the trades.yml key is the Shopkeepers UUID).
        try {
            UUID uuid = UUID.fromString(shopUuid);
            Shopkeeper byUuid = ShopkeepersAPI.getShopkeeperRegistry().getShopkeeperByUniqueId(uuid);
            if (byUuid != null) return byUuid;
        } catch (IllegalArgumentException ignored) {
            // Not a UUID — fall through to name lookup.
        }
        for (Shopkeeper sk : ShopkeepersAPI.getShopkeeperRegistry().getAllShopkeepers()) {
            if (sk.getName() != null && sk.getName().equalsIgnoreCase(shopName)) return sk;
        }
        return null;
    }

    /**
     * If {@code path} exists in the config but is NOT a section (e.g. it was written
     * as a flow-style empty placeholder like {@code subpools: {}}), remove it so a
     * subsequent {@code set(path + ".child", ...)} can recreate it as a real block
     * section. No-op when the path is missing or already a section.
     */
    private void ensureExtensibleSection(dev.oakheart.config.ConfigManager trades, String path) {
        if (trades.contains(path) && !trades.isSection(path)) {
            trades.remove(path);
        }
    }

    private @Nullable ItemStack resolvePrice(PoolConfig pool, @Nullable String subpoolName) {
        if (subpoolName != null) {
            SubpoolConfig sub = pool.getSubpool(subpoolName);
            if (sub != null) return sub.effectivePrice(pool);
            // Subpool not yet declared in trades.yml — bootstrap case. Fall back to the pool's
            // default price so the operator can run bulk-add before pasting the subpool block.
        }
        return pool.getDefaultPrice();
    }

    private @Nullable File resolveItemsFile(String relativePath) {
        File data = plugin.getDataFolder();
        File asGiven = new File(data, relativePath);
        if (asGiven.isFile()) return asGiven;
        File withYml = new File(data, relativePath + ".yml");
        return withYml.isFile() ? withYml : null;
    }

    private List<String> readNexoIds(File file) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        // Support either a flat list at "items:" or a list at top-level.
        List<String> ids = cfg.getStringList("items");
        if (ids.isEmpty()) {
            // Fall back: if file is a top-level list (e.g. "- nm_plushie_foo"), Bukkit's YamlConfig
            // can't represent that as a config root, so we accept the convention "items:" only.
            return List.of();
        }
        // Trim and drop blanks.
        List<String> cleaned = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (id == null) continue;
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) cleaned.add(trimmed);
        }
        return cleaned;
    }

    /**
     * Derives a stable item key from a Nexo ID by lowercasing and stripping the
     * common {@code nm_plushie_} / {@code nm_figure_} / {@code nm_adorable_} /
     * {@code nm_pack_} prefixes used by Nog's Menagerie. Falls back to the raw
     * ID when no known prefix matches. Used in the paste-ready YAML snippet.
     */
    private String deriveItemKey(String nexoId) {
        String lower = nexoId.toLowerCase(Locale.ROOT);
        for (String prefix : new String[]{"nm_plushie_", "nm_figure_", "nm_adorable_", "nm_pack_"}) {
            if (lower.startsWith(prefix)) return lower.substring(prefix.length());
        }
        return lower;
    }
}
