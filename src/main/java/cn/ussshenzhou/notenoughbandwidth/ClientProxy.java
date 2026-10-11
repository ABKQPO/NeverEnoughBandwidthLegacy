package cn.ussshenzhou.notenoughbandwidth;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.client.ClientCommandHandler;

import cn.ussshenzhou.notenoughbandwidth.report.NebClientCommand;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * Client-only additions: the client side report command and the lookup of the local connection.
 *
 * @author USS_Shenzhou
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        ClientCommandHandler.instance.registerCommand(new NebClientCommand());
    }

    @Override
    public NetworkManager clientConnection() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return null;
        }
        NetHandlerPlayClient handler = minecraft.getNetHandler();
        return handler == null ? null : handler.getNetworkManager();
    }
}
