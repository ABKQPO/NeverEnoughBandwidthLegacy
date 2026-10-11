package cn.ussshenzhou.notenoughbandwidth.pipeline;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

import com.google.common.collect.BiMap;

import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cn.ussshenzhou.notenoughbandwidth.session.NebSession;
import cn.ussshenzhou.notenoughbandwidth.stat.NebStats;
import cn.ussshenzhou.notenoughbandwidth.stat.PacketKindResolver;
import cn.ussshenzhou.notenoughbandwidth.util.ByteSize;
import cn.ussshenzhou.notenoughbandwidth.util.NebVarInt;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

/**
 * Collects the encoded packet bodies Minecraft hands to the wire and emits them as one compressed
 * frame.
 * <p>
 * It sits between Minecraft's packet encoder and its length prepender, so what arrives here is a
 * finished packet body and what leaves is a finished frame body. Working at that level is what
 * makes the mod indifferent to who produced the packet: vanilla packets, FML handshake traffic and
 * every mod's custom payload all look the same, and none of their codecs have to be re-entered.
 * <p>
 * Writes are buffered rather than forwarded because Minecraft flushes after every single packet,
 * which would otherwise leave nothing to aggregate. The buffer is released by the configured time
 * window, by a size threshold, or by the channel closing - the last one matters, because a kick
 * packet is normally followed immediately by a close and its bytes still have to get out.
 *
 * @author USS_Shenzhou
 */
public class PacketAggregator extends ChannelOutboundHandlerAdapter {

    private static final int INITIAL_CAPACITY = 8192;

    private final NebSession session;
    private final PacketKindResolver resolver = new PacketKindResolver();
    private final List<ChannelPromise> pending = new ArrayList<>();

    private ByteBuf accumulator;
    private int packetCount;
    private int firstPrefixSize;
    private boolean flushScheduled;
    private Runnable flushTask;

    public PacketAggregator(NebSession session) {
        this.session = session;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        flushTask = new Runnable() {

            @Override
            public void run() {
                flushScheduled = false;
                emit(ctx);
            }
        };
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
        if (!(message instanceof ByteBuf) || !session.outboundEnabled()) {
            ctx.write(message, promise);
            return;
        }
        ByteBuf body = (ByteBuf) message;
        if (!body.isReadable()) {
            ctx.write(body, promise);
            return;
        }
        try {
            profile(ctx, body);
            ByteBuf target = accumulator(ctx);
            int prefixSize = NebVarInt.sizeOf(body.readableBytes());
            NebVarInt.write(target, body.readableBytes());
            target.writeBytes(body);
            if (packetCount == 0) {
                firstPrefixSize = prefixSize;
            }
            packetCount++;
            pending.add(promise);
        } finally {
            body.release();
        }
        if (accumulator != null && accumulator.readableBytes() >= NebConfig.flushThresholdBytes) {
            emit(ctx);
        } else {
            scheduleFlush(ctx);
        }
    }

