package dev.oakheart.stockcontrol.message;

import dev.oakheart.stockcontrol.config.ConfigManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * Manages all player-facing messages with MiniMessage formatting.
 * Uses TagResolver (Placeholder.unparsed()) for dynamic content replacement.
 * Supports per-message display modes (chat/action_bar).
 */
public class MessageManager {

    // Player trade feedback
    public static final String TRADE_LIMIT_REACHED = "trade-limit-reached";
    public static final String TRADES_REMAINING = "trades-remaining";
    public static final String COOLDOWN_ACTIVE = "cooldown-active";

    // Shared errors
    public static final String ERROR_PLAYER_NOT_FOUND = "error-player-not-found";
    public static final String ERROR_SHOP_NOT_FOUND = "error-shop-not-found";
    public static final String ERROR_TRADE_NOT_FOUND = "error-trade-not-found";
    public static final String ERROR_NOT_SHARED = "error-not-shared";

    // Help
    public static final String HELP = "help";

    // Reload
    public static final String RELOAD_START = "reload-start";
    public static final String RELOAD_SUCCESS = "reload-success";
    public static final String RELOAD_FAILED = "reload-failed";

    // Debug
    public static final String DEBUG_ENABLED = "debug-enabled";
    public static final String DEBUG_DISABLED = "debug-disabled";

    // Cleanup
    public static final String CLEANUP_START = "cleanup-start";
    public static final String CLEANUP_COMPLETE = "cleanup-complete";

    // Reset
    public static final String RESET_PLAYER = "reset-player";
    public static final String RESET_PLAYER_SHOP = "reset-player-shop";
    public static final String RESET_PLAYER_SHOP_TRADE = "reset-player-shop-trade";

    // Restock
    public static final String RESTOCK_SHOP = "restock-shop";
    public static final String RESTOCK_TRADE = "restock-trade";

    // Check (no-data warnings)
    public static final String CHECK_NO_DATA = "check-no-data";
    public static final String CHECK_NO_DATA_SHOP = "check-no-data-shop";
    public static final String CHECK_NO_DATA_TRADE = "check-no-data-trade";

    // Check (display output)
    public static final String CHECK_HEADER = "check-header";
    public static final String CHECK_SHOP = "check-shop";
    public static final String CHECK_TRADE = "check-trade";
    public static final String CHECK_USED = "check-used";
    public static final String CHECK_REMAINING = "check-remaining";
    public static final String CHECK_COOLDOWN_NEVER = "check-cooldown-never";
    public static final String CHECK_COOLDOWN_ACTIVE = "check-cooldown-active";
    public static final String CHECK_COOLDOWN_READY = "check-cooldown-ready";

    // Info (display output)
    public static final String INFO_HEADER = "info-header";
    public static final String INFO_ID = "info-id";
    public static final String INFO_ENABLED = "info-enabled";
    public static final String INFO_STOCK_MODE = "info-stock-mode";
    public static final String INFO_COOLDOWN_MODE = "info-cooldown-mode";
    public static final String INFO_RESET_TIME = "info-reset-time";
    public static final String INFO_RESET_DAY = "info-reset-day";
    public static final String INFO_PER_PLAYER_CAP = "info-per-player-cap";
    public static final String INFO_TRADES_COUNT = "info-trades-count";
    public static final String INFO_TRADE_ENTRY = "info-trade-entry";

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final ConfigManager configManager;

    public MessageManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * Gets a raw message string from config by key.
     *
     * @param key The message key
     * @return The raw message string, or null if not found
     */
    public String getRawMessage(String key) {
        return configManager.getMessage(key);
    }

    /**
     * Sends a config-based message to a player, respecting the display mode setting.
     * Uses TagResolver (Placeholder.unparsed()) for safe placeholder replacement.
     *
     * @param player The player to send to
     * @param messageKey The message key in config
     * @param placeholders Map of placeholder names to values
     */
    public void sendFeedback(Player player, String messageKey, Map<String, String> placeholders) {
        String message = configManager.getMessage(messageKey);
        if (message == null || message.isBlank()) return;

        TagResolver resolver = buildResolver(placeholders);

        if ("action_bar".equalsIgnoreCase(configManager.getMessageDisplay(messageKey))) {
            player.sendActionBar(miniMessage.deserialize(message, resolver));
        } else {
            player.sendMessage(miniMessage.deserialize(message, resolver));
        }
    }

    /**
     * Sends a config-based message to a player without placeholders.
     */
    public void sendFeedback(Player player, String messageKey) {
        sendFeedback(player, messageKey, Map.of());
    }

    /**
     * Sends a config-based message to a command sender (always via chat).
     */
    public void send(CommandSender sender, String messageKey, Map<String, String> placeholders) {
        String message = configManager.getMessage(messageKey);
        if (message == null || message.isBlank()) return;

        TagResolver resolver = buildResolver(placeholders);
        sender.sendMessage(miniMessage.deserialize(message, resolver));
    }

    /**
     * Sends a config-based message to a command sender without placeholders.
     */
    public void send(CommandSender sender, String messageKey) {
        send(sender, messageKey, Map.of());
    }

    /**
     * Sends a multiline message (YAML list) to a command sender.
     * Each list item is deserialized and sent as a separate chat message.
     *
     * @param sender The command sender
     * @param messageKey The message key in config (text: is a YAML list)
     * @param placeholders Map of placeholder names to values
     */
    public void sendMultiline(CommandSender sender, String messageKey, Map<String, String> placeholders) {
        List<String> lines = configManager.getMessageList(messageKey);
        if (lines == null || lines.isEmpty()) return;

        TagResolver resolver = buildResolver(placeholders);
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                sender.sendMessage(miniMessage.deserialize(line, resolver));
            }
        }
    }

    /**
     * Sends a multiline message (YAML list) to a command sender without placeholders.
     */
    public void sendMultiline(CommandSender sender, String messageKey) {
        sendMultiline(sender, messageKey, Map.of());
    }

    /**
     * Builds a TagResolver from a map of placeholder names to values.
     * Uses Placeholder.unparsed() which automatically prevents MiniMessage injection
     * from player-provided values (no need for escapeTags).
     */
    private TagResolver buildResolver(Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return TagResolver.empty();
        }

        TagResolver.Builder builder = TagResolver.builder();
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            builder.resolver(Placeholder.unparsed(entry.getKey(), entry.getValue()));
        }
        return builder.build();
    }
}
