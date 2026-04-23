package dev.oakheart.stockcontrol.listeners;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSelectTrade;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMerchantOffers;
import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.managers.PacketManager;
import dev.oakheart.stockcontrol.managers.TradeDataManager;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.logging.Level;

/**
 * Listens for merchant offer packets and modifies them to show per-player stock.
 * CRITICAL: PacketEvents can call this on non-main thread, so be thread-safe!
 */
public class PacketListener extends PacketListenerAbstract {

    private final ShopkeepersStockControl plugin;
    private final PacketManager packetManager;
    private final TradeDataManager tradeDataManager;

    public PacketListener(ShopkeepersStockControl plugin, PacketManager packetManager,
                          TradeDataManager tradeDataManager) {
        this.plugin = plugin;
        this.packetManager = packetManager;
        this.tradeDataManager = tradeDataManager;
    }

    /**
     * Intercepts outgoing packets to the client.
     * We're looking for MERCHANT_OFFERS (villager trading UI).
     */
    @Override
    public void onPacketSend(PacketSendEvent event) {
        // Check if this is a merchant offers packet
        if (event.getPacketType() != PacketType.Play.Server.MERCHANT_OFFERS) {
            return;
        }

        // Get the player (must be a Player)
        Object playerObj = event.getPlayer();
        if (!(playerObj instanceof Player player)) {
            return;
        }

        try {
            // Wrap the packet
            WrapperPlayServerMerchantOffers packet = new WrapperPlayServerMerchantOffers(event);

            // Cache original offers before modification (only on first packet after shop open)
            if (packetManager.shouldCachePacket(player.getUniqueId())) {
                packetManager.cachePacketData(player.getUniqueId(), packet);
            }

            // Let PacketManager modify the packet. Changes made via the wrapper's setters
            // don't reach the outgoing buffer unless we flag the event for re-encoding.
            packetManager.modifyMerchantPacket(player, packet);
            event.markForReEncode(true);

            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Intercepted and modified MERCHANT_OFFERS for " + player.getName());
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error modifying merchant packet for " + player.getName(), e);
        }
    }

    /**
     * Intercepts inbound trade-selection packets and remaps the client's UI slot back to the
     * Shopkeepers source slot for pools-enabled shops. Required because we reorder/filter the
     * outgoing offer list, so the client's "slot 4" no longer matches Shopkeepers' "slot 4".
     */
    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.SELECT_TRADE) {
            return;
        }

        Object playerObj = event.getPlayer();
        if (!(playerObj instanceof Player player)) {
            return;
        }

        List<Integer> mapping = packetManager.getUiToSourceMap(player.getUniqueId());
        if (mapping == null) {
            // No rebuilt UI open — legacy path, indices already match.
            return;
        }

        try {
            WrapperPlayClientSelectTrade packet = new WrapperPlayClientSelectTrade(event);
            int uiSlot = packet.getSlot();
            if (uiSlot < 0 || uiSlot >= mapping.size()) {
                plugin.getLogger().warning("Player " + player.getName() + " selected UI slot "
                        + uiSlot + " which is outside the rebuilt mapping (size " + mapping.size() + ")");
                return;
            }
            int sourceSlot = mapping.get(uiSlot);
            if (sourceSlot != uiSlot) {
                packet.setSlot(sourceSlot);
                event.markForReEncode(true);
                if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().info("Remapped SELECT_TRADE for " + player.getName()
                            + ": UI slot " + uiSlot + " → source slot " + sourceSlot);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error remapping SELECT_TRADE for " + player.getName(), e);
        }
    }
}
