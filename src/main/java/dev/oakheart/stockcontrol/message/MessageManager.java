package dev.oakheart.stockcontrol.message;

import dev.oakheart.stockcontrol.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Manages all player-facing messages with MiniMessage formatting.
 * Uses TagResolver (Placeholder.unparsed()) for dynamic content replacement.
 * Supports per-message display modes (chat/action_bar).
 */
public class MessageManager {

    // Message key constants (player-facing trade feedback only)
    public static final String TRADE_LIMIT_REACHED = "trade_limit_reached";
    public static final String TRADES_REMAINING = "trades_remaining";
    public static final String COOLDOWN_ACTIVE = "cooldown_active";

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
        Component component = miniMessage.deserialize(message, resolver);

        if ("action_bar".equalsIgnoreCase(configManager.getMessageDisplay(messageKey))) {
            player.sendActionBar(component);
        } else {
            player.sendMessage(component);
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
     * Gets the MiniMessage instance for inline message formatting.
     */
    public MiniMessage getMiniMessage() {
        return miniMessage;
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
