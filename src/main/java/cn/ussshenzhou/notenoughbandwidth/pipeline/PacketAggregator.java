package cn.ussshenzhou.notenoughbandwidth.pipeline;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

import com.google.common.collect.BiMap;

import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.capture.DatasetRecorder;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import cn.ussshenzhou.notenoughbandwidth.codec.zstd.BandwidthDictionary.Snapshot;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cn.ussshenzhou.notenoughbandwidth.session.NebSession;
import cn.ussshenzhou.notenoughbandwidth.stat.NebStats;
import cn.ussshenzhou.notenoughbandwidth.stat.PacketKindResolver;
import cn.ussshenzhou.notenoughbandwidth.util.ByteSize;
import cn.ussshenzhou.notenoughbandwidth.util.NebVarInt;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

public class PacketAggregator extends ChannelDuplexHandler {

    private static final int INITIAL_CAPACITY = 8192;

    private final NebSession session;
    private final PacketKindResolver resolver = new PacketKindResolver();
    private final ChannelIndex channelIndex = new ChannelIndex();
    private final CustomPayloadLookup payloadLookup = new CustomPayloadLookup();
    private final PacketBypassPolicy bypassPolicy = new PacketBypassPolicy(
        Arrays.asList(NebConfig.unaggregatedChannels),
        payloadLookup);
    private final List<ChannelPromise> pending = new ArrayList<>();

    private ByteBuf accumulator;
    private int packetCount;
    private boolean flushScheduled;
    private Runnable flushTask;
    private boolean removed;

