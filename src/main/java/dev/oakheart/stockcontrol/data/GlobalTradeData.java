package dev.oakheart.stockcontrol.data;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents global trade stock data for a shared-mode shop.
 * Tracks the combined stock pool that all players draw from.
 *
 * <p>{@code tradesUsed} is an {@link AtomicInteger} so concurrent
 * {@code incrementTradesUsed()} calls compose without lost updates. Real production
 * trades always fire on the main thread, but this guards against accidental concurrent
 * mutation from admin commands, Folia support, or other async paths.</p>
 */
public class GlobalTradeData {
    private final String shopId;
    private final String tradeKey;
    private final AtomicInteger tradesUsed;
    private volatile long lastResetEpoch;
    private volatile int cooldownSeconds;

    public GlobalTradeData(String shopId, String tradeKey,
                           int tradesUsed, long lastResetEpoch, int cooldownSeconds) {
        this.shopId = shopId;
        this.tradeKey = tradeKey;
        this.tradesUsed = new AtomicInteger(tradesUsed);
        this.lastResetEpoch = lastResetEpoch;
        this.cooldownSeconds = cooldownSeconds;
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
     * Creates a cache key for this global trade data.
     * Format: "shopId:tradeKey"
     */
    public String getCacheKey() {
        return shopId + ":" + tradeKey;
    }

    @Override
    public String toString() {
        return "GlobalTradeData{" +
                "shopId='" + shopId + '\'' +
                ", tradeKey='" + tradeKey + '\'' +
                ", tradesUsed=" + tradesUsed.get() +
                ", lastResetEpoch=" + lastResetEpoch +
                ", cooldownSeconds=" + cooldownSeconds +
                '}';
    }
}
