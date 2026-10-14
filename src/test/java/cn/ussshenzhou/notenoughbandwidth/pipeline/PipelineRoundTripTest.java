package cn.ussshenzhou.notenoughbandwidth.pipeline;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecRegistry;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cn.ussshenzhou.notenoughbandwidth.session.NebSession;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

/**
 * Drives the real handlers, with the real compression backends, over a pair of embedded channels.
 * <p>
 * The property under test is the one the protocol lives or dies by: whatever sequence of packet
 * bodies goes into the aggregator must come out of the expander byte for byte and in order. That
 * has to hold for every backend, across frame boundaries - which is the interesting part, because
 * the streaming backends carry compression state between frames and would silently corrupt the
 * stream if a frame were ever dropped, reordered or flushed at the wrong moment.
 *
 * @author USS_Shenzhou
 */
public class PipelineRoundTripTest {

    private static final int PACKETS = 400;

    @BeforeEach
    void configure() {
        // The profiler reads Minecraft channel attributes an embedded channel has no reason to
        // carry, and it is not what these tests are about.
        NebConfig.collectPacketProfile = false;
        // 0 makes the aggregator flush through the event loop, which runPendingTasks() can drain.
        NebConfig.aggregationWindowMillis = 0;
        NebConfig.compressionThresholdBytes = 64;
        NebConfig.flushThresholdBytes = 262144;
        NebConfig.maxExpandedFrameBytes = 16777216;
    }

    @Test
    @DisplayName("every available backend round-trips a long packet stream unchanged")
    void roundTripsEveryBackend() {
        for (PacketCodecType type : PacketCodecRegistry.available()) {
            List<byte[]> sent = bodies(PACKETS, 1234L);
            List<byte[]> received = roundTrip(type, sent);
            assertEquals(sent.size(), received.size(), type + " lost or invented packets");
            for (int i = 0; i < sent.size(); i++) {
                assertArrayEquals(sent.get(i), received.get(i), type + " corrupted packet " + i);
            }
        }
    }

    @Test
    @DisplayName("a frame without the marker bit is passed through untouched")
    void passesVanillaFramesThrough() {
        EmbeddedChannel receiver = new EmbeddedChannel(new PacketExpander(new NebSession(bare())));
        byte[] vanilla = { 0x03, 0x11, 0x22, 0x33 };
        receiver.writeInbound(Unpooled.copiedBuffer(vanilla));
        assertArrayEquals(vanilla, drain((ByteBuf) receiver.readInbound()));
        release(receiver);
    }

    @Test
    @DisplayName("a lone small packet costs nothing: it goes out as a plain Minecraft frame")
    void lonePacketStaysPlain() {
        EmbeddedChannel sender = sender(PacketCodecType.STORED);
        byte[] body = { 0x03, 0x01, 0x02, 0x03 };
        sender.writeAndFlush(Unpooled.copiedBuffer(body));
        sender.runPendingTasks();

        assertArrayEquals(
            body,
            drain((ByteBuf) sender.readOutbound()),
            "a single small packet must not gain a NEB header");
        release(sender);
    }

    @Test
    @DisplayName("promises complete once the aggregated frame is written")
    void completesWritePromises() {
        EmbeddedChannel sender = sender(PacketCodecType.STORED);
        List<ChannelFuture> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(sender.write(Unpooled.copiedBuffer(new byte[] { 0x10, (byte) i, 0x7f, 0x7f })));
        }
        sender.flush();
        sender.runPendingTasks();

