package dev.oakheart.stockcontrol;

import dev.oakheart.stockcontrol.commands.StockControlCommand;
import dev.oakheart.stockcontrol.config.ConfigManager;
import dev.oakheart.stockcontrol.data.DataStore;
import dev.oakheart.stockcontrol.data.SQLiteDataStore;
import dev.oakheart.stockcontrol.listeners.PlayerQuitListener;
import dev.oakheart.stockcontrol.listeners.ShopkeepersListener;
import dev.oakheart.stockcontrol.managers.CooldownManager;
import dev.oakheart.stockcontrol.managers.PacketManager;
import dev.oakheart.stockcontrol.managers.TradeDataManager;
import dev.oakheart.stockcontrol.message.MessageManager;
import dev.oakheart.stockcontrol.placeholders.StockControlExpansion;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Main plugin class for ShopkeepersStockControl.
 * Enables per-player trade limits with accurate stock display in villager trading UI.
 */
public final class ShopkeepersStockControl extends JavaPlugin {

    private ConfigManager configManager;
    private MessageManager messageManager;
    private DataStore dataStore;
    private TradeDataManager tradeDataManager;
    private PacketManager packetManager;
    private CooldownManager cooldownManager;

    @Override
    public void onEnable() {
        try {
            checkDependencies();
            initializeComponents();
            registerListeners();
            registerCommands();
            initializeMetrics();
            registerPlaceholders();

            getLogger().info("ShopkeepersStockControl enabled successfully!");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to enable ShopkeepersStockControl", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (cooldownManager != null) {
            cooldownManager.shutdown();
        }
        if (packetManager != null) {
            packetManager.shutdown();
        }
        if (tradeDataManager != null) {
            tradeDataManager.shutdown();
        }
        if (dataStore != null) {
            dataStore.close();
        }
    }

    private void checkDependencies() {
        if (getServer().getPluginManager().getPlugin("Shopkeepers") == null) {
            throw new IllegalStateException("Shopkeepers plugin not found! This plugin requires Shopkeepers to function.");
        }
        getLogger().info("Shopkeepers detected: " +
                getServer().getPluginManager().getPlugin("Shopkeepers").getPluginMeta().getVersion());

        if (getServer().getPluginManager().getPlugin("packetevents") == null) {
            throw new IllegalStateException("PacketEvents plugin not found! This plugin requires PacketEvents for UI stock display.");
        }
        getLogger().info("PacketEvents detected: " +
                getServer().getPluginManager().getPlugin("packetevents").getPluginMeta().getVersion());
    }

    private void initializeComponents() {
        // Configuration
        configManager = new ConfigManager(this);
        configManager.load();
        messageManager = new MessageManager(configManager);

        // Data layer
        initializeDataLayer();

        // Packet layer
        packetManager = new PacketManager(this, tradeDataManager);
        packetManager.initialize();

        // Cooldown manager
        cooldownManager = new CooldownManager(this, tradeDataManager);
        cooldownManager.initialize();
    }

    private void initializeDataLayer() {
        String storageType = configManager.getStorageType();

        if ("sqlite".equalsIgnoreCase(storageType)) {
            dataStore = new SQLiteDataStore(this);
            dataStore.initialize();

            if (!dataStore.isOperational()) {
                throw new RuntimeException("Failed to initialize SQLite database");
            }
        } else {
            throw new UnsupportedOperationException("Storage type '" + storageType + "' not supported. Please use SQLite.");
        }

        tradeDataManager = new TradeDataManager(this, dataStore);
        tradeDataManager.initialize();

        getLogger().info("Data layer initialized successfully (" + storageType + ")");
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                new ShopkeepersListener(this, packetManager, tradeDataManager), this);

        getServer().getPluginManager().registerEvents(
                new PlayerQuitListener(tradeDataManager, packetManager), this);
    }

    private void registerCommands() {
        new StockControlCommand(this).register();
    }

    private void initializeMetrics() {
        try {
            new Metrics(this, 27827);
        } catch (Exception e) {
            getLogger().warning("Failed to initialize bStats: " + e.getMessage());
        }
    }

    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new StockControlExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }
    }

    /**
     * Reloads configuration and restarts dependent scheduled tasks.
     *
     * @return true if reload was successful, false otherwise
     */
    public boolean reloadPluginConfig() {
        boolean success = configManager.reload();
        if (success) {
            tradeDataManager.restartBatchWriteTask();
            cooldownManager.restart();
        }
        return success;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public DataStore getDataStore() {
        return dataStore;
    }

    public TradeDataManager getTradeDataManager() {
        return tradeDataManager;
    }

    public PacketManager getPacketManager() {
        return packetManager;
    }

    public CooldownManager getCooldownManager() {
        return cooldownManager;
    }

}
