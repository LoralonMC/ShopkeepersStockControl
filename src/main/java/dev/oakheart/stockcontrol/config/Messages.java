package dev.oakheart.stockcontrol.config;

/**
 * Constants for message keys used in config.yml.
 * Prevents typos and makes refactoring easier.
 */
public final class Messages {

    // Private constructor to prevent instantiation
    private Messages() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    // Message keys
    public static final String TRADE_LIMIT_REACHED = "trade_limit_reached";
    public static final String TRADES_REMAINING = "trades_remaining";
    public static final String COOLDOWN_ACTIVE = "cooldown_active";
    public static final String TRADE_AVAILABLE = "trade_available";
    public static final String NO_PERMISSION = "no_permission";
    public static final String PLAYER_NOT_FOUND = "player_not_found";
    public static final String TRADES_RESET = "trades_reset";
    public static final String ALL_TRADES_RESET = "all_trades_reset";
    public static final String CONFIG_RELOADED = "config_reloaded";
    public static final String CONFIG_RELOAD_FAILED = "config_reload_failed";
}
