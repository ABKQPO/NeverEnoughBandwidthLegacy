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
        return labelsByChannel.computeIfAbsent(channel, c -> name + '[' + c + ']');
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
