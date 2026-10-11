package cn.ussshenzhou.notenoughbandwidth.codec;

import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Fallback backend built on the JDK deflate implementation.
 * <p>
 * The {@link Deflater} is kept alive across frames and flushed with {@link Deflater#SYNC_FLUSH},
 * which preserves its 32 KiB sliding window. That gives the same cross-packet redundancy removal
 * as the zstd streaming backend, just with a much smaller history and a worse ratio. Its value is
 * that it needs no native code at all, so it works on any platform and cannot be broken by another
 * mod shipping a conflicting zstd build.
 *
 * @author USS_Shenzhou
 */
public class DeflateStreamCodec implements PacketCodec {

    private static final int SCRATCH_CHUNK = 8192;

    private final Deflater deflater;
    private final Inflater inflater;
    private byte[] scratchIn = new byte[SCRATCH_CHUNK];
    private byte[] scratchOut = new byte[SCRATCH_CHUNK];

    public DeflateStreamCodec(int level) {
        this.deflater = new Deflater(level);
        this.inflater = new Inflater();
    }

    @Override
    public PacketCodecType type() {
        return PacketCodecType.DEFLATE_STREAM;
    }

    @Override
    public boolean requiresDirectBuffers() {
        return false;
    }

    @Override
    public int compressBound(int rawSize) {
        return rawSize + (rawSize >> 3) + (rawSize >> 6) + 64;
    }

    @Override
    public void compress(ByteBuffer src, ByteBuffer dst) {
        int rawSize = src.remaining();
        byte[] in = take(rawSize, true);
        src.get(in, 0, rawSize);
        deflater.setInput(in, 0, rawSize);

        byte[] out = take(compressBound(rawSize), false);
        int produced = 0;
        while (produced < out.length) {
            int written = deflater.deflate(out, produced, out.length - produced, Deflater.SYNC_FLUSH);
            if (written == 0) {
                break;
            }
            produced += written;
        }
        dst.put(out, 0, produced);
    }

    @Override
    public void decompress(ByteBuffer src, ByteBuffer dst) {
        int compressedSize = src.remaining();
        byte[] in = take(compressedSize, true);
        src.get(in, 0, compressedSize);
        inflater.setInput(in, 0, compressedSize);

        int expected = dst.remaining();
        byte[] out = take(expected, false);
        int produced = 0;
        try {
            while (produced < expected) {
                int written = inflater.inflate(out, produced, expected - produced);
                if (written == 0) {
                    break;
                }
                produced += written;
            }
        } catch (DataFormatException e) {
            throw new IllegalStateException("NEB: corrupt deflate frame", e);
        }
        if (produced != expected) {
            throw new IllegalStateException(
                "NEB: deflate frame expanded to " + produced + " bytes, expected " + expected);
        }
        dst.put(out, 0, produced);
    }

    private byte[] take(int size, boolean input) {
        byte[] current = input ? scratchIn : scratchOut;
        if (current.length >= size) {
            return current;
        }
        byte[] grown = new byte[Math.max(size, current.length * 2)];
        if (input) {
            scratchIn = grown;
        } else {
            scratchOut = grown;
        }
        return grown;
    }

    @Override
    public void close() {
        deflater.end();
        inflater.end();
    }

    /**
     * Always available; the JDK ships deflate.
     */
    public static class Factory implements PacketCodecFactory {

        private final int level;

        public Factory(int level) {
            this.level = Math.min(Deflater.BEST_COMPRESSION, Math.max(Deflater.BEST_SPEED, level));
        }

        @Override
        public PacketCodecType type() {
            return PacketCodecType.DEFLATE_STREAM;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String describe() {
            return "JDK deflate level " + level + ", 32 KiB window";
        }

        @Override
        public PacketCodec create() {
            return new DeflateStreamCodec(level);
        }
    }
}
