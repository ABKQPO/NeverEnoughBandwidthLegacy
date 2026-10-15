package cn.ussshenzhou.notenoughbandwidth.network;

import net.minecraft.network.NetworkManager;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecRegistry;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import cn.ussshenzhou.notenoughbandwidth.session.NebSession;
import cn.ussshenzhou.notenoughbandwidth.session.NebSessionRegistry;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Capability announcement, exchanged once per connection in both directions.
 * <p>
 * Nothing is compressed until this message has been received, which is the whole reason it exists:
 * a peer that does not run NEB never answers, so it never gets a frame it cannot read, and a
 * server with the mod installed stays joinable by clients without it. Each side also publishes
 * which backends it supports, so the pair settles on the best one they share rather than on
 * whatever the sender happens to prefer.
 *
 * @author USS_Shenzhou
 */
public class HelloMessage implements IMessage {

    private int protocolVersion;
    private int capabilityMask;
    private String label;

    public HelloMessage() {}

    public HelloMessage(String label) {
        this.protocolVersion = ModConstants.PROTOCOL_VERSION;
        this.capabilityMask = PacketCodecRegistry.capabilityMask();
        this.label = label == null ? "" : label;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        protocolVersion = ByteBufUtils.readVarInt(buf, 3);
        capabilityMask = ByteBufUtils.readVarInt(buf, 3);
        label = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeVarInt(buf, protocolVersion, 3);
        ByteBufUtils.writeVarInt(buf, capabilityMask, 3);
        ByteBufUtils.writeUTF8String(buf, label);
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public int capabilityMask() {
        return capabilityMask;
    }

    public String label() {
        return label;
    }

    /**
     * Shared by both sides. The server speaks first, so only a client has to answer.
     */
    public static class Handler implements IMessageHandler<HelloMessage, IMessage> {

        @Override
        public IMessage onMessage(HelloMessage message, MessageContext ctx) {
            boolean serverSide = ctx.side == Side.SERVER;
            NebLog.debug(
                "Received a NEB hello from {} (protocol {}, backends 0x{}).",
                message.label(),
                message.protocolVersion(),
                Integer.toHexString(message.capabilityMask()));
            NetworkManager manager = NebNetwork.managerOf(ctx);
            NebSession session = NebSessionRegistry.of(manager);
            if (session == null) {
                NebLog.warn("A NEB hello arrived on a connection with no session; staying uncompressed.");
                return null;
            }
            if (message.protocolVersion() != ModConstants.PROTOCOL_VERSION) {
                NebLog.warn(
                    "{} speaks NEB protocol {} but this side speaks {}, staying uncompressed.",
                    message.label(),
                    message.protocolVersion(),
                    ModConstants.PROTOCOL_VERSION);
                return null;
            }
            if (!serverSide) {
                NebNetwork.sendHello(manager, "client");
            }
            session.peerLabel(serverSide ? NebNetwork.playerNameOf(ctx) : "server");
            boolean allowStateful = !serverSide || !NebNetwork.deniesContext(ctx);
            PacketCodecType agreed = PacketCodecRegistry.negotiate(message.capabilityMask(), allowStateful);
            session.enableOutbound(agreed, message.protocolVersion());
            return null;
        }
    }
}