        for (ChannelFuture future : futures) {
            assertTrue(future.isDone(), "a buffered write was never completed");
            assertTrue(future.isSuccess(), "a buffered write failed");
        }
        release(sender);
    }

    @Test
    @DisplayName("several packets in one frame cost less on the wire than they would alone")
    void aggregationShrinksTheStream() {
        PacketCodecType best = PacketCodecRegistry.preferred();
        List<byte[]> sent = bodies(PACKETS, 99L);
        long raw = 0;
        for (byte[] body : sent) {
            raw += body.length + 1L;
        }

        EmbeddedChannel sender = sender(best);
        for (byte[] body : sent) {
            sender.writeAndFlush(Unpooled.copiedBuffer(body));
        }
        sender.runPendingTasks();

        long wire = 0;
        ByteBuf frame;
        while ((frame = (ByteBuf) sender.readOutbound()) != null) {
            wire += frame.readableBytes();
            frame.release();
        }
        release(sender);
        assertTrue(wire > 0, "nothing was written at all");
        assertTrue(wire < raw, best + " made the stream bigger: " + wire + " vs " + raw);
    }

    @Test
    @DisplayName("the registry always offers at least one usable backend")
    void registryNeverEmpty() {
        List<PacketCodecType> available = PacketCodecRegistry.available();
        assertFalse(available.isEmpty(), "no compression backend is usable at all");
        assertTrue(available.contains(PacketCodecType.STORED), "the pass-through backend must always be usable");
        assertSame(
            PacketCodecRegistry.preferred(),
            PacketCodecRegistry.negotiate(PacketCodecRegistry.capabilityMask()),
            "negotiating against our own capabilities must pick what we prefer");
    }

    /**
     * Pushes {@code sent} through an aggregator, hands every produced frame to an expander, and
     * returns what the expander emitted.
     */
    private List<byte[]> roundTrip(PacketCodecType type, List<byte[]> sent) {
        EmbeddedChannel sender = sender(type);
        EmbeddedChannel receiver = new EmbeddedChannel(new PacketExpander(new NebSession(bare())));

        List<byte[]> received = new ArrayList<>();
        for (int i = 0; i < sent.size(); i++) {
            sender.writeAndFlush(Unpooled.copiedBuffer(sent.get(i)));
            // Flush irregularly so the stream really does cross frame boundaries.
            if (i % 7 == 0) {
                sender.runPendingTasks();
                received.addAll(forward(sender, receiver));
            }
        }
        sender.runPendingTasks();
        received.addAll(forward(sender, receiver));

        release(sender);
        release(receiver);
        return received;
    }

    private List<byte[]> forward(EmbeddedChannel sender, EmbeddedChannel receiver) {
        List<byte[]> received = new ArrayList<>();
        ByteBuf frame;
        while ((frame = (ByteBuf) sender.readOutbound()) != null) {
            receiver.writeInbound(frame);
            ByteBuf body;
            while ((body = (ByteBuf) receiver.readInbound()) != null) {
                received.add(drain(body));
            }
        }
        return received;
    }

    /**
     * An embedded channel whose session is the channel itself, so enabling compression lands on the
     * same event loop the handler runs on, exactly as it does in the game.
     */
    private EmbeddedChannel sender(PacketCodecType type) {
        EmbeddedChannel channel = bare();
        NebSession session = new NebSession(channel);
        channel.pipeline()
            .addLast(new PacketAggregator(session));
        session.enableOutbound(type, 1);
        channel.runPendingTasks();
        assertTrue(session.outboundEnabled(), "the session never switched compression on");
        return channel;
    }

    /**
     * Netty 4.0 refuses an embedded channel with no handlers, so every bare channel gets a
     * pass-through one.
     */
    private static EmbeddedChannel bare() {
        return new EmbeddedChannel(new ChannelOutboundHandlerAdapter());
    }

    /**
     * Bodies shaped like real Minecraft traffic: a small packet id, then mostly repetitive content
     * so a streaming backend has something to find, with the odd incompressible blob mixed in.
     */
    private static List<byte[]> bodies(int count, long seed) {
        Random random = new Random(seed);
        List<byte[]> bodies = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int size = 1 + random.nextInt(i % 37 == 0 ? 2048 : 48);
            byte[] body = new byte[size];
            body[0] = (byte) (0x20 + i % 24);
            for (int b = 1; b < size; b++) {
                body[b] = i % 37 == 0 ? (byte) random.nextInt(256) : (byte) ('a' + (b + i) % 7);
            }
            bodies.add(body);
        }
        return bodies;
    }

    private static byte[] drain(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return bytes;
    }

    private static void release(EmbeddedChannel channel) {
        Object message;
        while ((message = channel.readInbound()) != null) {
            ((ByteBuf) message).release();
        }
        while ((message = channel.readOutbound()) != null) {
            ((ByteBuf) message).release();
        }
        channel.finish();
        channel.checkException();
    }
}