    /**
     * Minecraft flushes per packet, so a flush only gets forwarded when there is nothing waiting;
     * otherwise the scheduled emit owns it.
     */
    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        if (accumulator == null || !accumulator.isReadable()) {
            ctx.flush();
            return;
        }
        scheduleFlush(ctx);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        emit(ctx);
        ctx.close(promise);
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        emit(ctx);
        ctx.disconnect(promise);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        releaseAccumulator();
        failPending(new ClosedChannelException());
    }

    private void scheduleFlush(ChannelHandlerContext ctx) {
        if (flushScheduled || flushTask == null) {
            return;
        }
        flushScheduled = true;
        int window = NebConfig.aggregationWindowMillis;
        if (window <= 0) {
            ctx.executor()
                .execute(flushTask);
        } else {
            ctx.executor()
                .schedule(flushTask, window, TimeUnit.MILLISECONDS);
        }
    }

    private ByteBuf accumulator(ChannelHandlerContext ctx) {
        if (accumulator == null) {
            accumulator = ctx.alloc()
                .directBuffer(INITIAL_CAPACITY);
            packetCount = 0;
        }
        return accumulator;
    }

    private void emit(ChannelHandlerContext ctx) {
        if (accumulator == null || !accumulator.isReadable()) {
            ctx.flush();
            return;
        }
        int rawSize = accumulator.readableBytes();
        int packets = packetCount;
        List<ChannelPromise> promises = new ArrayList<>(pending);
        pending.clear();
        packetCount = 0;

        ByteBuf frame;
        try {
            frame = buildFrame(ctx, rawSize, packets);
        } catch (Throwable t) {
            releaseAccumulator();
            failPending(t);
            for (ChannelPromise promise : promises) {
                promise.tryFailure(t);
            }
            NebLog.error("Failed to build a frame for {}, closing the connection.", session.peerLabel(), t);
            ctx.close();
            return;
        }
        accumulator = null;

        int wireSize = frame.readableBytes() + NebVarInt.sizeOf(frame.readableBytes());
        session.stats()
            .recordOutbound(rawSize, wireSize, packets);
        NebStats.traffic()
            .recordOutbound(rawSize, wireSize, packets);
        if (NebConfig.debugLog) {
            NebLog.debug(
                "out {} packets, {} -> {} ({})",
                packets,
                ByteSize.format(rawSize),
                ByteSize.format(wireSize),
                ByteSize.formatRatio(rawSize, wireSize));
        }

        ChannelFuture future = ctx.write(frame);
        future.addListener(new ChannelFutureListener() {

            @Override
            public void operationComplete(ChannelFuture completed) {
                if (completed.isSuccess()) {
                    for (ChannelPromise promise : promises) {
                        promise.trySuccess();
                    }
                } else {
                    for (ChannelPromise promise : promises) {
                        promise.tryFailure(completed.cause());
                    }
                }
            }
        });
        ctx.flush();
    }

    /**
     * Turns the accumulator into the buffer that goes on the wire, consuming it.
     */
    private ByteBuf buildFrame(ChannelHandlerContext ctx, int rawSize, int packets) {
        PacketCodecType type = session.outboundType();
        boolean compress = type != PacketCodecType.STORED && rawSize >= NebConfig.compressionThresholdBytes;
        if (!compress) {
            if (packets == 1) {
                return plainFrame();
            }
            return storedFrame(ctx, rawSize);
        }
        return compressedFrame(ctx, type, rawSize);
    }

    /**
     * A lone small packet is cheapest as an ordinary Minecraft frame: no header, no length prefix,
     * nothing for the peer to undo.
     */
    private ByteBuf plainFrame() {
        ByteBuf raw = accumulator;
        raw.skipBytes(firstPrefixSize);
        return raw;
    }

    private ByteBuf storedFrame(ChannelHandlerContext ctx, int rawSize) {
        ByteBuf frame = ctx.alloc()
            .directBuffer(1 + rawSize);
        try {
            frame.writeByte(NebFrame.header(PacketCodecType.STORED));
            frame.writeBytes(accumulator);
        } catch (Throwable t) {
            frame.release();
            throw t;
        }
        accumulator.release();
        return frame;
    }

    private ByteBuf compressedFrame(ChannelHandlerContext ctx, PacketCodecType type, int rawSize) {
        PacketCodec codec = session.outboundCodec();
        int bound = codec.compressBound(rawSize);
        int headerSize = 1 + NebVarInt.sizeOf(rawSize);
        ByteBuf frame = ctx.alloc()
            .directBuffer(headerSize + bound);
        try {
            frame.writeByte(NebFrame.header(type));
            NebVarInt.write(frame, rawSize);
            ByteBuffer source = accumulator.nioBuffer();
            ByteBuffer destination = frame.nioBuffer(frame.writerIndex(), bound);
            codec.compress(source, destination);
            frame.writerIndex(frame.writerIndex() + destination.position());
        } catch (Throwable t) {
            frame.release();
            throw t;
        }
        accumulator.release();
        return frame;
    }

    private void profile(ChannelHandlerContext ctx, ByteBuf body) {
        if (!NebConfig.collectPacketProfile) {
            return;
        }
        BiMap<Integer, Class<? extends Packet>> protocol = ctx.channel()
            .attr(NetworkManager.attrKeySendable)
            .get();
        NebStats.outboundProfile()
            .record(resolver.resolve(protocol, body), body.readableBytes() + NebVarInt.sizeOf(body.readableBytes()));
    }

    private void releaseAccumulator() {
        if (accumulator != null) {
            accumulator.release();
            accumulator = null;
        }
        packetCount = 0;
    }

    private void failPending(Throwable cause) {
        for (ChannelPromise promise : pending) {
            promise.tryFailure(cause);
        }
        pending.clear();
    }
}
