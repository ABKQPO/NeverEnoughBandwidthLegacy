package cn.ussshenzhou.notenoughbandwidth.codec.zstd;

import java.nio.ByteBuffer;

import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;

import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecFactory;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;

public class ZstdStreamCodec implements PacketCodec {

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
