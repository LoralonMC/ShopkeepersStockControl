package dev.oakheart.stockcontrol.config;

import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.data.ShopConfig;
import dev.oakheart.stockcontrol.data.TradeConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

import static dev.oakheart.stockcontrol.config.Messages.*;

/**
 * Manages plugin configuration including main config and trades config.
 */
public class ConfigManager {
    private final ShopkeepersStockControl plugin;
    private FileConfiguration config;
    private FileConfiguration tradesConfig;
    private Map<String, ShopConfig> shops;

    public ConfigManager(ShopkeepersStockControl plugin) {
        this.plugin = plugin;
        this.shops = new HashMap<>();
    }

    /**
     * Loads all configuration files.
     */
    public void loadConfig() {
        // Save default config files if they don't exist
        plugin.saveDefaultConfig();
        config = plugin.getConfig();

        // Load trades.yml
        File tradesFile = new File(plugin.getDataFolder(), "trades.yml");
        if (!tradesFile.exists()) {
            plugin.saveResource("trades.yml", false);
        }
        tradesConfig = YamlConfiguration.loadConfiguration(tradesFile);

        // Load shop configurations
        loadShops();
    }

    /**
     * Reloads all configuration files.
     *
     * @return true if reload was successful, false otherwise
     */
    public boolean reload() {
        try {
            plugin.reloadConfig();
            config = plugin.getConfig();

            File tradesFile = new File(plugin.getDataFolder(), "trades.yml");
            tradesConfig = YamlConfiguration.loadConfiguration(tradesFile);

            // Load new shop configurations
            Map<String, ShopConfig> newShops = loadShopsFromConfig();

            // Validate new configurations
            Set<String> errors = validateShops(newShops);
            if (!errors.isEmpty()) {
                plugin.getLogger().severe("=== Configuration Errors ===");
                errors.forEach(error -> plugin.getLogger().severe("  - " + error));
                plugin.getLogger().severe("Fix these errors in trades.yml!");
                return false;
            }

            // Atomically swap configurations
            this.shops = newShops;
            plugin.getLogger().info("Configuration reloaded successfully");
            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to reload config: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Loads shop configurations from trades.yml.
     */
    private void loadShops() {
        shops = loadShopsFromConfig();

        // Validate main config
        Set<String> configErrors = validateMainConfig();
        if (!configErrors.isEmpty()) {
            plugin.getLogger().warning("=== Configuration Warnings (config.yml) ===");
            configErrors.forEach(error -> plugin.getLogger().warning("  - " + error));
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
     * @return Map of shop ID to ShopConfig
     */
    private Map<String, ShopConfig> loadShopsFromConfig() {
        Map<String, ShopConfig> loadedShops = new HashMap<>();

        ConfigurationSection shopsSection = tradesConfig.getConfigurationSection("shops");
        if (shopsSection == null) {
            plugin.getLogger().warning("No shops configured in trades.yml");
            return loadedShops;
        }

        for (String shopId : shopsSection.getKeys(false)) {
            ConfigurationSection shopSection = shopsSection.getConfigurationSection(shopId);
            if (shopSection == null) continue;

            String name = shopSection.getString("name", shopId);  // Default to shopId if no name
            boolean enabled = shopSection.getBoolean("enabled", true);
            boolean respectShopStock = shopSection.getBoolean("respect_shop_stock", false);

            Map<String, TradeConfig> trades = new HashMap<>();
            ConfigurationSection tradesSection = shopSection.getConfigurationSection("trades");
            if (tradesSection != null) {
                for (String tradeKey : tradesSection.getKeys(false)) {
                    ConfigurationSection tradeSection = tradesSection.getConfigurationSection(tradeKey);
                    if (tradeSection == null) continue;

                    int slot = tradeSection.getInt("slot", -1);
                    int maxTrades = tradeSection.getInt("max_trades", 1);
                    int cooldown = tradeSection.getInt("cooldown", 86400);

                    plugin.getLogger().info("Loading trade '" + tradeKey + "' for shop " + shopId +
                            ": slot=" + slot + ", max_trades=" + maxTrades + ", cooldown=" + cooldown);

                    TradeConfig tradeConfig = new TradeConfig(tradeKey, slot, maxTrades, cooldown);
                    trades.put(tradeKey, tradeConfig);
                }
            }

            ShopConfig shopConfig = new ShopConfig(shopId, name, enabled, respectShopStock, trades);
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
                if (trade.getCooldownSeconds() <= 0) {
                    errors.add("Trade '" + trade.getTradeKey() + "': cooldown must be > 0");
                }
                if (trade.getSlot() < 0) {
                    errors.add("Trade '" + trade.getTradeKey() + "': slot must be >= 0");
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
        int cooldownCheck = getCooldownCheckInterval();
        if (cooldownCheck < 10 || cooldownCheck > 3600) {
            warnings.add("cooldown_check_interval should be between 10-3600 seconds (currently: " + cooldownCheck + ")");
        }

        int cacheTTL = getCacheTTL();
        if (cacheTTL < 5 || cacheTTL > 300) {
            warnings.add("cache_ttl should be between 5-300 seconds (currently: " + cacheTTL + ")");
        }

        int batchWrite = getBatchWriteInterval();
        if (batchWrite < 5 || batchWrite > 300) {
            warnings.add("batch_write_interval should be between 5-300 seconds (currently: " + batchWrite + ")");
        }

        // Validate required message keys exist
        String[] requiredMessages = {
            TRADE_LIMIT_REACHED, TRADES_REMAINING, COOLDOWN_ACTIVE,
            TRADE_AVAILABLE, NO_PERMISSION, PLAYER_NOT_FOUND,
            TRADES_RESET, ALL_TRADES_RESET, CONFIG_RELOADED, CONFIG_RELOAD_FAILED
        };

        for (String messageKey : requiredMessages) {
            if (!config.contains("messages." + messageKey)) {
                warnings.add("Missing message key: messages." + messageKey);
            }
        }

        // Validate storage type
        String storageType = getStorageType();
        if (!storageType.equalsIgnoreCase("sqlite")) {
            warnings.add("storage_type '" + storageType + "' is not supported. Only 'sqlite' is currently supported.");
        }

        return warnings;
    }

    // Getters for configuration values
    public String getStorageType() {
        return config.getString("storage_type", "sqlite");
    }

    public int getCooldownCheckInterval() {
        return config.getInt("cooldown_check_interval", 60);
    }

    public int getCacheTTL() {
        return config.getInt("cache_ttl", 10);
    }

    public int getBatchWriteInterval() {
        return config.getInt("batch_write_interval", 30);
    }

    public boolean isDebugMode() {
        return config.getBoolean("debug", false);
    }

    public String getMessage(String key) {
        return config.getString("messages." + key, "&cMessage not found: " + key);
    }

    public Map<String, ShopConfig> getShops() {
        return new HashMap<>(shops);
    }

    public ShopConfig getShop(String shopId) {
        return shops.get(shopId);
    }

    public boolean hasShop(String shopId) {
        return shops.containsKey(shopId);
    }
}
