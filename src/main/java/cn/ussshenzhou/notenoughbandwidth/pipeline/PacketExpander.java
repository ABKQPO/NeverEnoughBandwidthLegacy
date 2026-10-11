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

/**
 * Undoes {@link PacketAggregator}: expands a NEB frame and hands the packet bodies on one at a
 * time, exactly as Minecraft's frame splitter would have.
 * <p>
 * It sits between the splitter and the packet decoder, and decides per frame whether that frame is
 * one of ours by looking at the marker bit. A frame that is not ours is forwarded untouched, which
 * is what lets the two sides of a connection switch compression on at different moments without
 * ever misreading the stream.
 *
 * @author USS_Shenzhou
 */
public class PacketExpander extends MessageToMessageDecoder<ByteBuf> {

    private final NebSession session;
    private final PacketKindResolver resolver = new PacketKindResolver();

    public PacketExpander(NebSession session) {
        this.session = session;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
        if (!NebFrame.isNebFrame(frame)) {
            out.add(frame.retain());
            return;
        }
        int wireSize = frame.readableBytes() + NebVarInt.sizeOf(frame.readableBytes());
        int header = frame.readUnsignedByte();
        PacketCodecType codecType = NebFrame.codecOf(header);
        if (codecType == null) {
            throw new CorruptedFrameException("NEB: frame header 0x" + Integer.toHexString(header) + " is unknown");
        }
        ByteBuf raw = codecType == PacketCodecType.STORED ? frame.retain() : expand(ctx, codecType, frame);
        try {
            int rawSize = raw.readableBytes();
            int packets = split(ctx, raw, out);
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
        } finally {
            raw.release();
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
        PacketCodec codec = session.inboundCodec(codecType);
        ByteBuf source = codec.requiresDirectBuffers() && !frame.isDirect() ? toDirect(ctx, frame) : frame.retain();
        ByteBuf raw = ctx.alloc()
            .directBuffer(rawSize, rawSize);
        try {
            ByteBuffer from = source.nioBuffer();
            ByteBuffer to = raw.nioBuffer(0, rawSize);
            codec.decompress(from, to);
            raw.writerIndex(to.position());
        } catch (Throwable t) {
            raw.release();
            throw t instanceof DecoderException ? (DecoderException) t
                : new DecoderException("NEB: failed to expand a " + codecType.configName() + " frame", t);
        } finally {
            source.release();
        }
        return raw;
    }

    /**
     * Minecraft's frame splitter produces heap buffers, which the native backends cannot read.
     */
    private ByteBuf toDirect(ChannelHandlerContext ctx, ByteBuf frame) {
        ByteBuf direct = ctx.alloc()
            .directBuffer(frame.readableBytes());
        direct.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
        return direct;
    }

    /**
     * @return how many packet bodies the frame held
     */
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
            out.add(raw.readBytes(bodySize));
            packets++;
        }
        return packets;
    }
}