    public PacketAggregator(NebSession session) {
        this.session = session;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        flushTask = () -> {
            flushScheduled = false;
            if (!removed) {
                emit(ctx);
            }
        };
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        if (!(message instanceof ByteBuf body) || !session.outboundEnabled()) {
            if (accumulator != null) {
                emit(ctx);
            }
            if (message instanceof ByteBuf passed) {
                // Still measured, so the reported ratio covers the whole stream rather than only
                // the part that happened to be compressible.
                int size = passed.readableBytes() + NebVarInt.sizeOf(passed.readableBytes());
                record(size, size, 1);
                DatasetRecorder.global()
                    .record(true, PacketCodecType.STORED, size, 1, size, passed);
            }
            ctx.write(message, promise);
            return;
        }
        if (!body.isReadable()) {
            ctx.write(body, promise);
            return;
        }
        try {
            BiMap<Integer, Class<? extends Packet>> protocol = ctx.channel()
                .attr(NetworkManager.attrKeySendable)
                .get();
            bypassPolicy.refresh(protocol);
            int payloadId = payloadLookup.idOf(protocol);
            if (bypassPolicy.shouldBypass(body, payloadId)) {
                emit(ctx);
                profile(ctx, body);
                int wireSize = body.readableBytes() + NebVarInt.sizeOf(body.readableBytes());
                DatasetRecorder.global()
                    .record(true, PacketCodecType.STORED, wireSize, 1, wireSize, body);
                ctx.write(body.retain(), promise);
                record(wireSize, wireSize, 1);
                ctx.flush();
                return;
            }
            profile(ctx, body);
            int bodySize = body.readableBytes();
            int entrySize = bodySize + NebVarInt.sizeOf(bodySize);
            int limit = Math.min(NebFrame.MAX_AGGREGATED_BYTES, NebConfig.maxExpandedFrameBytes);
            if (accumulator != null && entrySize > limit - accumulator.readableBytes()) {
                emit(ctx);
            }
            if (entrySize > limit) {
                // Preserve ordering while letting vanilla carry a large individual packet.
                record(entrySize, entrySize, 1);
                DatasetRecorder.global()
                    .record(true, PacketCodecType.STORED, entrySize, 1, entrySize, body);
                ctx.write(body.retain(), promise);
                return;
            }
            ByteBuf target = accumulator(ctx);
            NebVarInt.write(target, body.readableBytes());
            target.writeBytes(body);
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

    @Override
    public void flush(ChannelHandlerContext ctx) {
        if (accumulator == null || !accumulator.isReadable()) {
            ctx.flush();
            return;
        }
        scheduleFlush(ctx);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
        emit(ctx);
        ctx.close(promise);
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) {
        emit(ctx);
        ctx.disconnect(promise);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        removed = true;
        flushTask = null;
        releaseAccumulator();
        failPending(new ClosedChannelException());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        removed = true;
        releaseAccumulator();
        failPending(new ClosedChannelException());
        super.channelInactive(ctx);
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

        PacketCodecType type = session.outboundType();
        if (type == PacketCodecType.STORED || rawSize < NebConfig.compressionThresholdBytes) {
            emitPlain(ctx, promises, rawSize, packets);
            return;
        }

        ByteBuf frame;
        ByteBuf indexed = null;
        int indexMark = channelIndex.mark();
        try {
            indexed = indexChannels(ctx, accumulator);
            ByteBuf compressionPayload = indexed == null ? accumulator : indexed;
            session.observeOutbound(compressionPayload);
            Snapshot dictionary = session.nextOutboundDictionary();
            if (dictionary != null) {
                ByteBuf update = DictionaryFrame.encode(ctx.alloc(), dictionary);
                int wireSize = update.readableBytes() + NebVarInt.sizeOf(update.readableBytes());
                session.installOutboundDictionary(dictionary);
                record(0, wireSize, 0);
                ctx.write(update)
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            }
            frame = compressedFrame(ctx, type, compressionPayload);
        } catch (Throwable t) {
            channelIndex.rollback(indexMark);
            release(indexed);
            releaseAccumulator();
            failPending(t);
            for (ChannelPromise promise : promises) {
                promise.tryFailure(t);
            }
            NebLog.error("Failed to build a frame for {}, closing the connection.", session.peerLabel(), t);
            ctx.close();
            return;
        }
        if (frame == null) {
            // The peer never saw the definitions this attempt would have introduced.
            channelIndex.rollback(indexMark);
            release(indexed);
            emitPlain(ctx, promises, rawSize, packets);
            return;
        }

        int wireSize = frame.readableBytes() + NebVarInt.sizeOf(frame.readableBytes());
        DatasetRecorder.global()
            .record(true, type, wireSize, packets, rawSize, indexed == null ? accumulator : indexed);
        release(indexed);
        releaseAccumulator();
        record(rawSize, wireSize, packets);

        ChannelFuture future = ctx.write(frame);
        future.addListener((ChannelFutureListener) completed -> {
            if (completed.isSuccess()) {
                for (ChannelPromise promise : promises) {
                    promise.trySuccess();
                }
            } else {
                for (ChannelPromise promise : promises) {
                    promise.tryFailure(completed.cause());
                }
            }
        });
        ctx.flush();
    }

    private void emitPlain(ChannelHandlerContext ctx, List<ChannelPromise> promises, int rawSize, int packets) {
        ByteBuf raw = accumulator;
        accumulator = null;
        try {
            int index = 0;
            while (raw.isReadable()) {
                int bodySize = NebVarInt.read(raw);
                ByteBuf body = raw.readSlice(bodySize)
                    .retain();
                DatasetRecorder.global()
                    .record(
                        true,
                        PacketCodecType.STORED,
                        bodySize + NebVarInt.sizeOf(bodySize),
                        1,
                        bodySize + NebVarInt.sizeOf(bodySize),
                        body);
                if (index < promises.size()) {
                    ctx.write(body, promises.get(index));
                } else {
                    ctx.write(body);
                }
                index++;
            }
        } catch (Throwable t) {
            failPending(t);
            for (ChannelPromise promise : promises) {
                promise.tryFailure(t);
            }
            NebLog.error("Failed to write buffered packets for {}, closing the connection.", session.peerLabel(), t);
            ctx.close();
            return;
        } finally {
            raw.release();
        }
        record(rawSize, rawSize, packets);
        ctx.flush();
    }

    private void record(int rawSize, int wireSize, int packets) {
        session.stats()
            .recordOutbound(rawSize, wireSize, packets);
        NebStats.traffic()
            .recordOutbound(rawSize, wireSize, packets);
        NebStats.timeline()
            .recordOutbound(rawSize, wireSize);
        if (NebConfig.debugLog) {
            NebLog.debug(
                "out {} packets, {} -> {} ({})",
                packets,
                ByteSize.format(rawSize),
                ByteSize.format(wireSize),
                ByteSize.formatRatio(rawSize, wireSize));
        }
    }

    private ByteBuf compressedFrame(ChannelHandlerContext ctx, PacketCodecType type, ByteBuf payload) {
        ByteBuf frame;
        int rawSize = payload.readableBytes();
        PacketCodec codec = session.outboundCodec();
        int bound = codec.compressBound(rawSize);
        int headerSize = 1 + NebVarInt.sizeOf(rawSize);
        if (bound < 0 || bound > NebFrame.MAX_WIRE_BYTES - headerSize) {
            throw new IllegalStateException("NEB: codec bound exceeds Minecraft's frame length limit");
        }
        frame = ctx.alloc()
            .directBuffer(headerSize + bound);
        try {
            frame.writeByte(NebFrame.header(type));
            NebVarInt.write(frame, rawSize);
            ByteBuffer source = payload.nioBuffer();
            ByteBuffer destination = frame.nioBuffer(frame.writerIndex(), bound);
            // Some ByteBuf implementations hand back their internal buffer positioned at the
            // requested index rather than a zero-based slice, so the only portable way to learn
            // how much the codec wrote is to diff the position.
            int before = destination.position();
            codec.compress(source, destination);
            frame.writerIndex(frame.writerIndex() + (destination.position() - before));
            if (!type.stateful() && frame.readableBytes() + NebVarInt.sizeOf(frame.readableBytes()) >= rawSize) {
                frame.release();
                return null;
            }
        } catch (Throwable t) {
            frame.release();
            throw t;
        }
        return frame;
    }

    /**
     * Shortens the mod channel name of every custom payload in the buffered payload.
     * <p>
     * Only frames that really go out are indexed, because a definition the peer never receives
     * would desynchronise its table. The scan runs first so a payload with no custom payload in
     * it, which is most of them, costs nothing but a walk.
     *
     * @return a rewritten payload the caller owns, or {@code null} to use the original
     */
    private ByteBuf indexChannels(ChannelHandlerContext ctx, ByteBuf payload) {
        int payloadId = payloadLookup.idOf(
            ctx.channel()
                .attr(NetworkManager.attrKeySendable)
                .get());
        if (payloadId == CustomPayloadLookup.UNRESOLVED || !carriesCustomPayload(payload, payloadId)) {
            return null;
        }
        ByteBuf result = ctx.alloc()
            .directBuffer(payload.readableBytes() + 16);
        try {
            ByteBuf view = payload.duplicate();
            while (view.isReadable()) {
                int bodySize = NebVarInt.read(view);
                ByteBuf body = view.readSlice(bodySize);
                ByteBuf token = channelIndex.encode(ctx.alloc(), body, payloadId);
                if (token == null) {
                    NebVarInt.write(result, bodySize);
                    result.writeBytes(body, body.readerIndex(), bodySize);
                } else {
                    try {
                        NebVarInt.write(result, token.readableBytes());
                        result.writeBytes(token);
                    } finally {
                        token.release();
                    }
                }
            }
            return result;
        } catch (Throwable failure) {
            result.release();
            throw failure;
        }
    }

    private static boolean carriesCustomPayload(ByteBuf payload, int payloadId) {
        NebVarInt.Cursor cursor = new NebVarInt.Cursor();
        int index = payload.readerIndex();
        int end = payload.writerIndex();
        while (index < end) {
            cursor.at(index);
            if (!NebVarInt.readAt(payload, cursor)) {
                return false;
            }
            int bodySize = cursor.value;
            int bodyStart = cursor.index;
            if (bodySize <= 0 || bodySize > end - bodyStart) {
                return false;
            }
            cursor.at(bodyStart);
            if (NebVarInt.readAt(payload, cursor) && cursor.value == payloadId) {
                return true;
            }
            index = bodyStart + bodySize;
        }
        return false;
    }

    private static void release(ByteBuf buffer) {
        if (buffer != null) {
            buffer.release();
        }
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
