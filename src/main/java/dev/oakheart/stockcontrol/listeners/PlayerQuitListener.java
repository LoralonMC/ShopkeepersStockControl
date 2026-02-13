package dev.oakheart.stockcontrol.listeners;

import dev.oakheart.stockcontrol.managers.PacketManager;
import dev.oakheart.stockcontrol.managers.TradeDataManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Handles player quit events to evict cache, flush data, and clean up shop mappings.
 */
public class PlayerQuitListener implements Listener {

    private final TradeDataManager tradeDataManager;
    private final PacketManager packetManager;

    public PlayerQuitListener(TradeDataManager tradeDataManager, PacketManager packetManager) {
        this.tradeDataManager = tradeDataManager;
        this.packetManager = packetManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        var playerId = event.getPlayer().getUniqueId();
        tradeDataManager.evictPlayer(playerId);
        packetManager.removeShopMapping(playerId);
    }
}
