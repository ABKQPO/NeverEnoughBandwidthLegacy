package cn.ussshenzhou.notenoughbandwidth.codec;

import java.nio.ByteBuffer;

public class StoredCodec implements PacketCodec {

    @Override
    public PacketCodecType type() {
        return PacketCodecType.STORED;
    }

    @Override
    public boolean requiresDirectBuffers() {
        return false;
    }

    @Override
    public int compressBound(int rawSize) {
        return rawSize;
    }

    @Override
    public void compress(ByteBuffer src, ByteBuffer dst) {
        dst.put(src);
    }

    @Override
    public void decompress(ByteBuffer src, ByteBuffer dst) {
        dst.put(src);
    }

    @Override
    public void close() {}

    public static class Factory implements PacketCodecFactory {

        @Override
        public PacketCodecType type() {
            return PacketCodecType.STORED;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String describe() {
            return "no compression";
        }

        @Override
        public PacketCodec create() {
            return new StoredCodec();
        }
    }
}
