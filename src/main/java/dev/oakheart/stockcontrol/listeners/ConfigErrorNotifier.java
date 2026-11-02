package dev.oakheart.stockcontrol.listeners;

import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Tells staff on join when the trades.yml the plugin is running on has validation errors, so a
 * broken edit isn't only a block of SEVERE lines in a console nobody was watching at startup.
 */
public class ConfigErrorNotifier implements Listener {

    private final ShopkeepersStockControl plugin;

    public ConfigErrorNotifier(ShopkeepersStockControl plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (plugin.getConfigManager().getConfigErrorCount() == 0
                || !player.hasPermission("shopkeepersstock.reload")) {
            return;
        }
        // After the join chat, so it isn't buried.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            int count = plugin.getConfigManager().getConfigErrorCount();
            if (count > 0 && player.isConnected()) {
                plugin.getMessageManager().sendCommand(player, "config-errors-on-join",
                        Placeholder.unparsed("count", String.valueOf(count)));
            }
        }, 60L);
    }
}
