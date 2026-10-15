package cn.ussshenzhou.notenoughbandwidth.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidth;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecRegistry;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public class NebNetwork {

    private static final int HELLO = 0;
    private static final int STAT_QUERY = 1;
    private static final int STAT_REPORT = 2;

    private static SimpleNetworkWrapper channel;

    public static void register() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(ModConstants.CHANNEL);
        channel.registerMessage(HelloMessage.Handler.class, HelloMessage.class, HELLO, Side.CLIENT);
        channel.registerMessage(HelloMessage.Handler.class, HelloMessage.class, HELLO, Side.SERVER);
        channel.registerMessage(StatQueryMessage.Handler.class, StatQueryMessage.class, STAT_QUERY, Side.SERVER);
        channel.registerMessage(StatReportMessage.Handler.class, StatReportMessage.class, STAT_REPORT, Side.CLIENT);
    }

    public static void sendHello(NetworkManager manager, String label) {
        sendHello(manager, label, null);
    }

    public static void sendHello(NetworkManager manager, String label, PacketCodecType selectedCodec) {
        if (channel == null || manager == null || !manager.isChannelOpen()) {
            NebLog.warn(
                "Cannot send the NEB hello to {}: the connection is not ready.",
                manager == null ? "null" : manager.getSocketAddress());
            return;
        }
        try {
            manager.scheduleOutboundPacket(channel.getPacketFrom(new HelloMessage(label, selectedCodec)));
            NebLog.debug("Sent the NEB hello to {}.", manager.getSocketAddress());
        } catch (Throwable t) {
            NebLog.error(
                "Failed to send the NEB hello to {}; that connection stays uncompressed.",
                manager.getSocketAddress(),
                t);
        }
    }

    public static void greet(NetworkManager manager) {
        sendHello(manager, "server", PacketCodecRegistry.preferred());
    }

    public static void queryServer() {
        if (channel != null) {
            channel.sendToServer(new StatQueryMessage());
        }
    }

    public static NetworkManager managerOf(MessageContext ctx) {
        if (ctx.side == Side.SERVER) {
            return ctx.getServerHandler().netManager;
        }
        return NotEnoughBandwidth.proxy.connectionOf(ctx.netHandler);
    }

    public static String playerNameOf(MessageContext ctx) {
        EntityPlayerMP player = ctx.getServerHandler().playerEntity;
        return player == null ? "unknown" : player.getCommandSenderName();
    }

    public static boolean deniesContext(MessageContext ctx) {
        EntityPlayerMP player = ctx.getServerHandler().playerEntity;
        if (player == null) {
            return false;
        }
        String uuid = player.getGameProfile() == null || player.getGameProfile()
            .getId() == null ? ""
                : player.getGameProfile()
                    .getId()
                    .toString();
        return NebConfig.deniesContext(player.getCommandSenderName(), uuid);
    }

    public static boolean isOperator(EntityPlayerMP player) {
        MinecraftServer server = MinecraftServer.getServer();
        return server != null && server.getConfigurationManager()
            .func_152596_g(player.getGameProfile());
    }

    private NebNetwork() {}
}
