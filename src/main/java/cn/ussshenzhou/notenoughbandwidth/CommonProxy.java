package cn.ussshenzhou.notenoughbandwidth;

import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {}

    public NetworkManager connectionOf(INetHandler handler) {
        return null;
    }
}
