package cn.ussshenzhou.notenoughbandwidth.codec.zstd;

import java.nio.Buffer;
import java.nio.ByteBuffer;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;

import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecFactory;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;

/**
 * zstd with one self-contained frame per flush.
 * <p>
 * This exists purely as a compatibility path. It deliberately restricts itself to the API surface
 * that zstd-jni has carried since well before 1.5, because another mod may have shipped an older
 * build of the unrelocatable {@code com.github.luben.zstd} package and won class loading. The
 * aggregation window still helps here - a frame holding a tick worth of packets compresses far
 * better than each packet alone - but there is no history between frames.
 *
 * @author USS_Shenzhou
 */
public class ZstdBlockCodec implements PacketCodec {

    private final ZstdCompressCtx compressCtx;
    private final ZstdDecompressCtx decompressCtx;

    public ZstdBlockCodec(int level) {
        compressCtx = new ZstdCompressCtx();
        compressCtx.setLevel(level);
        compressCtx.setChecksum(false);
        compressCtx.setContentSize(false);
        decompressCtx = new ZstdDecompressCtx();
    }

    @Override
    public PacketCodecType type() {
        return PacketCodecType.ZSTD_BLOCK;
    }

    @Override
    public boolean requiresDirectBuffers() {
        return true;
    }

    @Override
    public int compressBound(int rawSize) {
        return (int) Zstd.compressBound(rawSize);
    }

    @Override
    public void compress(ByteBuffer src, ByteBuffer dst) {
        int written = compressCtx
            .compressDirectByteBuffer(dst, dst.position(), dst.remaining(), src, src.position(), src.remaining());
        advance(src, src.limit());
        advance(dst, dst.position() + written);
    }

    @Override
    public void decompress(ByteBuffer src, ByteBuffer dst) {
        int expected = dst.remaining();
        int written = decompressCtx
            .decompressDirectByteBuffer(dst, dst.position(), expected, src, src.position(), src.remaining());
        if (written != expected) {
            throw new IllegalStateException("NEB: zstd frame expanded to " + written + " bytes, expected " + expected);
        }
        advance(src, src.limit());
        advance(dst, dst.position() + written);
    }

    /**
     * Java 9 made {@code ByteBuffer.position(int)} covariant, so calling it through the subtype
     * would emit a post-8 descriptor. Going through {@link Buffer} keeps the bytecode on Java 8.
     */
    private static void advance(ByteBuffer buffer, int position) {
        ((Buffer) buffer).position(position);
    }

    @Override
    public void close() {
        compressCtx.close();
        decompressCtx.close();
    }

    /**
     * Available whenever the native library loaded at all.
     */
    public static class Factory implements PacketCodecFactory {

        private final int level;

        public Factory(int level) {
            this.level = level;
        }

        @Override
        public PacketCodecType type() {
            return PacketCodecType.ZSTD_BLOCK;
        }

        @Override
        public boolean available() {
            return ZstdCapability.blockSupported();
        }

        @Override
        public String describe() {
            return "zstd-jni " + ZstdCapability.version() + ", level " + level + ", no cross-frame history";
        }

        @Override
        public PacketCodec create() {
            return new ZstdBlockCodec(level);
        }
    }
}
