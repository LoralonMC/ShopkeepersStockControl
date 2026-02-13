package dev.oakheart.stockcontrol.data;

/**
 * Represents the context of a shop that a player has open.
 * Includes TTL (time-to-live) for automatic cleanup of stale entries.
 */
public record ShopContext(
        String shopId,      // Shopkeeper's unique ID
        long expiryTime     // When this context expires (milliseconds)
) {
    /**
     * Checks if this context has expired.
     *
     * @return true if expired, false otherwise
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > expiryTime;
    }
}
