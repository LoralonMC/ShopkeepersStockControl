package dev.oakheart.stockcontrol.data;

import dev.oakheart.stockcontrol.ShopkeepersStockControl;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * SQLite implementation of the DataStore interface.
 * Uses WAL mode for better concurrency and prepared statements for performance.
 */
public class SQLiteDataStore implements DataStore {

    private final ShopkeepersStockControl plugin;
    private Connection connection;
    private boolean operational;

    // Prepared statements for performance
    private PreparedStatement loadTradeStmt;
    private PreparedStatement loadPlayerStmt;
    private PreparedStatement loadShopStmt;
    private PreparedStatement upsertTradeStmt;
    private PreparedStatement deleteTradeStmt;
    private PreparedStatement deletePlayerStmt;
    private PreparedStatement deletePlayerShopStmt;
    private PreparedStatement getAllPlayersStmt;

    public SQLiteDataStore(ShopkeepersStockControl plugin) {
        this.plugin = plugin;
        this.operational = false;
    }

    @Override
    public void initialize() {
        try {
            // Create database file in plugin folder
            File dbFile = new File(plugin.getDataFolder(), "stockcontrol.db");
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

            // Load SQLite JDBC driver
            Class.forName("org.sqlite.JDBC");

            // Connect to database
            connection = DriverManager.getConnection(url);

            // Enable WAL mode for better concurrency
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA synchronous=NORMAL;");
                stmt.execute("PRAGMA foreign_keys=ON;");
            }

            // Create tables
            createTables();

            // Prepare statements
            prepareStatements();

            operational = true;
            plugin.getLogger().info("SQLite database initialized successfully at: " + dbFile.getAbsolutePath());

        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("SQLite JDBC driver not found!");
            e.printStackTrace();
            operational = false;
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize SQLite database: " + e.getMessage());
            e.printStackTrace();
            operational = false;
        }
    }

    /**
     * Creates the database tables if they don't exist.
     */
    private void createTables() throws SQLException {
        String createTableSQL = """
                CREATE TABLE IF NOT EXISTS player_trades (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT NOT NULL,
                    shop_id TEXT NOT NULL,
                    trade_key TEXT NOT NULL,
                    trades_used INTEGER DEFAULT 0,
                    last_reset_epoch BIGINT,
                    cooldown_seconds INTEGER NOT NULL,
                    UNIQUE(player_uuid, shop_id, trade_key)
                );
                """;

        String createPlayerShopIndexSQL = """
                CREATE INDEX IF NOT EXISTS idx_player_shop
                ON player_trades(player_uuid, shop_id);
                """;

        // Note: No separate (player_uuid, shop_id, trade_key) index needed —
        // the UNIQUE constraint already creates an equivalent index.

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTableSQL);
            stmt.execute(createPlayerShopIndexSQL);
        }

        plugin.getLogger().info("Database tables created/verified successfully");
    }

    /**
     * Prepares all SQL statements for reuse.
     */
    private void prepareStatements() throws SQLException {
        // Load single trade data
        loadTradeStmt = connection.prepareStatement(
                "SELECT * FROM player_trades WHERE player_uuid = ? AND shop_id = ? AND trade_key = ?"
        );

        // Load all trades for a player
        loadPlayerStmt = connection.prepareStatement(
                "SELECT * FROM player_trades WHERE player_uuid = ?"
        );

        // Load all trades for a shop
        loadShopStmt = connection.prepareStatement(
                "SELECT * FROM player_trades WHERE shop_id = ?"
        );

        // Upsert trade data
        upsertTradeStmt = connection.prepareStatement("""
                INSERT INTO player_trades (player_uuid, shop_id, trade_key, trades_used, last_reset_epoch, cooldown_seconds)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid, shop_id, trade_key)
                DO UPDATE SET
                    trades_used = excluded.trades_used,
                    last_reset_epoch = excluded.last_reset_epoch,
                    cooldown_seconds = excluded.cooldown_seconds
                """);

        // Delete single trade data
        deleteTradeStmt = connection.prepareStatement(
                "DELETE FROM player_trades WHERE player_uuid = ? AND shop_id = ? AND trade_key = ?"
        );

        // Delete all trades for a player
        deletePlayerStmt = connection.prepareStatement(
                "DELETE FROM player_trades WHERE player_uuid = ?"
        );

        // Delete all trades for a player in a specific shop
        deletePlayerShopStmt = connection.prepareStatement(
                "DELETE FROM player_trades WHERE player_uuid = ? AND shop_id = ?"
        );

        // Get all unique player UUIDs
        getAllPlayersStmt = connection.prepareStatement(
                "SELECT DISTINCT player_uuid FROM player_trades"
        );
    }

    @Override
    public synchronized PlayerTradeData loadTradeData(UUID playerId, String shopId, String tradeKey) {
        if (!operational) return null;

        try {
            loadTradeStmt.setString(1, playerId.toString());
            loadTradeStmt.setString(2, shopId);
            loadTradeStmt.setString(3, tradeKey);

            try (ResultSet rs = loadTradeStmt.executeQuery()) {
                if (rs.next()) {
                    return extractTradeData(rs);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error loading trade data", e);
        }

        return null;
    }

    @Override
    public synchronized List<PlayerTradeData> loadPlayerData(UUID playerId) {
        List<PlayerTradeData> result = new ArrayList<>();
        if (!operational) return result;

        try {
            loadPlayerStmt.setString(1, playerId.toString());

            try (ResultSet rs = loadPlayerStmt.executeQuery()) {
                while (rs.next()) {
                    result.add(extractTradeData(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error loading player data", e);
        }

        return result;
    }

    @Override
    public synchronized List<PlayerTradeData> loadShopData(String shopId) {
        List<PlayerTradeData> result = new ArrayList<>();
        if (!operational) return result;

        try {
            loadShopStmt.setString(1, shopId);

            try (ResultSet rs = loadShopStmt.executeQuery()) {
                while (rs.next()) {
                    result.add(extractTradeData(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error loading shop data", e);
        }

        return result;
    }

    @Override
    public synchronized void saveTradeData(PlayerTradeData data) {
        if (!operational) return;

        try {
            upsertTradeStmt.setString(1, data.getPlayerId().toString());
            upsertTradeStmt.setString(2, data.getShopId());
            upsertTradeStmt.setString(3, data.getTradeKey());
            upsertTradeStmt.setInt(4, data.getTradesUsed());
            upsertTradeStmt.setLong(5, data.getLastResetEpoch());
            upsertTradeStmt.setInt(6, data.getCooldownSeconds());

            upsertTradeStmt.executeUpdate();

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error saving trade data", e);
        }
    }

    @Override
    public synchronized void batchSaveTradeData(List<PlayerTradeData> dataList) {
        if (!operational || dataList.isEmpty()) return;

        try {
            connection.setAutoCommit(false);

            for (PlayerTradeData data : dataList) {
                upsertTradeStmt.setString(1, data.getPlayerId().toString());
                upsertTradeStmt.setString(2, data.getShopId());
                upsertTradeStmt.setString(3, data.getTradeKey());
                upsertTradeStmt.setInt(4, data.getTradesUsed());
                upsertTradeStmt.setLong(5, data.getLastResetEpoch());
                upsertTradeStmt.setInt(6, data.getCooldownSeconds());
                upsertTradeStmt.addBatch();
            }

            upsertTradeStmt.executeBatch();
            connection.commit();
            connection.setAutoCommit(true);

            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Batch saved " + dataList.size() + " trade entries");
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error batch saving trade data", e);
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "Error rolling back transaction", ex);
            }
        }
    }

    @Override
    public synchronized void deleteTradeData(UUID playerId, String shopId, String tradeKey) {
        if (!operational) return;

        try {
            deleteTradeStmt.setString(1, playerId.toString());
            deleteTradeStmt.setString(2, shopId);
            deleteTradeStmt.setString(3, tradeKey);
            deleteTradeStmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error deleting trade data", e);
        }
    }

    @Override
    public synchronized void deletePlayerData(UUID playerId) {
        if (!operational) return;

        try {
            deletePlayerStmt.setString(1, playerId.toString());
            int deleted = deletePlayerStmt.executeUpdate();
            plugin.getLogger().info("Deleted " + deleted + " trade entries for player " + playerId);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error deleting player data", e);
        }
    }

    @Override
    public synchronized void deletePlayerShopData(UUID playerId, String shopId) {
        if (!operational) return;

        try {
            deletePlayerShopStmt.setString(1, playerId.toString());
            deletePlayerShopStmt.setString(2, shopId);
            deletePlayerShopStmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error deleting player shop data", e);
        }
    }

    @Override
    public synchronized List<UUID> getAllPlayers() {
        List<UUID> result = new ArrayList<>();
        if (!operational) return result;

        try (ResultSet rs = getAllPlayersStmt.executeQuery()) {
            while (rs.next()) {
                try {
                    UUID playerId = UUID.fromString(rs.getString("player_uuid"));
                    result.add(playerId);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid UUID in database: " + rs.getString("player_uuid"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error getting all players", e);
        }

        return result;
    }

    @Override
    public boolean isOperational() {
        return operational && connection != null;
    }

    @Override
    public synchronized void close() {
        try {
            // Close prepared statements
            if (loadTradeStmt != null) loadTradeStmt.close();
            if (loadPlayerStmt != null) loadPlayerStmt.close();
            if (loadShopStmt != null) loadShopStmt.close();
            if (upsertTradeStmt != null) upsertTradeStmt.close();
            if (deleteTradeStmt != null) deleteTradeStmt.close();
            if (deletePlayerStmt != null) deletePlayerStmt.close();
            if (deletePlayerShopStmt != null) deletePlayerShopStmt.close();
            if (getAllPlayersStmt != null) getAllPlayersStmt.close();

            // Close connection
            if (connection != null && !connection.isClosed()) {
                connection.close();
                plugin.getLogger().info("SQLite database connection closed");
            }

            operational = false;

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Error closing database connection", e);
        }
    }

    /**
     * Extracts PlayerTradeData from a ResultSet.
     */
    private PlayerTradeData extractTradeData(ResultSet rs) throws SQLException {
        UUID playerId = UUID.fromString(rs.getString("player_uuid"));
        String shopId = rs.getString("shop_id");
        String tradeKey = rs.getString("trade_key");
        int tradesUsed = rs.getInt("trades_used");
        long lastResetEpoch = rs.getLong("last_reset_epoch");
        int cooldownSeconds = rs.getInt("cooldown_seconds");

        return new PlayerTradeData(playerId, shopId, tradeKey, tradesUsed, lastResetEpoch, cooldownSeconds);
    }
}
