package dev.oakheart.stockcontrol;

import dev.oakheart.stockcontrol.commands.StockControlCommand;
import dev.oakheart.stockcontrol.config.ConfigManager;
import dev.oakheart.stockcontrol.data.DataStore;
import dev.oakheart.stockcontrol.data.SQLiteDataStore;
import dev.oakheart.stockcontrol.listeners.ShopkeepersListener;
import dev.oakheart.stockcontrol.managers.CooldownManager;
import dev.oakheart.stockcontrol.managers.PacketManager;
import dev.oakheart.stockcontrol.managers.TradeDataManager;
import dev.oakheart.stockcontrol.placeholders.StockControlExpansion;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin class for ShopkeepersStockControl.
 * Enables per-player trade limits with accurate stock display in villager trading UI.
 */
public final class ShopkeepersStockControl extends JavaPlugin implements Listener {

    private static ShopkeepersStockControl instance;
    private ConfigManager configManager;
    private DataStore dataStore;
    private TradeDataManager tradeDataManager;
    private PacketManager packetManager;
    private ShopkeepersListener shopkeepersListener;
    private CooldownManager cooldownManager;

    @Override
    public void onEnable() {
        instance = this;

        // ASCII art banner
        String ver = getDescription().getVersion();
        getLogger().info("╔═══════════════════════════════════════════╗");
        getLogger().info("║  ShopkeepersStockControl                 ║");
        getLogger().info(String.format("║  Version: %-32s║", ver));
        getLogger().info("║  Per-player trade limits with cooldowns  ║");
        getLogger().info("╚═══════════════════════════════════════════╝");

        // Check for required dependencies
        if (!checkDependencies()) {
            getLogger().severe("Missing required dependencies! Disabling plugin...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Load configuration
        try {
            configManager = new ConfigManager(this);
            configManager.loadConfig();
            getLogger().info("Configuration loaded successfully");
        } catch (Exception e) {
            getLogger().severe("Failed to load configuration: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize data layer (Phase 2)
        try {
            initializeDataLayer();
        } catch (Exception e) {
            getLogger().severe("Failed to initialize data layer: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize packet layer (Phase 3)
        try {
            packetManager = new PacketManager(this, tradeDataManager);
            packetManager.initialize();
        } catch (Exception e) {
            getLogger().severe("Failed to initialize packet layer: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Register Shopkeepers listeners (Phase 3/4)
        shopkeepersListener = new ShopkeepersListener(this, packetManager, tradeDataManager);
        getServer().getPluginManager().registerEvents(shopkeepersListener, this);
        getLogger().info("Shopkeepers listeners registered successfully");

        // Register player quit listener for cache cleanup
        getServer().getPluginManager().registerEvents(this, this);

        // Initialize cooldown manager (Phase 5)
        try {
            cooldownManager = new CooldownManager(this, tradeDataManager);
            cooldownManager.initialize();
        } catch (Exception e) {
            getLogger().severe("Failed to initialize cooldown manager: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Register commands (Phase 6)
        StockControlCommand commandExecutor = new StockControlCommand(this);
        getCommand("ssc").setExecutor(commandExecutor);
        getCommand("ssc").setTabCompleter(commandExecutor);
        getLogger().info("Commands registered successfully");

        // Register PlaceholderAPI expansion if available
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new StockControlExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered");
        }

        // Initialize bStats metrics (anonymous usage statistics)
        try {
            int pluginId = 27827; // bStats plugin ID for ShopkeepersStockControl
            Metrics metrics = new Metrics(this, pluginId);
            getLogger().info("bStats metrics initialized");
        } catch (Exception e) {
            getLogger().warning("Failed to initialize bStats: " + e.getMessage());
        }

        getLogger().info("ShopkeepersStockControl enabled successfully!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Shutting down ShopkeepersStockControl...");

        // Phase 5 - Shutdown cooldown manager
        if (cooldownManager != null) {
            cooldownManager.shutdown();
        }

        // Phase 3 - Shutdown packet layer
        if (packetManager != null) {
            packetManager.shutdown();
        }

        // Phase 2 - Shutdown data layer
        if (tradeDataManager != null) {
            tradeDataManager.shutdown();
        }

        if (dataStore != null) {
            dataStore.close();
        }

        instance = null;
        getLogger().info("ShopkeepersStockControl disabled successfully!");
    }

    /**
     * Initializes the data layer (DataStore and TradeDataManager).
     */
    private void initializeDataLayer() {
        String storageType = configManager.getStorageType();

        if ("sqlite".equalsIgnoreCase(storageType)) {
            dataStore = new SQLiteDataStore(this);
            dataStore.initialize();

            if (!dataStore.isOperational()) {
                throw new RuntimeException("Failed to initialize SQLite database");
            }
        } else {
            // TODO: Implement YAML storage if needed
            throw new UnsupportedOperationException("YAML storage not yet implemented. Please use SQLite.");
        }

        // Initialize TradeDataManager
        tradeDataManager = new TradeDataManager(this, dataStore);
        tradeDataManager.initialize();

        getLogger().info("Data layer initialized successfully (" + storageType + ")");
    }

    /**
     * Handles player quit events to evict cache and flush data.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (tradeDataManager != null) {
            tradeDataManager.evictPlayer(event.getPlayer().getUniqueId());
        }
    }

    /**
     * Checks if all required dependencies are present.
     *
     * @return true if all dependencies are available, false otherwise
     */
    private boolean checkDependencies() {
        boolean allPresent = true;

        // Check for Shopkeepers
        if (getServer().getPluginManager().getPlugin("Shopkeepers") == null) {
            getLogger().severe("Shopkeepers plugin not found! This plugin requires Shopkeepers to function.");
            allPresent = false;
        } else {
            getLogger().info("Shopkeepers detected: " +
                    getServer().getPluginManager().getPlugin("Shopkeepers").getDescription().getVersion());
        }

        // PacketEvents is a soft-depend — check but don't require
        if (getServer().getPluginManager().getPlugin("PacketEvents") == null) {
            getLogger().warning("PacketEvents plugin not found! UI stock display will be disabled.");
        } else {
            getLogger().info("PacketEvents detected: " +
                    getServer().getPluginManager().getPlugin("PacketEvents").getDescription().getVersion());
        }

        return allPresent;
    }

    /**
     * Gets the plugin instance.
     *
     * @return The plugin instance
     */
    public static ShopkeepersStockControl getInstance() {
        return instance;
    }

    /**
     * Gets the configuration manager.
     *
     * @return The configuration manager
     */
    public ConfigManager getConfigManager() {
        return configManager;
    }

    /**
     * Gets the data store.
     *
     * @return The data store
     */
    public DataStore getDataStore() {
        return dataStore;
    }

    /**
     * Gets the trade data manager.
     *
     * @return The trade data manager
     */
    public TradeDataManager getTradeDataManager() {
        return tradeDataManager;
    }

    /**
     * Gets the packet manager.
     *
     * @return The packet manager
     */
    public PacketManager getPacketManager() {
        return packetManager;
    }

    /**
     * Gets the cooldown manager.
     *
     * @return The cooldown manager
     */
    public CooldownManager getCooldownManager() {
        return cooldownManager;
    }
}
