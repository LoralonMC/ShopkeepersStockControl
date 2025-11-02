package dev.oakheart.stockcontrol.data;

import java.util.List;
import java.util.UUID;

/**
 * Interface for data persistence operations.
 * Supports both SQLite and YAML implementations.
 */
public interface DataStore {

    /**
     * Initializes the data store (creates tables, files, etc.).
     */
    void initialize();

    /**
     * Closes the data store and releases resources.
     */
    void close();

    /**
     * Loads trade data for a specific player, shop, and trade.
     *
     * @param playerId The player's UUID
     * @param shopId   The shop identifier
     * @param tradeKey The trade key
     * @return PlayerTradeData or null if not found
     */
    PlayerTradeData loadTradeData(UUID playerId, String shopId, String tradeKey);

    /**
     * Loads all trade data for a specific player.
     *
     * @param playerId The player's UUID
     * @return List of PlayerTradeData
     */
    List<PlayerTradeData> loadPlayerData(UUID playerId);

    /**
     * Loads all trade data for a specific shop.
     *
     * @param shopId The shop identifier
     * @return List of PlayerTradeData
     */
    List<PlayerTradeData> loadShopData(String shopId);

    /**
     * Saves or updates trade data using UPSERT logic.
     *
     * @param data The trade data to save
     */
    void saveTradeData(PlayerTradeData data);

    /**
     * Saves multiple trade data entries in a batch.
     *
     * @param dataList List of trade data to save
     */
    void batchSaveTradeData(List<PlayerTradeData> dataList);

    /**
     * Deletes trade data for a specific player, shop, and trade.
     *
     * @param playerId The player's UUID
     * @param shopId   The shop identifier
     * @param tradeKey The trade key
     */
    void deleteTradeData(UUID playerId, String shopId, String tradeKey);

    /**
     * Deletes all trade data for a specific player.
     *
     * @param playerId The player's UUID
     */
    void deletePlayerData(UUID playerId);

    /**
     * Deletes all trade data for a specific player in a specific shop.
     *
     * @param playerId The player's UUID
     * @param shopId   The shop identifier
     */
    void deletePlayerShopData(UUID playerId, String shopId);

    /**
     * Gets all player UUIDs that have trade data.
     *
     * @return List of player UUIDs
     */
    List<UUID> getAllPlayers();

    /**
     * Checks if the data store is properly initialized and operational.
     *
     * @return true if operational, false otherwise
     */
    boolean isOperational();
}
