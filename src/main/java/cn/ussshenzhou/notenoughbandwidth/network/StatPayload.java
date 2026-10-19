package cn.ussshenzhou.notenoughbandwidth.network;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import cn.ussshenzhou.notenoughbandwidth.stat.PacketKindSample;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficPoint;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficSnapshot;
import cpw.mods.fml.common.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.CorruptedFrameException;

public class StatPayload {

    public static final int MAX_KINDS = 24;
    public static final int MAX_TIMELINE_POINTS = 60;
    private static final int MAX_KIND_LABEL_BYTES = 256;

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
        try {
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
        } catch (IndexOutOfBoundsException | IllegalArgumentException invalid) {
            throw new CorruptedFrameException("NEB: invalid traffic snapshot", invalid);
        }
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
        try {
            int count = ByteBufUtils.readVarInt(buf, 2);
            if (count < 0 || count > MAX_KINDS) {
                throw new CorruptedFrameException("NEB: invalid packet profile length " + count);
            }
            List<PacketKindSample> samples = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                samples.add(new PacketKindSample(readLabel(buf), buf.readLong(), buf.readLong()));
            }
            return samples;
        } catch (IndexOutOfBoundsException | IllegalArgumentException invalid) {
            throw new CorruptedFrameException("NEB: invalid packet profile", invalid);
        }
    }

    public static List<PacketKindSample> trim(List<PacketKindSample> samples) {
        return samples.size() <= MAX_KINDS ? samples : new ArrayList<>(samples.subList(0, MAX_KINDS));
    }

    public static void writeTimeline(ByteBuf buf, List<TrafficPoint> points) {
        int start = Math.max(0, points.size() - MAX_TIMELINE_POINTS);
        int count = points.size() - start;
        ByteBufUtils.writeVarInt(buf, count, 2);
        long previousTimestamp = 0L;
        for (int i = start; i < points.size(); i++) {
            TrafficPoint point = points.get(i);
            writeVarLong(buf, point.timestampMillis() - previousTimestamp);
            writeVarLong(buf, point.inboundRawBytes());
            writeVarLong(buf, point.inboundWireBytes());
            writeVarLong(buf, point.outboundRawBytes());
            writeVarLong(buf, point.outboundWireBytes());
            previousTimestamp = point.timestampMillis();
        }
    }

    public static List<TrafficPoint> readTimeline(ByteBuf buf) {
        if (!buf.isReadable()) {
            return Collections.emptyList();
        }
        int count = ByteBufUtils.readVarInt(buf, 2);
        if (count < 0 || count > MAX_TIMELINE_POINTS) {
            throw new CorruptedFrameException("NEB: invalid traffic timeline length " + count);
        }
        List<TrafficPoint> points = new ArrayList<>(count);
        long timestamp = 0L;
        for (int i = 0; i < count; i++) {
            timestamp = safeAdd(timestamp, readVarLong(buf));
            points.add(
                new TrafficPoint(timestamp, readVarLong(buf), readVarLong(buf), readVarLong(buf), readVarLong(buf)));
        }
        return points;
    }

    private static void writeVarLong(ByteBuf buf, long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("NEB: timeline values cannot be negative");
        }
        while ((value & ~0x7FL) != 0L) {
            buf.writeByte((int) value & 0x7F | 0x80);
            value >>>= 7;
        }
        buf.writeByte((int) value);
    }

    private static long readVarLong(ByteBuf buf) {
        long value = 0L;
        for (int shift = 0; shift < 64; shift += 7) {
            if (!buf.isReadable()) {
                throw new CorruptedFrameException("NEB: truncated traffic timeline");
            }
            int part = buf.readUnsignedByte();
            if (shift == 63 && part != 0) {
                throw new CorruptedFrameException("NEB: oversized traffic timeline value");
            }
            value |= (long) (part & 0x7F) << shift;
            if ((part & 0x80) == 0) {
                return value;
            }
        }
        throw new CorruptedFrameException("NEB: oversized traffic timeline value");
    }

    private static String readLabel(ByteBuf buf) {
        int length = ByteBufUtils.readVarInt(buf, 2);
        if (length <= 0 || length > MAX_KIND_LABEL_BYTES || length > buf.readableBytes()) {
            throw new CorruptedFrameException("NEB: invalid packet profile label length " + length);
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static long safeAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private StatPayload() {}
}
