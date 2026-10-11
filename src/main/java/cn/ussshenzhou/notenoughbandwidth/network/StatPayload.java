package cn.ussshenzhou.notenoughbandwidth.network;

import java.util.ArrayList;
import java.util.List;

import cn.ussshenzhou.notenoughbandwidth.stat.PacketKindSample;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficSnapshot;
import cpw.mods.fml.common.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/**
 * Serialises a {@link TrafficSnapshot} plus the heaviest packet kinds, so one side can show the
 * other side's view of the same link.
 *
 * @author USS_Shenzhou
 */
public class StatPayload {

    public static final int MAX_KINDS = 24;

    public static void writeSnapshot(ByteBuf buf, TrafficSnapshot snapshot) {
        buf.writeLong(snapshot.outboundRawBytes());
        buf.writeLong(snapshot.outboundWireBytes());
        buf.writeLong(snapshot.outboundFrames());
        buf.writeLong(snapshot.outboundPackets());
        buf.writeLong(snapshot.inboundRawBytes());
        buf.writeLong(snapshot.inboundWireBytes());
        buf.writeLong(snapshot.inboundFrames());
        buf.writeLong(snapshot.inboundPackets());
        buf.writeDouble(snapshot.outboundRawRate());
        buf.writeDouble(snapshot.outboundWireRate());
        buf.writeDouble(snapshot.inboundRawRate());
        buf.writeDouble(snapshot.inboundWireRate());
    }

    public static TrafficSnapshot readSnapshot(ByteBuf buf) {
        return new TrafficSnapshot(
            buf.readLong(),
            buf.readLong(),
            buf.readLong(),
            buf.readLong(),
            buf.readLong(),
            buf.readLong(),
            buf.readLong(),
            buf.readLong(),
            buf.readDouble(),
            buf.readDouble(),
            buf.readDouble(),
            buf.readDouble());
    }

    public static void writeKinds(ByteBuf buf, List<PacketKindSample> samples) {
        int count = Math.min(MAX_KINDS, samples.size());
        ByteBufUtils.writeVarInt(buf, count, 2);
        for (int i = 0; i < count; i++) {
            PacketKindSample sample = samples.get(i);
            ByteBufUtils.writeUTF8String(buf, sample.label());
            buf.writeLong(sample.count());
            buf.writeLong(sample.rawBytes());
        }
    }

    public static List<PacketKindSample> readKinds(ByteBuf buf) {
        int count = Math.min(MAX_KINDS, ByteBufUtils.readVarInt(buf, 2));
        List<PacketKindSample> samples = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            samples.add(new PacketKindSample(ByteBufUtils.readUTF8String(buf), buf.readLong(), buf.readLong()));
        }
        return samples;
    }

    /**
     * Guards against a reply that would not fit in a 1.7.10 custom payload.
     */
    public static List<PacketKindSample> trim(List<PacketKindSample> samples) {
        return samples.size() <= MAX_KINDS ? samples : new ArrayList<>(samples.subList(0, MAX_KINDS));
    }

    private StatPayload() {}
}
