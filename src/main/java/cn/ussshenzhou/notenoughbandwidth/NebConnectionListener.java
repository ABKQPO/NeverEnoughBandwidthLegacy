package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.network.NebNetwork;
import cn.ussshenzhou.notenoughbandwidth.session.NebSessionRegistry;
import cn.ussshenzhou.notenoughbandwidth.stat.RemoteStats;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/**
 * Starts the capability handshake at the first moment a custom payload can legally travel.
 * <p>
 * FML posts {@code ServerConnectionFromClientEvent} from inside its own handshake completion, just
 * before the player is initialised, with the connection already moved into the play state. Sending
 * the hello there rather than on player login means the initial terrain and inventory burst is
 * already compressed, which is the single largest thing a joining client downloads.
 *
 * @author USS_Shenzhou
 */
public class NebConnectionListener {

    @SubscribeEvent
    public void onClientConnected(FMLNetworkEvent.ServerConnectionFromClientEvent event) {
        if (event.isLocal || NebSessionRegistry.of(event.manager) == null) {
            return;
        }
        NebNetwork.greet(event.manager);
    }

    @SubscribeEvent
    public void onClientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        RemoteStats.clear();
    }
}
