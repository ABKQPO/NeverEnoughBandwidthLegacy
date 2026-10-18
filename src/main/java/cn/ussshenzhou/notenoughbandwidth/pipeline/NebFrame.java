package cn.ussshenzhou.notenoughbandwidth.pipeline;

import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import io.netty.buffer.ByteBuf;

public class NebFrame {

    public static final int MARKER = 0x80;

    public static final int CODEC_MASK = 0x0F;

    public static final int MAX_WIRE_BYTES = (1 << 21) - 1;

    public static final int MAX_AGGREGATED_BYTES = 1 << 20;

    public static int header(PacketCodecType codec) {
        return MARKER | codec.wireId() & CODEC_MASK;
    }

    public static boolean isNebFrame(ByteBuf buf) {
        return buf.isReadable() && (buf.getUnsignedByte(buf.readerIndex()) & ~CODEC_MASK) == MARKER
            && codecOf(buf.getUnsignedByte(buf.readerIndex())) != null;
    }

    public static PacketCodecType codecOf(int header) {
        if ((header & ~CODEC_MASK) != MARKER) {
            return null;
        }
        return PacketCodecType.byWireId(header & CODEC_MASK);
    }
}
