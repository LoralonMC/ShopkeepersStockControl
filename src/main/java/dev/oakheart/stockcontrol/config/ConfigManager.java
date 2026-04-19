package dev.oakheart.stockcontrol.config;

import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.CooldownMode;
import dev.oakheart.stockcontrol.data.ShopConfig;
import dev.oakheart.stockcontrol.data.StockMode;
import dev.oakheart.stockcontrol.data.TradeConfig;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Manages plugin configuration including main config and trades config.
 * Both files use OakheartLib's ConfigManager — formatting, comments, and quoting
 * are preserved across saves.
 */
public class ConfigManager {
    private final ShopkeepersStockControl plugin;
    private final File configFile;
    private final File tradesFile;
    private dev.oakheart.config.ConfigManager config;
    private dev.oakheart.config.ConfigManager tradesConfig;
    private volatile Map<String, ShopConfig> shops;

    // Cached values for hot-path access
    private String storageType;
    private int cooldownCheckInterval;
    private int cacheTTL;
    private int batchWriteInterval;
    private boolean debugMode;
    private int purgeInactiveDays;

    private static final Set<String> VALID_DAYS = Set.of(
            "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"
    );

    public ConfigManager(ShopkeepersStockControl plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        this.tradesFile = new File(plugin.getDataFolder(), "trades.yml");
        this.shops = Collections.emptyMap();
    }

    // Old snake_case -> new kebab-case key mappings for trades.yml shop/trade settings
    private static final Map<String, String> TRADES_KEY_MIGRATIONS = Map.of(
            "cooldown_mode", "cooldown-mode",
            "reset_time", "reset-time",
            "reset_day", "reset-day",
            "stock_mode", "stock-mode",
            "max_per_player", "max-per-player",
            "max_trades", "max-trades"
    );

    /**
     * Initial load of configuration. Called once during onEnable.
     */
    public void load() {
        // Save default config files if they don't exist
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }
        if (!tradesFile.exists()) {
            plugin.saveResource("trades.yml", false);
        }

        // Rewrite any legacy snake_case keys to kebab-case before the document is parsed,
        // so the canonical keys are what we read.
        migrateTradesConfig();

