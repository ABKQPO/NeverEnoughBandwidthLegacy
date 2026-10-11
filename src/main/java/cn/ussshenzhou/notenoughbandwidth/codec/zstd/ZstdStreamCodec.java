package cn.ussshenzhou.notenoughbandwidth.codec.zstd;

import java.nio.ByteBuffer;

import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;

import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecFactory;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;

/**
 * The backend this mod exists for: one zstd context per connection, flushed once per frame.
 * <p>
 * Because the context survives between frames, the compressor keeps a window of everything it has
 * already sent on this connection. Minecraft traffic is extremely repetitive at that scale - the
 * same entity ids, the same mod channel names, the same NBT keys, over and over - so the second
 * occurrence of a structure costs a few bits instead of its full size. That is a very different
 * result from compressing each packet on its own, which is what makes the held-back aggregation
 * window worthwhile.
 * <p>
 * Frames are written magicless and without a content size field, as both are redundant here: the
 * frame length is already known from the Minecraft frame header and the raw size travels in the
 * NEB header.
 *
 * @author USS_Shenzhou
 */
public class ZstdStreamCodec implements PacketCodec {

    /**
     * Guards against a backend that stops making progress. A frame needs one or two iterations.
     */
    private static final int ITERATION_LIMIT = 1024;

    private final ZstdCompressCtx compressCtx;
    private final ZstdDecompressCtx decompressCtx;

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
        return (int) Zstd.compressBound(rawSize) + 64;
    }

    @Override
    public void compress(ByteBuffer src, ByteBuffer dst) {
        for (int i = 0; i < ITERATION_LIMIT; i++) {
            boolean flushed = compressCtx.compressDirectByteBufferStream(dst, src, EndDirective.FLUSH);
            if (flushed && !src.hasRemaining()) {
                return;
            }
            if (!dst.hasRemaining()) {
                throw new IllegalStateException("NEB: zstd output buffer exhausted while flushing a frame");
            }
        }
        throw new IllegalStateException("NEB: zstd compressor made no progress");
    }

    @Override
    public void decompress(ByteBuffer src, ByteBuffer dst) {
        for (int i = 0; i < ITERATION_LIMIT && dst.hasRemaining(); i++) {
            decompressCtx.decompressDirectByteBufferStream(dst, src);
            if (!dst.hasRemaining()) {
                break;
            }
            if (!src.hasRemaining()) {
                throw new IllegalStateException("NEB: truncated zstd frame, " + dst.remaining() + " bytes missing");
            }
        }
        if (dst.hasRemaining()) {
            throw new IllegalStateException("NEB: zstd decompressor made no progress");
        }
        if (src.hasRemaining()) {
            throw new IllegalStateException(
                "NEB: zstd frame had " + src.remaining() + " trailing bytes, the stream is out of sync");
        }
    }

    @Override
    public void close() {
        compressCtx.close();
        decompressCtx.close();
    }

    /**
     * Only offers itself when the zstd-jni build on the classpath actually exposes the streaming API.
     */
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
