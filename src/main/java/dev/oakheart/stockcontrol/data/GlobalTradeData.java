package dev.oakheart.stockcontrol.data;

/**
 * Represents global trade stock data for a shared-mode shop.
 * Tracks the combined stock pool that all players draw from.
 */
public class GlobalTradeData {
    private final String shopId;
    private final String tradeKey;
    private volatile int tradesUsed;
    private volatile long lastResetEpoch;
    private volatile int cooldownSeconds;

    public GlobalTradeData(String shopId, String tradeKey,
                           int tradesUsed, long lastResetEpoch, int cooldownSeconds) {
        this.shopId = shopId;
        this.tradeKey = tradeKey;
        this.tradesUsed = tradesUsed;
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
        return tradesUsed;
    }

    public long getLastResetEpoch() {
        return lastResetEpoch;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setTradesUsed(int tradesUsed) {
        this.tradesUsed = tradesUsed;
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
                ", tradesUsed=" + tradesUsed +
                ", lastResetEpoch=" + lastResetEpoch +
                ", cooldownSeconds=" + cooldownSeconds +
                '}';
    }
}
