package cn.ussshenzhou.notenoughbandwidth.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises each backend on its own, with no Netty in the way.
 * <p>
 * The streaming backends are the reason this exists: their output for frame <em>n</em> depends on
 * frames 1..n-1, so a bug there shows up as corruption several frames later rather than at the
 * call that caused it.
 *
 * @author USS_Shenzhou
 */
public class PacketCodecTest {

    private static final int FRAMES = 32;

    @Test
    @DisplayName("every backend reproduces a sequence of frames exactly")
    void roundTripsFrameSequence() {
        for (PacketCodecType type : PacketCodecRegistry.available()) {
            List<byte[]> frames = frames(FRAMES, 7L);
            PacketCodec compressor = PacketCodecRegistry.create(type);
            PacketCodec decompressor = PacketCodecRegistry.create(type);
            try {
                for (int i = 0; i < frames.size(); i++) {
                    byte[] original = frames.get(i);
                    byte[] compressed = compress(compressor, original);
                    byte[] restored = decompress(decompressor, compressed, original.length);
                    assertArrayEquals(original, restored, type + " corrupted frame " + i);
                }
            } finally {
                compressor.close();
                decompressor.close();
            }
        }
    }

    @Test
    @DisplayName("a stateful backend beats a stateless one on repetitive frames")
    void statefulBackendsUseTheirHistory() {
        if (!PacketCodecRegistry.isAvailable(PacketCodecType.ZSTD_STREAM)
            || !PacketCodecRegistry.isAvailable(PacketCodecType.ZSTD_BLOCK)) {
            return;
        }
        List<byte[]> frames = frames(FRAMES, 11L);
        long streaming = totalCompressed(PacketCodecType.ZSTD_STREAM, frames);
        long block = totalCompressed(PacketCodecType.ZSTD_BLOCK, frames);
        assertTrue(
            streaming < block,
            "keeping the context alive should pay off: " + streaming + " vs " + block + " bytes");
    }

    private long totalCompressed(PacketCodecType type, List<byte[]> frames) {
        PacketCodec codec = PacketCodecRegistry.create(type);
        try {
            long total = 0;
            for (byte[] frame : frames) {
                total += compress(codec, frame).length;
            }
            return total;
        } finally {
            codec.close();
        }
    }

    @Test
    @DisplayName("remembering previous frames beats a stronger algorithm with no memory")
    void historyBeatsAStrongerAlgorithm() {
        if (!PacketCodecRegistry.isAvailable(PacketCodecType.ZSTD_BLOCK)
            || !PacketCodecRegistry.isAvailable(PacketCodecType.DEFLATE_STREAM)) {
            return;
        }
        List<byte[]> frames = modChannelFrames();
        long withHistory = totalCompressed(PacketCodecType.DEFLATE_STREAM, frames);
        long without = totalCompressed(PacketCodecType.ZSTD_BLOCK, frames);
        assertTrue(
            withHistory < without,
            "this is why the preference list ranks deflate_stream above zstd_block: " + withHistory
                + " vs "
                + without
                + " bytes");
    }

    @Test
    @DisplayName("the chosen backend is the best one that remembers previous frames")
    void prefersAHistoryKeepingBackend() {
        PacketCodecType preferred = PacketCodecRegistry.preferred();
        boolean anyStateful = false;
        for (PacketCodecType type : PacketCodecRegistry.available()) {
            anyStateful |= type.stateful();
        }
        if (anyStateful) {
            assertTrue(
                preferred.stateful(),
                "a stateless backend was chosen while a history-keeping one was available: " + preferred);
        }
    }

    /**
     * Frames shaped like a burst of mod custom payloads: a packet id, a channel name, a short body.
     * The channel name repeating across frames is the whole point - it is what a backend with no
     * memory has to pay for again every single time.
     */
    private static List<byte[]> modChannelFrames() {
        String[] channels = { "GregTech", "appliedenergistics2", "gtnhlib", "FML|HS", "betterquesting", "Thaumcraft" };
        Random random = new Random(5L);
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            StringBuilder builder = new StringBuilder();
            for (int packet = 0; packet < 6; packet++) {
                builder.append('?')
                    .append(channels[(i + packet) % channels.length])
                    .append(" ")
                    .append("idCountDamagetagdisplay")
                    .append((char) ('a' + random.nextInt(26)));
            }
            frames.add(
                builder.toString()
                    .getBytes(StandardCharsets.UTF_8));
        }
        return frames;
    }

    private static byte[] compress(PacketCodec codec, byte[] raw) {
        ByteBuffer src = direct(raw.length);
        src.put(raw);
        src.flip();
        ByteBuffer dst = direct(codec.compressBound(raw.length));
        codec.compress(src, dst);
        dst.flip();
        byte[] out = new byte[dst.remaining()];
        dst.get(out);
        return out;
    }

    private static byte[] decompress(PacketCodec codec, byte[] compressed, int rawSize) {
        ByteBuffer src = direct(compressed.length);
        src.put(compressed);
        src.flip();
        ByteBuffer dst = direct(rawSize);
        dst.limit(rawSize);
        codec.decompress(src, dst);
        dst.flip();
        byte[] out = new byte[dst.remaining()];
        dst.get(out);
        return out;
    }

    private static ByteBuffer direct(int capacity) {
        return ByteBuffer.allocateDirect(Math.max(1, capacity));
    }

    /**
     * Repetitive, Minecraft-ish frame contents with the odd incompressible blob.
     */
    private static List<byte[]> frames(int count, long seed) {
        Random random = new Random(seed);
        List<byte[]> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int size = 128 + random.nextInt(4096);
            byte[] frame = new byte[size];
            for (int b = 0; b < size; b++) {
                frame[b] = i % 9 == 0 ? (byte) random.nextInt(256) : (byte) ('a' + (b + i) % 11);
            }
            frames.add(frame);
        }
        return frames;
    }
}
