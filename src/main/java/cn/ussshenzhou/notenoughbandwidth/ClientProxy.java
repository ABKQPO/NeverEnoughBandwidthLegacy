package cn.ussshenzhou.notenoughbandwidth;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.client.ClientCommandHandler;

import cn.ussshenzhou.notenoughbandwidth.client.NebKeyHandler;
import cn.ussshenzhou.notenoughbandwidth.report.NebClientCommand;
import cpw.mods.fml.common.FMLCommonHandler;
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
        NebKeyHandler keys = new NebKeyHandler();
        keys.register();
        FMLCommonHandler.instance()
            .bus()
            .register(keys);
    }

    @Override
    public NetworkManager connectionOf(INetHandler handler) {
        return handler instanceof NetHandlerPlayClient ? ((NetHandlerPlayClient) handler).getNetworkManager() : null;
    }
}
