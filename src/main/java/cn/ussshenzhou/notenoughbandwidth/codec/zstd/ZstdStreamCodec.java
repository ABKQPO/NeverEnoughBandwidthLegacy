package cn.ussshenzhou.notenoughbandwidth.codec.zstd;

import java.nio.Buffer;
import java.nio.ByteBuffer;

import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;

import cn.ussshenzhou.notenoughbandwidth.codec.DictionaryCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecFactory;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;

public class ZstdStreamCodec implements DictionaryCodec {

    private static final long MAX_WINDOW_BYTES = 1L << 27;

    private final ZstdCompressCtx compressCtx;
    private final ZstdDecompressCtx decompressCtx;
    private final ByteBuffer overflowProbe = ByteBuffer.allocateDirect(1);
    private boolean atFrameStart = true;

    public ZstdStreamCodec(int level, int windowLog) {
        compressCtx = new ZstdCompressCtx();
        compressCtx.setLevel(level);
        compressCtx.setChecksum(false);
        compressCtx.setContentSize(false);
        compressCtx.setMagicless(true);
        compressCtx.setWindowLog(windowLog);
        decompressCtx = new ZstdDecompressCtx();
        decompressCtx.setMagicless(true);
    }

    @Override
    public PacketCodecType type() {
        return PacketCodecType.ZSTD_STREAM;
    }

    @Override
    public boolean requiresDirectBuffers() {
        return true;
    }

    @Override
    public int compressBound(int rawSize) {
        return (int) Zstd.compressBound(rawSize) + 128 * 1024;
    }

    @Override
    public void compress(ByteBuffer src, ByteBuffer dst) {
        while (true) {
            int beforeInput = src.position();
            int beforeOutput = dst.position();
            boolean flushed = compressCtx.compressDirectByteBufferStream(dst, src, EndDirective.FLUSH);
            if (flushed && !src.hasRemaining()) {
                return;
            }
            if (!dst.hasRemaining()) {
                throw new IllegalStateException("NEB: zstd output buffer exhausted while flushing a frame");
            }
            if (src.position() == beforeInput && dst.position() == beforeOutput) {
                throw new IllegalStateException("NEB: zstd compressor made no progress");
            }
        }
    }

    @Override
    public void decompress(ByteBuffer src, ByteBuffer dst) {
        if (atFrameStart) {
            validateWindow(src);
        }
        while (dst.hasRemaining()) {
            int beforeInput = src.position();
            int beforeOutput = dst.position();
            atFrameStart = decompressCtx.decompressDirectByteBufferStream(dst, src);
            if (atFrameStart && src.hasRemaining()) {
                throw new IllegalStateException("NEB: multiple zstd streams in one frame");
            }
            if (dst.hasRemaining()
                && (atFrameStart || (src.position() == beforeInput && dst.position() == beforeOutput))) {
                throw new IllegalStateException("NEB: truncated zstd frame, " + dst.remaining() + " bytes missing");
            }
        }
        // A decoder can consume all input while retaining additional decompressed output.
        while (!atFrameStart) {
            ((Buffer) overflowProbe).clear();
            int beforeInput = src.position();
            atFrameStart = decompressCtx.decompressDirectByteBufferStream(overflowProbe, src);
            if (overflowProbe.position() != 0) {
                throw new IllegalStateException("NEB: zstd frame exceeds its declared size");
            }
            if (atFrameStart && src.hasRemaining()) {
                throw new IllegalStateException("NEB: multiple zstd streams in one frame");
            }
            if (!src.hasRemaining()) {
                return;
            }
            if (src.position() == beforeInput) {
                throw new IllegalStateException("NEB: zstd frame has trailing input");
            }
        }
    }

    private static void validateWindow(ByteBuffer src) {
        int start = src.position();
        // Magicless streams disable content size, so their window descriptor follows the header byte.
        if (src.remaining() < 2 || (src.get(start) & 0x20) != 0) {
            throw new IllegalStateException("NEB: invalid streaming zstd frame header");
        }
        int descriptor = src.get(start + 1) & 0xff;
        long base = 1L << (10 + (descriptor >>> 3));
        long window = base + (base >>> 3) * (descriptor & 7);
        // Match the largest configured sender window, independent of this receiver's preference.
        if (window > MAX_WINDOW_BYTES) {
            throw new IllegalStateException("NEB: zstd history window exceeds 128 MiB");
        }
    }

    @Override
    public void loadDictionary(byte[] dictionary) {
        compressCtx.loadDict(dictionary);
        decompressCtx.loadDict(dictionary);
        atFrameStart = true;
    }

    @Override
    public void close() {
        compressCtx.close();
        decompressCtx.close();
    }

    public static class Factory implements PacketCodecFactory {

        private final int level;
        private final int windowLog;

        public Factory(int level, int windowLog) {
            this.level = level;
            this.windowLog = windowLog;
        }

        @Override
        public PacketCodecType type() {
            return PacketCodecType.ZSTD_STREAM;
        }

        @Override
        public boolean available() {
            return ZstdCapability.streamingSupported();
        }

        @Override
        public String describe() {
            return "zstd-jni " + ZstdCapability.version() + ", level " + level + ", window 2^" + windowLog + " bytes";
        }

        @Override
        public PacketCodec create() {
            return new ZstdStreamCodec(level, windowLog);
        }
    }
}
