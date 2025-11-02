package dev.oakheart.stockcontrol.listeners;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMerchantOffers;
import dev.oakheart.stockcontrol.ShopkeepersStockControl;
import dev.oakheart.stockcontrol.managers.PacketManager;
import dev.oakheart.stockcontrol.managers.TradeDataManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

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

            // Let PacketManager modify the packet
            packetManager.modifyMerchantPacket(player, packet);

            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Intercepted and modified MERCHANT_OFFERS for " + player.getName());
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Error modifying merchant packet for " + player.getName() + ": " + e.getMessage());
            if (plugin.getConfigManager().isDebugMode()) {
                e.printStackTrace();
            }
        }
    }
}
