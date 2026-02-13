package dev.oakheart.stockcontrol.config;

import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.CooldownMode;
import dev.oakheart.stockcontrol.data.ShopConfig;
import dev.oakheart.stockcontrol.data.StockMode;
import dev.oakheart.stockcontrol.data.TradeConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.logging.Level;

import static dev.oakheart.stockcontrol.message.MessageManager.*;

/**
 * Manages plugin configuration including main config and trades config.
 * Uses Bukkit's FileConfiguration (YamlConfiguration) for all YAML operations.
 */
public class ConfigManager {
    private final ShopkeepersStockControl plugin;
    private final File configFile;
    private final File tradesFile;
    private FileConfiguration config;
    private FileConfiguration tradesConfig;
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

        // Load config.yml
        config = YamlConfiguration.loadConfiguration(configFile);
        mergeDefaults();
        cacheValues();

        // Load trades.yml
        tradesConfig = YamlConfiguration.loadConfiguration(tradesFile);

        // Load shop configurations
        loadShops();
    }

    /**
     * Merges default config values from the JAR resource into the user's config
     * without overwriting existing values. Only saves when new keys are found.
     */
    private void mergeDefaults() {
        try (InputStream defaultStream = plugin.getResource("config.yml")) {
            if (defaultStream == null) return;

            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream));
            config.setDefaults(defaults);

            if (hasNewKeys(defaults)) {
                config.options().copyDefaults(true);
                config.save(configFile);
                plugin.getLogger().info("Merged missing default config keys");
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to merge default config", e);
        }
    }

    /**
     * Checks if the defaults contain any keys not present in the user's config.
     */
    private boolean hasNewKeys(YamlConfiguration defaults) {
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (!config.contains(key, true)) return true;
        }
        return false;
    }

    /**
     * Caches frequently accessed configuration values.
     */
    private void cacheValues() {
        storageType = config.getString("storage_type", "sqlite");
        cooldownCheckInterval = config.getInt("cooldown_check_interval", 60);
        cacheTTL = config.getInt("cache_ttl", 10);
        batchWriteInterval = config.getInt("batch_write_interval", 30);
        debugMode = config.getBoolean("debug", false);
        purgeInactiveDays = config.getInt("purge_inactive_days", 0);
    }

    /**
     * Reloads all configuration files.
     *
     * @return true if reload was successful, false otherwise
     */
    public boolean reload() {
        try {
            FileConfiguration newConfig = YamlConfiguration.loadConfiguration(configFile);
            FileConfiguration newTradesConfig = YamlConfiguration.loadConfiguration(tradesFile);

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
            this.config = newConfig;
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
     * @param tradesNode The trades FileConfiguration
     * @return Map of shop ID to ShopConfig
     */
    private Map<String, ShopConfig> loadShopsFromConfig(FileConfiguration tradesNode) {
        Map<String, ShopConfig> loadedShops = new HashMap<>();

        ConfigurationSection shopsSection = tradesNode.getConfigurationSection("shops");
        if (shopsSection == null) {
            plugin.getLogger().warning("No shops configured in trades.yml");
            return loadedShops;
        }

        for (String shopId : shopsSection.getKeys(false)) {
            ConfigurationSection shopSection = shopsSection.getConfigurationSection(shopId);
            if (shopSection == null) continue;

            String name = shopSection.getString("name", shopId);
            boolean enabled = shopSection.getBoolean("enabled", true);

            // Per-shop cooldown settings
            CooldownMode cooldownMode = CooldownMode.fromString(shopSection.getString("cooldown_mode", "rolling"));
            String resetTime = shopSection.getString("reset_time", "00:00");
            String resetDay = shopSection.getString("reset_day", "monday").toUpperCase();

            // Stock mode settings
            StockMode stockMode = StockMode.fromString(shopSection.getString("stock_mode", "per_player"));
            int shopMaxPerPlayer = shopSection.getInt("max_per_player", 0);

            Map<String, TradeConfig> trades = new HashMap<>();
            ConfigurationSection tradesSection = shopSection.getConfigurationSection("trades");
            if (tradesSection != null) {
                for (String tradeKey : tradesSection.getKeys(false)) {
                    ConfigurationSection tradeSection = tradesSection.getConfigurationSection(tradeKey);
                    if (tradeSection == null) continue;

                    int slot = tradeSection.getInt("slot", -1);
                    int maxTrades = tradeSection.getInt("max_trades", 1);
                    int cooldown = tradeSection.getInt("cooldown", 86400);

                    // Per-trade cooldown settings with shop-level fallback
                    CooldownMode tradeCooldownMode = tradeSection.contains("cooldown_mode")
                            ? CooldownMode.fromString(tradeSection.getString("cooldown_mode"))
                            : cooldownMode;
                    String tradeResetTime = tradeSection.getString("reset_time", resetTime);
                    String tradeResetDay = tradeSection.contains("reset_day")
                            ? tradeSection.getString("reset_day").toUpperCase()
                            : resetDay;

                    int tradeMaxPerPlayer = tradeSection.contains("max_per_player")
                            ? tradeSection.getInt("max_per_player", 0)
                            : shopMaxPerPlayer;

                    if (isDebugMode()) {
                        plugin.getLogger().info("Loading trade '" + tradeKey + "' for shop " + shopId +
                                ": slot=" + slot + ", max_trades=" + maxTrades + ", cooldown=" + cooldown +
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
        Set<String> errors = new HashSet<>();

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
                    errors.add("Trade '" + trade.getTradeKey() + "': max_trades must be > 0");
                }
                if (trade.getCooldownMode() == CooldownMode.ROLLING && trade.getCooldownSeconds() <= 0) {
                    errors.add("Trade '" + trade.getTradeKey() + "': cooldown must be > 0 for rolling mode");
                }
                if (trade.getSlot() < 0) {
                    errors.add("Trade '" + trade.getTradeKey() + "': slot must be >= 0");
                }
                if (trade.getMaxPerPlayer() < 0) {
                    errors.add("Trade '" + trade.getTradeKey() + "' in shop '" + shop.getShopId()
                            + "': max_per_player must be >= 0");
                }
                if (trade.getMaxPerPlayer() > 0 && trade.getMaxPerPlayer() > trade.getMaxTrades()
                        && shop.isShared()) {
                    errors.add("Trade '" + trade.getTradeKey() + "' in shop '" + shop.getShopId()
                            + "': max_per_player (" + trade.getMaxPerPlayer()
                            + ") exceeds max_trades (" + trade.getMaxTrades() + ")");
                }

                // Validate per-trade cooldown settings
                CooldownMode mode = trade.getCooldownMode();
                if (mode == CooldownMode.DAILY || mode == CooldownMode.WEEKLY) {
                    String tradeResetTime = trade.getResetTime();
                    if (!tradeResetTime.matches("^([0-1][0-9]|2[0-3]):[0-5][0-9]$")) {
                        errors.add("Trade '" + trade.getTradeKey() + "' in shop '" + shop.getShopId()
                                + "': reset_time must be in HH:mm format (00:00 to 23:59). Current: '" + tradeResetTime + "'");
                    }
                }
                if (mode == CooldownMode.WEEKLY) {
                    if (!VALID_DAYS.contains(trade.getResetDay())) {
                        errors.add("Trade '" + trade.getTradeKey() + "' in shop '" + shop.getShopId()
                                + "': reset_day must be a valid day of the week. Current: '" + trade.getResetDay() + "'");
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
        Set<String> warnings = new HashSet<>();

        // Validate numeric ranges
        if (cooldownCheckInterval < 10 || cooldownCheckInterval > 3600) {
            warnings.add("cooldown_check_interval should be between 10-3600 seconds (currently: " + cooldownCheckInterval + ")");
        }

        if (cacheTTL < 5 || cacheTTL > 300) {
            warnings.add("cache_ttl should be between 5-300 seconds (currently: " + cacheTTL + ")");
        }

        if (batchWriteInterval < 5 || batchWriteInterval > 300) {
            warnings.add("batch_write_interval should be between 5-300 seconds (currently: " + batchWriteInterval + ")");
        }

        // Validate required message keys exist
        String[] requiredMessages = {
                TRADE_LIMIT_REACHED, TRADES_REMAINING, COOLDOWN_ACTIVE
        };

        for (String messageKey : requiredMessages) {
            if (!config.contains("messages." + messageKey)) {
                warnings.add("Missing message key: messages." + messageKey);
            }
        }

        if (purgeInactiveDays < 0) {
            warnings.add("purge_inactive_days must be >= 0 (0 to disable). Currently: " + purgeInactiveDays);
        }

        // Validate storage type
        if (!storageType.equalsIgnoreCase("sqlite")) {
            warnings.add("storage_type '" + storageType + "' is not supported. Only 'sqlite' is currently supported.");
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
     * Does not save to disk to avoid SnakeYAML reformatting the config file.
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

    public String getMessage(String key) {
        return config.getString("messages." + key, "<red>Message not found: " + key);
    }

    public String getMessageDisplay(String key) {
        return config.getString("message_display." + key, "chat");
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
