package cn.ussshenzhou.notenoughbandwidth.pipeline;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S40PacketDisconnect;

import com.google.common.collect.BiMap;

import cn.ussshenzhou.notenoughbandwidth.util.NebVarInt;
import io.netty.buffer.ByteBuf;

public class PacketBypassPolicy {

    private final Set<Integer> immediatePacketIds = new HashSet<>();
    private final Set<String> immediateChannels = new HashSet<>();
    private final CustomPayloadLookup payloadLookup;

    public PacketBypassPolicy(Collection<String> channels, CustomPayloadLookup payloadLookup) {
        if (channels != null) {
            for (String channel : channels) {
                if (channel != null && !channel.isEmpty()) {
                    immediateChannels.add(channel);
                }
            }
        }
        this.payloadLookup = payloadLookup;
    }

    public void refresh(BiMap<Integer, Class<? extends Packet>> protocol) {
        if (protocol == null) {
            return;
        }
        immediatePacketIds.clear();
        addPacketId(protocol, S00PacketKeepAlive.class);
        addPacketId(protocol, S40PacketDisconnect.class);
        addPacketId(protocol, S07PacketRespawn.class);
        addPacketId(protocol, C00PacketKeepAlive.class);
    }

    public boolean shouldBypass(ByteBuf body, int payloadId) {
        NebVarInt.Cursor cursor = new NebVarInt.Cursor();
        cursor.at(body.readerIndex());
        if (!NebVarInt.readAt(body, cursor)) {
            return false;
        }
        if (immediatePacketIds.contains(cursor.value)) {
            return true;
        }
        String channel = payloadLookup.channelOf(body, payloadId);
        return channel != null && immediateChannels.contains(channel);
    }

    private void addPacketId(BiMap<Integer, Class<? extends Packet>> protocol, Class<? extends Packet> packetType) {
        Integer id = protocol.inverse()
            .get(packetType);
        if (id != null) {
            immediatePacketIds.add(id);
        }
    }
}
