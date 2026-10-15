package cn.ussshenzhou.notenoughbandwidth.pipeline;

import java.nio.ByteBuffer;
import java.util.List;

import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

import com.google.common.collect.BiMap;

import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecRegistry;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cn.ussshenzhou.notenoughbandwidth.session.NebSession;
import cn.ussshenzhou.notenoughbandwidth.stat.NebStats;
import cn.ussshenzhou.notenoughbandwidth.stat.PacketKindResolver;
import cn.ussshenzhou.notenoughbandwidth.util.ByteSize;
import cn.ussshenzhou.notenoughbandwidth.util.NebVarInt;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;

public class PacketExpander extends MessageToMessageDecoder<ByteBuf> {

    private final NebSession session;
    private final PacketKindResolver resolver = new PacketKindResolver();

    public PacketExpander(NebSession session) {
        this.session = session;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
        int wireSize = frame.readableBytes() + NebVarInt.sizeOf(frame.readableBytes());
        if (!NebFrame.isNebFrame(frame)) {
            // Counted too, so the reported ratio covers the whole stream and stays comparable with
            // the outbound figure.
            record(wireSize, wireSize, 1);
            out.add(frame.retain());
            return;
        }
        int header = frame.readUnsignedByte();
        PacketCodecType codecType = NebFrame.codecOf(header);
        if (codecType == null) {
            throw new CorruptedFrameException("NEB: frame header 0x" + Integer.toHexString(header) + " is unknown");
        }
        ByteBuf raw = codecType == PacketCodecType.STORED ? frame.retain() : expand(ctx, codecType, frame);
        try {
            int rawSize = raw.readableBytes();
            if (rawSize <= 0 || rawSize > NebConfig.maxExpandedFrameBytes) {
                throw new CorruptedFrameException("NEB: invalid expanded frame size " + rawSize);
            }
            validatePackets(raw);
            record(rawSize, wireSize, split(ctx, raw, out));
        } finally {
            raw.release();
        }
    }

    private void record(int rawSize, int wireSize, int packets) {
        session.stats()
            .recordInbound(rawSize, wireSize, packets);
        NebStats.traffic()
            .recordInbound(rawSize, wireSize, packets);
        if (NebConfig.debugLog) {
            NebLog.debug(
                "in  {} packets, {} <- {} ({})",
                packets,
                ByteSize.format(rawSize),
                ByteSize.format(wireSize),
                ByteSize.formatRatio(rawSize, wireSize));
        }
    }

    private ByteBuf expand(ChannelHandlerContext ctx, PacketCodecType codecType, ByteBuf frame) {
        int rawSize = NebVarInt.read(frame);
        if (rawSize <= 0 || rawSize > NebConfig.maxExpandedFrameBytes) {
            throw new CorruptedFrameException(
                "NEB: frame claims to expand to " + rawSize + " bytes, limit is " + NebConfig.maxExpandedFrameBytes);
        }
        if (!PacketCodecRegistry.isAvailable(codecType)) {
            throw new DecoderException(
                "NEB: peer sent a " + codecType.configName() + " frame but this side cannot decode it");
        }
        ByteBuf raw;
        try (PacketCodec codec = session.inboundCodec(codecType)) {
            ByteBuf source = codec.requiresDirectBuffers() && !frame.isDirect() ? toDirect(ctx, frame) : frame.retain();
            raw = ctx.alloc()
                .directBuffer(rawSize, rawSize);
            try {
                ByteBuffer from = source.nioBuffer();
                ByteBuffer to = raw.nioBuffer(raw.writerIndex(), rawSize);
                // See the matching note in PacketAggregator: the position a ByteBuf's nio view starts
                // at is implementation defined, so measure the codec's output as a difference.
                int before = to.position();
                codec.decompress(from, to);
                if (to.position() - before != rawSize) {
                    throw new CorruptedFrameException("NEB: expanded frame does not match its declared size");
                }
                raw.writerIndex(raw.writerIndex() + (to.position() - before));
            } catch (Throwable t) {
                raw.release();
                throw t instanceof DecoderException ? (DecoderException) t
                    : new DecoderException("NEB: failed to expand a " + codecType.configName() + " frame", t);
            } finally {
                source.release();
            }
        }
        return raw;
    }

    private ByteBuf toDirect(ChannelHandlerContext ctx, ByteBuf frame) {
        ByteBuf direct = ctx.alloc()
            .directBuffer(frame.readableBytes());
        direct.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
        return direct;
    }

    private int split(ChannelHandlerContext ctx, ByteBuf raw, List<Object> out) {
        BiMap<Integer, Class<? extends Packet>> protocol = NebConfig.collectPacketProfile ? ctx.channel()
            .attr(NetworkManager.attrKeyReceivable)
            .get() : null;
        int packets = 0;
        while (raw.isReadable()) {
            int bodySize = NebVarInt.read(raw);
            if (bodySize <= 0 || bodySize > raw.readableBytes()) {
                throw new CorruptedFrameException(
                    "NEB: sub packet claims " + bodySize
                        + " bytes but only "
                        + raw.readableBytes()
                        + " are left in the frame");
            }
            if (protocol != null) {
                NebStats.inboundProfile()
                    .record(
                        resolver.resolve(protocol, raw.slice(raw.readerIndex(), bodySize)),
                        bodySize + NebVarInt.sizeOf(bodySize));
            }
            out.add(
                raw.readSlice(bodySize)
                    .retain());
            packets++;
        }
        return packets;
    }

    private void validatePackets(ByteBuf raw) {
        ByteBuf view = raw.duplicate();
        while (view.isReadable()) {
            int size = NebVarInt.read(view);
            if (size <= 0 || size > view.readableBytes()) {
                throw new CorruptedFrameException("NEB: invalid sub packet size " + size);
            }
            view.skipBytes(size);
        }
    }
}
