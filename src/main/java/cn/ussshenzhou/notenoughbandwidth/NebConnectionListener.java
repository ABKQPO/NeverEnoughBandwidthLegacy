package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.network.NebNetwork;
import cn.ussshenzhou.notenoughbandwidth.session.NebSessionRegistry;
import cn.ussshenzhou.notenoughbandwidth.stat.RemoteStats;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

public class NebConnectionListener {

    @SubscribeEvent
    public void onClientConnected(FMLNetworkEvent.ServerConnectionFromClientEvent event) {
        if (event.isLocal) {
            return;
        }
        if (NebSessionRegistry.of(event.manager) == null) {
            NebLog.warn("No NEB session for {}; that connection stays uncompressed.", event.manager.getSocketAddress());
            return;
        }
        NebNetwork.greet(event.manager);
    }

    @SubscribeEvent
    public void onClientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        RemoteStats.clear();
    }
}
