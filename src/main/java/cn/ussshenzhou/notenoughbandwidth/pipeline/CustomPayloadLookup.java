package cn.ussshenzhou.notenoughbandwidth.pipeline;

import java.nio.charset.StandardCharsets;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.server.S3FPacketCustomPayload;

import com.google.common.collect.BiMap;

import cn.ussshenzhou.notenoughbandwidth.util.NebVarInt;
import io.netty.buffer.ByteBuf;

public class CustomPayloadLookup {

    public static final int UNRESOLVED = -1;

    private static final int MAX_CHANNEL_BYTES = 80;

    private int resolved = UNRESOLVED;

    /**
     * @param protocol the id to packet class table of the direction of interest
     * @return the custom payload packet id, or {@link #UNRESOLVED} if this table has none yet
     */
    public int idOf(BiMap<Integer, Class<? extends Packet>> protocol) {
        if (protocol == null) {
            return UNRESOLVED;
        }
        if (resolved != UNRESOLVED) {
            Class<? extends Packet> current = protocol.get(resolved);
            if (current == S3FPacketCustomPayload.class || current == C17PacketCustomPayload.class) {
                return resolved;
            }
            resolved = UNRESOLVED;
        }
        Integer clientbound = protocol.inverse()
            .get(S3FPacketCustomPayload.class);
        if (clientbound != null) {
            resolved = clientbound;
            return resolved;
        }
        Integer serverbound = protocol.inverse()
            .get(C17PacketCustomPayload.class);
        if (serverbound != null) {
            resolved = serverbound;
        }
        return resolved;
    }

    /**
     * Reads the channel name from one encoded CustomPayload body.
     *
     * @param body      complete encoded packet body
     * @param payloadId packet id assigned to CustomPayload in this direction
     * @return the channel name, or {@code null} when the body is another packet
     */
    public String channelOf(ByteBuf body, int payloadId) {
        if (payloadId == UNRESOLVED) {
            return null;
        }
        NebVarInt.Cursor cursor = new NebVarInt.Cursor();
        cursor.at(body.readerIndex());
        if (!NebVarInt.readAt(body, cursor) || cursor.value != payloadId || !NebVarInt.readAt(body, cursor)) {
            return null;
        }
        int length = cursor.value;
        if (length <= 0 || length > MAX_CHANNEL_BYTES || length > body.writerIndex() - cursor.index) {
            return null;
        }
        byte[] bytes = new byte[length];
        body.getBytes(cursor.index, bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
