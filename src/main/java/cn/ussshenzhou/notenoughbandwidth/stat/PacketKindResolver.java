package cn.ussshenzhou.notenoughbandwidth.stat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.server.S3FPacketCustomPayload;

import com.google.common.collect.BiMap;

import cn.ussshenzhou.notenoughbandwidth.util.NebVarInt;
import io.netty.buffer.ByteBuf;

/**
 * Turns an encoded packet body back into a human readable label for the report.
 * <p>
 * The body is read with absolute indices only, so the buffer handed to the rest of the pipeline is
 * untouched. Custom payload packets get their mod channel appended, which is the whole point: on a
 * large pack almost everything interesting travels as a custom payload, and "S3FPacketCustomPayload"
 * on its own would tell you nothing about which mod to go and fix.
 * <p>
 * One instance belongs to one direction of one connection and is only ever touched from that
 * connection's event loop, so the caches need no synchronisation.
 *
 * @author USS_Shenzhou
 */
public class PacketKindResolver {

    private static final String UNKNOWN = "unknown";
    private static final int MAX_CHANNEL_BYTES = 64;

    private final NebVarInt.Cursor cursor = new NebVarInt.Cursor();
    private final Map<Integer, String> namesById = new HashMap<>();
    private final Map<Integer, Boolean> customPayloadById = new HashMap<>();
    private final Map<String, String> labelsByChannel = new HashMap<>();

    /**
     * @param protocol the id to packet class map of the direction this body travels in
     * @param body     a complete encoded packet body, reader index at the varint packet id
     */
    public String resolve(BiMap<Integer, Class<? extends Packet>> protocol, ByteBuf body) {
        cursor.at(body.readerIndex());
        if (!NebVarInt.readAt(body, cursor)) {
            return UNKNOWN;
        }
        int packetId = cursor.value;
        String name = nameOf(protocol, packetId);
        if (!Boolean.TRUE.equals(customPayloadById.get(packetId))) {
            return name;
        }
        String channel = readChannel(body, cursor.index);
        if (channel == null) {
            return name;
        }
        String cached = labelsByChannel.get(channel);
        if (cached == null) {
            cached = name + '[' + channel + ']';
            labelsByChannel.put(channel, cached);
        }
        return cached;
    }

    private String nameOf(BiMap<Integer, Class<? extends Packet>> protocol, int packetId) {
        String known = namesById.get(packetId);
        if (known != null) {
            return known;
        }
        Class<? extends Packet> type = protocol == null ? null : protocol.get(packetId);
        String name = type == null ? "id:" + packetId : type.getSimpleName();
        namesById.put(packetId, name);
        customPayloadById.put(packetId, type == C17PacketCustomPayload.class || type == S3FPacketCustomPayload.class);
        return name;
    }

    /**
     * Both custom payload packets start with a Minecraft string, that is a varint byte length
     * followed by UTF-8 bytes.
     */
    private String readChannel(ByteBuf body, int index) {
        cursor.at(index);
        if (!NebVarInt.readAt(body, cursor)) {
            return null;
        }
        int length = cursor.value;
        if (length <= 0 || length > MAX_CHANNEL_BYTES || cursor.index + length > body.writerIndex()) {
            return null;
        }
        byte[] raw = new byte[length];
        body.getBytes(cursor.index, raw);
        return new String(raw, StandardCharsets.UTF_8);
    }
}
