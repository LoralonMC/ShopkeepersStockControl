package dev.oakheart.stockcontrol.data;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a player's trade data for a specific trade in a specific shop.
 * This class tracks how many times a player has used a trade and when their cooldown started.
 *
 * <p>{@code tradesUsed} is an {@link AtomicInteger} so concurrent {@code incrementTradesUsed()}
 * calls compose without lost updates. Real production trades always fire on the main thread,
 * but this guards against accidental concurrent mutation from admin commands, Folia support,
 * or other async paths.</p>
 */
public class PlayerTradeData {
    private final UUID playerId;
    private final String shopId;
    private final String tradeKey;         // Stable identifier (e.g., "diamond_trade")
    private final AtomicInteger tradesUsed;
    private volatile long lastResetEpoch;           // Timestamp when cooldown started (limit reached)
    private volatile int cooldownSeconds;           // Cooldown duration from config

    /**
     * Creates a new PlayerTradeData instance.
     *
     * @param playerId         The player's UUID
     * @param shopId           The shop's unique identifier
     * @param tradeKey         The stable trade key
     * @param tradesUsed       Number of trades used
     * @param lastResetEpoch   When the cooldown started (Unix timestamp in seconds)
     * @param cooldownSeconds  Duration of cooldown in seconds
     */
    public PlayerTradeData(UUID playerId, String shopId, String tradeKey,
                           int tradesUsed, long lastResetEpoch, int cooldownSeconds) {
        this.playerId = playerId;
        this.shopId = shopId;
        this.tradeKey = tradeKey;
        this.tradesUsed = new AtomicInteger(tradesUsed);
        this.lastResetEpoch = lastResetEpoch;
        this.cooldownSeconds = cooldownSeconds;
    }

    // Getters
    public UUID getPlayerId() {
        return playerId;
    }

    public String getShopId() {
        return shopId;
    }

    public String getTradeKey() {
        return tradeKey;
    }

    public int getTradesUsed() {
        return tradesUsed.get();
    }

    /**
     * Atomically increments trades used and returns the new value. Use this in preference to
     * {@code setTradesUsed(getTradesUsed() + 1)} — the latter has a read-modify-write race
     * under concurrent mutation that can lose updates.
     */
    public int incrementTradesUsed() {
        return tradesUsed.incrementAndGet();
    }

    public long getLastResetEpoch() {
        return lastResetEpoch;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    // Setters
    public void setTradesUsed(int tradesUsed) {
        this.tradesUsed.set(tradesUsed);
    }

    public void setLastResetEpoch(long lastResetEpoch) {
        this.lastResetEpoch = lastResetEpoch;
    }

    public void setCooldownSeconds(int cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    /**
     * Creates a cache key for this trade data.
     * Format: "playerId:shopId:tradeKey"
     */
    public String getCacheKey() {
        return playerId + ":" + shopId + ":" + tradeKey;
    }

    @Override
    public String toString() {
        return "PlayerTradeData{" +
                "playerId=" + playerId +
                ", shopId='" + shopId + '\'' +
                ", tradeKey='" + tradeKey + '\'' +
                ", tradesUsed=" + tradesUsed.get() +
                ", lastResetEpoch=" + lastResetEpoch +
                ", cooldownSeconds=" + cooldownSeconds +
                '}';
    }
}
