package cn.ussshenzhou.notenoughbandwidth.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidth;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * The mod's FML channel: a capability handshake and a statistics request, nothing else.
 * <p>
 * This channel deliberately carries no bulk data. The compression itself happens below the packet
 * layer, so there is nothing for it to transport.
 *
 * @author USS_Shenzhou
 */
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

    /**
     * Sends a hello down a connection directly, bypassing the usual addressing helpers.
     * <p>
     * Neither of the convenient routes works at handshake time. FML's reply target is declared
     * server-only, so a client handler cannot answer by returning a message, and the client's
     * "send to server" helper resolves the connection through the player entity, which does not
     * exist yet. Writing to the connection we were handed avoids both problems and works
     * identically on either side, because FML's dispatcher picks the right custom payload packet
     * for its own direction.
     */
    public static void sendHello(NetworkManager manager, String label) {
        if (channel == null || manager == null || !manager.isChannelOpen()) {
            NebLog.warn(
                "Cannot send the NEB hello to {}: the connection is not ready.",
                manager == null ? "null" : manager.getSocketAddress());
            return;
        }
        try {
            manager.scheduleOutboundPacket(channel.getPacketFrom(new HelloMessage(label)));
            NebLog.debug("Sent the NEB hello to {}.", manager.getSocketAddress());
        } catch (Throwable t) {
            NebLog.error(
                "Failed to send the NEB hello to {}; that connection stays uncompressed.",
                manager.getSocketAddress(),
                t);
        }
    }

    /**
     * Sends the server's hello down a connection that does not have a usable player handler yet,
     * which is the earliest moment a custom payload can travel. Doing it this early means the
     * initial terrain burst is already compressed.
     */
    public static void greet(NetworkManager manager) {
        sendHello(manager, "server");
    }

    public static void queryServer() {
        if (channel != null) {
            channel.sendToServer(new StatQueryMessage());
        }
    }

    /**
     * @return the connection a received message arrived on, or {@code null} if it has gone away
     */
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

    /**
     * @return whether the configuration opts this player out of a history-keeping backend
     */
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
