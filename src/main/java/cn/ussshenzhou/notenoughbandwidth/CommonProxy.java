package cn.ussshenzhou.notenoughbandwidth;

import net.minecraft.network.NetworkManager;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * Side-independent half of the mod's lifecycle, plus the few lookups that only mean something on
 * one side.
 *
 * @author USS_Shenzhou
 */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {}

    /**
     * @return the client's connection to its server, or {@code null} when there is none, which is
     *         always the case on a dedicated server
     */
    public NetworkManager clientConnection() {
        return null;
    }
}
