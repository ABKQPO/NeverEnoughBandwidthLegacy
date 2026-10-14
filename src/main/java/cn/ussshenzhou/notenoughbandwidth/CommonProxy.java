package cn.ussshenzhou.notenoughbandwidth;

import net.minecraft.network.INetHandler;
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
     * Resolves the connection a received message arrived on.
     * <p>
     * This goes through the proxy rather than straight to the handler because the client packet
     * handler type does not exist on a dedicated server, and because asking Minecraft for "the"
     * connection does not work: the client's accessor is backed by the player entity, which is
     * still null while the capability handshake is running.
     *
     * @return the connection, or {@code null} when this side cannot own one
     */
    public NetworkManager connectionOf(INetHandler handler) {
        return null;
    }
}
