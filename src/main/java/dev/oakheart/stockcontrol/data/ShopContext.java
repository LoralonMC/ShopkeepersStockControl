package dev.oakheart.stockcontrol.data;

/**
 * Represents the context of a shop that a player has open.
 * Includes TTL (time-to-live) for automatic cleanup of stale entries.
 */
public record ShopContext(
        String shopId,      // Shopkeeper's unique ID
        int entityId,       // NPC entity ID
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

    /**
     * Gets remaining time until expiry in milliseconds.
     *
     * @return milliseconds remaining, or 0 if expired
     */
    public long getRemainingTime() {
        long remaining = expiryTime - System.currentTimeMillis();
        return Math.max(0, remaining);
    }
}
