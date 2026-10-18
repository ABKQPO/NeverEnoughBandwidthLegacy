package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.capture.DatasetRecorder;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecRegistry;
import cn.ussshenzhou.notenoughbandwidth.codec.zstd.BandwidthDictionary;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cn.ussshenzhou.notenoughbandwidth.network.NebNetwork;
import cn.ussshenzhou.notenoughbandwidth.report.NebCommand;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;

@Mod(
    modid = Tags.MODID,
    name = Tags.MODNAME,
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
        BandwidthDictionary.reset();
        event.registerServerCommand(new NebCommand());
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        DatasetRecorder.global()
            .stop();
        BandwidthDictionary.reset();
    }
}
