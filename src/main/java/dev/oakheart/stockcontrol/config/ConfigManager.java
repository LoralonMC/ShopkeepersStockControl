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
import java.nio.charset.StandardCharsets;
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

    // Old snake_case -> new kebab-case key mappings for config.yml migration
    private static final Map<String, String> CONFIG_KEY_MIGRATIONS = Map.of(
            "storage_type", "storage-type",
            "cooldown_check_interval", "cooldown-check-interval",
            "cache_ttl", "cache-ttl",
            "batch_write_interval", "batch-write-interval",
            "purge_inactive_days", "purge-inactive-days"
    );

    // Old snake_case -> new kebab-case key mappings for message keys
    private static final Map<String, String> MESSAGE_KEY_MIGRATIONS = Map.of(
            "trade_limit_reached", "trade-limit-reached",
            "trades_remaining", "trades-remaining",
            "cooldown_active", "cooldown-active"
    );

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

        // Load config.yml
        config = YamlConfiguration.loadConfiguration(configFile);
        migrateConfig();
        mergeDefaults();
        cacheValues();

        // Load trades.yml
        tradesConfig = YamlConfiguration.loadConfiguration(tradesFile);
        migrateTradesConfig();

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
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
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
     * Migrates config.yml from v1 (snake_case) to v2 (kebab-case).
     * Only runs if config-version is missing or < 2.
     */
    private void migrateConfig() {
        int version = config.getInt("config-version", 1);
        if (version >= 2) return;

        plugin.getLogger().info("Migrating config.yml from v" + version + " to v2 (kebab-case keys)...");
        boolean changed = false;

        // Migrate top-level keys
        for (Map.Entry<String, String> entry : CONFIG_KEY_MIGRATIONS.entrySet()) {
            if (config.contains(entry.getKey(), true) && !config.contains(entry.getValue(), true)) {
                config.set(entry.getValue(), config.get(entry.getKey()));
                config.set(entry.getKey(), null);
                changed = true;
            }
        }

        // Migrate message keys under messages: (snake_case -> kebab-case)
        ConfigurationSection messages = config.getConfigurationSection("messages");
        if (messages != null) {
            for (Map.Entry<String, String> entry : MESSAGE_KEY_MIGRATIONS.entrySet()) {
                if (messages.contains(entry.getKey()) && !messages.contains(entry.getValue())) {
                    messages.set(entry.getValue(), messages.get(entry.getKey()));
                    messages.set(entry.getKey(), null);
                    changed = true;
                }
            }
        }

        // Convert flat messages + message-display into nested text/display structure
        // Re-fetch in case messages section was just created by key migrations above
        messages = config.getConfigurationSection("messages");
        ConfigurationSection messageDisplay = config.getConfigurationSection("message-display");
        if (messageDisplay == null) {
            messageDisplay = config.getConfigurationSection("message_display");
        }

        if (messages != null) {
            for (String key : new ArrayList<>(messages.getKeys(false))) {
                Object value = messages.get(key);
                // Only convert flat strings — skip if already nested (has .text subkey)
                if (value instanceof String text) {
                    String display = messageDisplay != null ? messageDisplay.getString(key, "chat") : "chat";
                    messages.set(key, null);
                    messages.set(key + ".text", text);
                    messages.set(key + ".display", display);
                    changed = true;
                }
            }
        }

        // Remove the old message-display section
        if (config.contains("message-display", true)) {
            config.set("message-display", null);
            changed = true;
        }
        if (config.contains("message_display", true)) {
            config.set("message_display", null);
            changed = true;
        }

        config.set("config-version", 2);
        changed = true;

        if (changed) {
            try {
                config.save(configFile);
                plugin.getLogger().info("config.yml migrated to v2 successfully");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save migrated config.yml", e);
            }
        }
    }

    /**
     * Migrates trades.yml from snake_case to kebab-case keys.
     * Checks each shop and trade section for old-style keys.
     */
    private void migrateTradesConfig() {
        ConfigurationSection shopsSection = tradesConfig.getConfigurationSection("shops");
        if (shopsSection == null) return;

        boolean changed = false;
        for (String shopId : shopsSection.getKeys(false)) {
            ConfigurationSection shopSection = shopsSection.getConfigurationSection(shopId);
            if (shopSection == null) continue;

            // Migrate shop-level keys
            for (Map.Entry<String, String> entry : TRADES_KEY_MIGRATIONS.entrySet()) {
                if (shopSection.contains(entry.getKey()) && !shopSection.contains(entry.getValue())) {
                    shopSection.set(entry.getValue(), shopSection.get(entry.getKey()));
                    shopSection.set(entry.getKey(), null);
                    changed = true;
                }
            }

            // Migrate per-trade keys
            ConfigurationSection tradesSection = shopSection.getConfigurationSection("trades");
            if (tradesSection != null) {
                for (String tradeKey : tradesSection.getKeys(false)) {
                    ConfigurationSection tradeSection = tradesSection.getConfigurationSection(tradeKey);
                    if (tradeSection == null) continue;

                    for (Map.Entry<String, String> entry : TRADES_KEY_MIGRATIONS.entrySet()) {
                        if (tradeSection.contains(entry.getKey()) && !tradeSection.contains(entry.getValue())) {
                            tradeSection.set(entry.getValue(), tradeSection.get(entry.getKey()));
                            tradeSection.set(entry.getKey(), null);
                            changed = true;
                        }
                    }
                }
            }
        }

        if (changed) {
            try {
                tradesConfig.save(tradesFile);
                plugin.getLogger().info("trades.yml migrated to kebab-case keys successfully");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save migrated trades.yml", e);
            }
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
            CooldownMode cooldownMode = CooldownMode.fromString(shopSection.getString("cooldown-mode", "rolling"));
            String resetTime = shopSection.getString("reset-time", "00:00");
            String rawResetDay = shopSection.getString("reset-day", "monday");
            String resetDay = rawResetDay != null ? rawResetDay.toUpperCase() : "MONDAY";

            // Stock mode settings
            StockMode stockMode = StockMode.fromString(shopSection.getString("stock-mode", "per_player"));
            int shopMaxPerPlayer = shopSection.getInt("max-per-player", 0);

            Map<String, TradeConfig> trades = new HashMap<>();
            ConfigurationSection tradesSection = shopSection.getConfigurationSection("trades");
            if (tradesSection != null) {
                for (String tradeKey : tradesSection.getKeys(false)) {
                    ConfigurationSection tradeSection = tradesSection.getConfigurationSection(tradeKey);
                    if (tradeSection == null) continue;

                    int slot = tradeSection.getInt("slot", -1);
                    int maxTrades = tradeSection.getInt("max-trades", 1);
                    int cooldown = tradeSection.getInt("cooldown", 86400);

                    // Per-trade cooldown settings with shop-level fallback
                    CooldownMode tradeCooldownMode = tradeSection.contains("cooldown-mode")
                            ? CooldownMode.fromString(tradeSection.getString("cooldown-mode"))
                            : cooldownMode;
                    String tradeResetTime = tradeSection.getString("reset-time", resetTime);
                    String rawTradeResetDay = tradeSection.getString("reset-day");
                    String tradeResetDay = rawTradeResetDay != null ? rawTradeResetDay.toUpperCase() : resetDay;

                    int tradeMaxPerPlayer = tradeSection.contains("max-per-player")
                            ? tradeSection.getInt("max-per-player", 0)
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

        // Validate player-facing messages with display mode
        String[] playerMessages = {
                TRADE_LIMIT_REACHED, TRADES_REMAINING, COOLDOWN_ACTIVE
        };

        for (String messageKey : playerMessages) {
            if (!config.contains("messages." + messageKey + ".text")) {
                warnings.add("Missing message key: messages." + messageKey + ".text");
            }
            String display = config.getString("messages." + messageKey + ".display", "chat");
            if (!"chat".equals(display) && !"action_bar".equals(display)) {
                warnings.add("messages." + messageKey + ".display must be 'chat' or 'action_bar' (currently: '" + display + "')");
            }
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
        return config.getString("messages." + key + ".text", "<red>Message not found: " + key);
    }

    public String getMessageDisplay(String key) {
        return config.getString("messages." + key + ".display", "chat");
    }

    /**
     * Gets a message as a list of lines (for YAML list format).
     * If the config value is a String, wraps it in a single-element list.
     * Returns null if the key is missing or the text is blank.
     */
    public List<String> getMessageList(String key) {
        String path = "messages." + key + ".text";
        if (config.isList(path)) {
            return config.getStringList(path);
        }
        String text = config.getString(path);
        if (text == null || text.isBlank()) return null;
        return List.of(text);
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
