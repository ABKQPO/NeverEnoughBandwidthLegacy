package cn.ussshenzhou.notenoughbandwidth.pipeline;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.server.S3FPacketCustomPayload;

import com.google.common.collect.BiMap;

public class CustomPayloadLookup {

    public static final int UNRESOLVED = -1;

    private int resolved = UNRESOLVED;

    /**
     * @param protocol the id to packet class table of the direction of interest
     * @return the custom payload packet id, or {@link #UNRESOLVED} if this table has none yet
     */
    public int idOf(BiMap<Integer, Class<? extends Packet>> protocol) {
        if (resolved != UNRESOLVED) {
            return resolved;
        }
        if (protocol == null) {
            return UNRESOLVED;
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
}