        // Load config.yml with OakheartLib
        try {
            config = dev.oakheart.config.ConfigManager.load(configFile.toPath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config.yml", e);
        }
        mergeDefaults();
        cacheValues();

        // Load trades.yml with OakheartLib
        try {
            tradesConfig = dev.oakheart.config.ConfigManager.load(tradesFile.toPath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load trades.yml", e);
        }

        // Load shop configurations
        loadShops();
    }

    /**
     * Merges default config values from the JAR resource into the user's config
     * without overwriting existing values. Only saves when new keys are found.
     */
    private void mergeDefaults() {
        try (var defaultStream = plugin.getResource("config.yml")) {
            if (defaultStream == null) return;

            var defaults = dev.oakheart.config.ConfigManager.fromStream(defaultStream);
            if (config.mergeDefaults(defaults)) {
                config.save();
                plugin.getLogger().info("Merged missing default config keys");
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to merge default config", e);
        }
    }

    /**
     * Rewrites legacy snake_case field names in trades.yml to kebab-case.
     * Uses in-place text substitution so comments, ordering, and indentation survive verbatim.
     * The trailing ": " in the pattern keeps shop or trade IDs that happen to match an old
     * field name (section headers end with just ':') from being rewritten.
     */
    private void migrateTradesConfig() {
        try {
            String text = Files.readString(tradesFile.toPath(), StandardCharsets.UTF_8);
            String original = text;

            for (Map.Entry<String, String> entry : TRADES_KEY_MIGRATIONS.entrySet()) {
                String pattern = "(?m)^(\\s+)" + Pattern.quote(entry.getKey()) + ": ";
                text = text.replaceAll(pattern, "$1" + entry.getValue() + ": ");
            }

            if (!text.equals(original)) {
                Files.writeString(tradesFile.toPath(), text, StandardCharsets.UTF_8);
                plugin.getLogger().info("trades.yml migrated to kebab-case keys successfully");
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to migrate trades.yml", e);
        }
    }

    /**
     * Caches frequently accessed configuration values.
     */
    private void cacheValues() {
        storageType = config.getString("storage-type", "sqlite");
        cooldownCheckInterval = config.getInt("cooldown-check-interval", 60);
        cacheTTL = config.getInt("cache-ttl", 10);
        batchWriteInterval = config.getInt("batch-write-interval", 30);
        debugMode = config.getBoolean("debug", false);
        purgeInactiveDays = config.getInt("purge-inactive-days", 0);
    }

    /**
     * Reloads all configuration files.
     *
     * @return true if reload was successful, false otherwise
     */
    public boolean reload() {
        try {
            config.reload();

            dev.oakheart.config.ConfigManager newTradesConfig;
            try {
                newTradesConfig = dev.oakheart.config.ConfigManager.load(tradesFile.toPath());
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to reload trades.yml", e);
                return false;
            }

            // Load new shop configurations
            Map<String, ShopConfig> newShops = loadShopsFromConfig(newTradesConfig);

            // Validate new configurations
            Set<String> errors = validateShops(newShops);
            if (!errors.isEmpty()) {
                plugin.getLogger().severe("=== Configuration Errors ===");
                errors.forEach(error -> plugin.getLogger().severe("  - " + error));
                plugin.getLogger().severe("Fix these errors in trades.yml!");
                return false;
            }

            // Clean up orphaned shop data (shops that were in old config but not in new)
            Set<String> orphanedIds = new HashSet<>(this.shops.keySet());
            orphanedIds.removeAll(newShops.keySet());
            if (!orphanedIds.isEmpty()) {
                for (String orphanId : orphanedIds) {
                    plugin.getTradeDataManager().evictShop(orphanId);
                    plugin.getDataStore().deleteShopData(orphanId);
                }
                plugin.getLogger().info("Cleaned up " + orphanedIds.size() + " orphaned shop(s): " + orphanedIds);
            }

            // Atomically swap configurations
            this.tradesConfig = newTradesConfig;
            this.shops = Collections.unmodifiableMap(newShops);
            cacheValues();

            plugin.getLogger().info("Configuration reloaded successfully");
            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to reload config", e);
            return false;
        }
    }

    /**
     * Loads shop configurations from trades.yml.
     */
    private void loadShops() {
        shops = Collections.unmodifiableMap(loadShopsFromConfig(tradesConfig));

        // Validate main config
        Set<String> configWarnings = validateMainConfig();
        if (!configWarnings.isEmpty()) {
            plugin.getLogger().warning("=== Configuration Warnings (config.yml) ===");
            configWarnings.forEach(warning -> plugin.getLogger().warning("  - " + warning));
        }

        // Validate shop configurations
        Set<String> errors = validateShops(shops);
        if (!errors.isEmpty()) {
            plugin.getLogger().severe("=== Configuration Errors (trades.yml) ===");
            errors.forEach(error -> plugin.getLogger().severe("  - " + error));
            plugin.getLogger().severe("Fix these errors in trades.yml!");
        }
    }

    /**
     * Loads shop configurations from the trades config.
     *
     * @param tradesNode The trades ConfigManager
     * @return Map of shop ID to ShopConfig
     */
    private Map<String, ShopConfig> loadShopsFromConfig(dev.oakheart.config.ConfigManager tradesNode) {
        Map<String, ShopConfig> loadedShops = new HashMap<>();

        if (!tradesNode.isSection("shops")) {
            plugin.getLogger().warning("No shops configured in trades.yml");
            return loadedShops;
        }

        for (String shopId : tradesNode.getKeys("shops", false)) {
            String shopPath = "shops." + shopId;
            if (!tradesNode.isSection(shopPath)) continue;

            String name = tradesNode.getString(shopPath + ".name", shopId);
            boolean enabled = tradesNode.getBoolean(shopPath + ".enabled", true);

            // Per-shop cooldown settings
            CooldownMode cooldownMode = CooldownMode.fromString(tradesNode.getString(shopPath + ".cooldown-mode", "rolling"));
            String resetTime = tradesNode.getString(shopPath + ".reset-time", "00:00");
            String rawResetDay = tradesNode.getString(shopPath + ".reset-day", "monday");
            String resetDay = rawResetDay != null ? rawResetDay.toUpperCase() : "MONDAY";

            // Stock mode settings
            StockMode stockMode = StockMode.fromString(tradesNode.getString(shopPath + ".stock-mode", "per_player"));
            int shopMaxPerPlayer = tradesNode.getInt(shopPath + ".max-per-player", 0);

            Map<String, TradeConfig> trades = new HashMap<>();
            String tradesPath = shopPath + ".trades";
            if (tradesNode.isSection(tradesPath)) {
                for (String tradeKey : tradesNode.getKeys(tradesPath, false)) {
                    String tradePath = tradesPath + "." + tradeKey;
                    if (!tradesNode.isSection(tradePath)) continue;

                    int slot = tradesNode.getInt(tradePath + ".slot", -1);
                    int maxTrades = tradesNode.getInt(tradePath + ".max-trades", 1);
                    int cooldown = tradesNode.getInt(tradePath + ".cooldown", 86400);

                    // Per-trade cooldown settings with shop-level fallback
                    CooldownMode tradeCooldownMode = tradesNode.contains(tradePath + ".cooldown-mode")
                            ? CooldownMode.fromString(tradesNode.getString(tradePath + ".cooldown-mode"))
                            : cooldownMode;
                    String tradeResetTime = tradesNode.getString(tradePath + ".reset-time", resetTime);
                    String rawTradeResetDay = tradesNode.getString(tradePath + ".reset-day");
                    String tradeResetDay = rawTradeResetDay != null ? rawTradeResetDay.toUpperCase() : resetDay;

                    int tradeMaxPerPlayer = tradesNode.contains(tradePath + ".max-per-player")
                            ? tradesNode.getInt(tradePath + ".max-per-player", 0)
                            : shopMaxPerPlayer;

                    if (isDebugMode()) {
                        plugin.getLogger().info("Loading trade '" + tradeKey + "' for shop " + shopId +
                                ": slot=" + slot + ", max-trades=" + maxTrades + ", cooldown=" + cooldown +
                                ", mode=" + tradeCooldownMode);
                    }

                    TradeConfig tradeConfig = new TradeConfig(tradeKey, slot, maxTrades, cooldown,
                            tradeCooldownMode, tradeResetTime, tradeResetDay, tradeMaxPerPlayer);
                    trades.put(tradeKey, tradeConfig);
                }
            }

            ShopConfig shopConfig = new ShopConfig(shopId, name, enabled,
                    cooldownMode, resetTime, resetDay, stockMode, shopMaxPerPlayer, trades);
            loadedShops.put(shopId, shopConfig);
        }

        plugin.getLogger().info("Loaded " + loadedShops.size() + " shop configurations");
        return loadedShops;
    }

    /**
     * Validates shop configurations.
     *
     * @param shopsToValidate The shops to validate
     * @return Set of error messages (empty if valid)
     */
    private Set<String> validateShops(Map<String, ShopConfig> shopsToValidate) {
        Set<String> errors = new LinkedHashSet<>();

        for (ShopConfig shop : shopsToValidate.values()) {
            Set<Integer> usedSlots = new HashSet<>();
            Set<String> tradeKeys = new HashSet<>();

            for (TradeConfig trade : shop.getTrades().values()) {
                // Check for duplicate trade keys
                if (!tradeKeys.add(trade.getTradeKey())) {
                    errors.add("Shop '" + shop.getShopId() + "': Duplicate trade key '" + trade.getTradeKey() + "'");
                }

                // Check for duplicate slots
                if (!usedSlots.add(trade.getSlot())) {
                    errors.add("Shop '" + shop.getShopId() + "': Duplicate slot " + trade.getSlot());
                }

                // Validate positive values
                if (trade.getMaxTrades() <= 0) {
                    errors.add("Trade '" + trade.getTradeKey() + "': max-trades must be > 0");
                }
                if (trade.getCooldownMode() == CooldownMode.ROLLING && trade.getCooldownSeconds() <= 0) {
                    errors.add("Trade '" + trade.getTradeKey() + "': cooldown must be > 0 for rolling mode");
                }
                if (trade.getSlot() < 0) {
                    errors.add("Trade '" + trade.getTradeKey() + "': slot must be >= 0");
                }
                if (trade.getMaxPerPlayer() < 0) {
                    errors.add("Trade '" + trade.getTradeKey() + "' in shop '" + shop.getShopId()
                            + "': max-per-player must be >= 0");
                }
                if (trade.getMaxPerPlayer() > 0 && trade.getMaxPerPlayer() > trade.getMaxTrades()
                        && shop.isShared()) {
                    errors.add("Trade '" + trade.getTradeKey() + "' in shop '" + shop.getShopId()
                            + "': max-per-player (" + trade.getMaxPerPlayer()
                            + ") exceeds max-trades (" + trade.getMaxTrades() + ")");
                }

                // Validate per-trade cooldown settings
                CooldownMode mode = trade.getCooldownMode();
                if (mode == CooldownMode.DAILY || mode == CooldownMode.WEEKLY) {
                    String tradeResetTime = trade.getResetTime();
                    if (!tradeResetTime.matches("^([0-1][0-9]|2[0-3]):[0-5][0-9]$")) {
                        errors.add("Trade '" + trade.getTradeKey() + "' in shop '" + shop.getShopId()
                                + "': reset-time must be in HH:mm format (00:00 to 23:59). Current: '" + tradeResetTime + "'");
                    }
                }
                if (mode == CooldownMode.WEEKLY) {
                    if (!VALID_DAYS.contains(trade.getResetDay())) {
                        errors.add("Trade '" + trade.getTradeKey() + "' in shop '" + shop.getShopId()
                                + "': reset-day must be a valid day of the week. Current: '" + trade.getResetDay() + "'");
                    }
                }
            }
        }

        return errors;
    }

    /**
     * Validates main config.yml settings.
     *
     * @return Set of warning messages
     */
    private Set<String> validateMainConfig() {
        Set<String> warnings = new LinkedHashSet<>();

        // Validate numeric ranges
        if (cooldownCheckInterval < 10 || cooldownCheckInterval > 3600) {
            warnings.add("cooldown-check-interval should be between 10-3600 seconds (currently: " + cooldownCheckInterval + ")");
        }

        if (cacheTTL < 5 || cacheTTL > 300) {
            warnings.add("cache-ttl should be between 5-300 seconds (currently: " + cacheTTL + ")");
        }

        if (batchWriteInterval < 5 || batchWriteInterval > 300) {
            warnings.add("batch-write-interval should be between 5-300 seconds (currently: " + batchWriteInterval + ")");
        }

        if (purgeInactiveDays < 0) {
            warnings.add("purge-inactive-days must be >= 0 (0 to disable). Currently: " + purgeInactiveDays);
        }

        // Validate storage type
        if (!storageType.equalsIgnoreCase("sqlite")) {
            warnings.add("storage-type '" + storageType + "' is not supported. Only 'sqlite' is currently supported.");
        }

        return warnings;
    }

    // Getters for cached configuration values
    public String getStorageType() {
        return storageType;
    }

    public int getCooldownCheckInterval() {
        return cooldownCheckInterval;
    }

    public int getCacheTTL() {
        return cacheTTL;
    }

    public int getBatchWriteInterval() {
        return batchWriteInterval;
    }

    /**
     * Toggles debug mode for the current session.
     * Does not save to disk to avoid reformatting the config file.
     * The value resets to whatever is in config.yml on next server restart or reload.
     *
     * @param enabled Whether debug mode should be enabled
     */
    public void setDebugMode(boolean enabled) {
        debugMode = enabled;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    public Map<String, ShopConfig> getShops() {
        return shops;
    }

    public ShopConfig getShop(String shopId) {
        return shops.get(shopId);
    }

    /**
     * Finds a shop by its display name (case-insensitive).
     * Returns the first match, or null if no shop has that name.
     *
     * @param name The display name to search for
     * @return The matching ShopConfig, or null if not found
     */
    public ShopConfig getShopByName(String name) {
        for (ShopConfig shop : shops.values()) {
            if (shop.getName().equalsIgnoreCase(name)) {
                return shop;
            }
        }
        // Fallback: try with underscores as spaces (for command tab-complete friendly names)
        if (name.indexOf('_') >= 0) {
            String spaced = name.replace('_', ' ');
            for (ShopConfig shop : shops.values()) {
                if (shop.getName().equalsIgnoreCase(spaced)) {
                    return shop;
                }
            }
        }
        return null;
    }

    public int getPurgeInactiveDays() {
        return purgeInactiveDays;
    }

    public boolean hasShop(String shopId) {
        return shops.containsKey(shopId);
    }
}
