package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecRegistry;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cn.ussshenzhou.notenoughbandwidth.network.NebNetwork;
import cn.ussshenzhou.notenoughbandwidth.report.NebCommand;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

/**
 * Mod entry point.
 * <p>
 * {@code acceptableRemoteVersions = "*"} is intentional and load bearing: the mod must not force
 * itself onto the other end of a connection. Compression is negotiated in band instead, so a
 * server running NEB stays joinable by clients without it and vice versa.
 *
 * @author USS_Shenzhou
 */
@Mod(
    modid = ModConstants.MOD_ID,
    name = ModConstants.MOD_NAME,
    version = Tags.VERSION,
    guiFactory = "cn.ussshenzhou.notenoughbandwidth.config.NebGuiFactory",
    acceptableRemoteVersions = "*",
    acceptedMinecraftVersions = "[1.7.10]")
public class NotEnoughBandwidth {

    @SidedProxy(
        clientSide = "cn.ussshenzhou.notenoughbandwidth.ClientProxy",
        serverSide = "cn.ussshenzhou.notenoughbandwidth.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (!NebConfig.enabled) {
            NebLog.info("Disabled by configuration; the packet stream is left untouched.");
            return;
        }
        PacketCodecRegistry.bootstrap();
        NebNetwork.register();
        FMLCommonHandler.instance()
            .bus()
            .register(new NebConnectionListener());
        proxy.preInit(event);
        NebLog.info(
            "Ready, preferred backend is {} ({}).",
            PacketCodecRegistry.preferred()
                .configName(),
            PacketCodecRegistry.describe(PacketCodecRegistry.preferred()));
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new NebCommand());
    }
}
