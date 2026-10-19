package cn.ussshenzhou.notenoughbandwidth.pipeline;

import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import io.netty.buffer.ByteBuf;

public class NebFrame {

    public static final int MARKER = 0x80;

    public static final int CODEC_MASK = 0x0F;

    public static final int RESET_CONTEXT = 0x40;

    public static final int RESERVED_MASK = 0x30;

    public static final int MAX_WIRE_BYTES = (1 << 21) - 1;

    public static final int MAX_AGGREGATED_BYTES = 1 << 20;

    public static int header(PacketCodecType codec) {
        return header(codec, false);
    }

    public static int header(PacketCodecType codec, boolean resetContext) {
        return MARKER | (resetContext ? RESET_CONTEXT : 0) | codec.wireId() & CODEC_MASK;
    }

    public static boolean resetsContext(int header) {
        return (header & RESET_CONTEXT) != 0;
    }

    public static boolean isNebFrame(ByteBuf buf) {
        return buf.isReadable() && codecOf(buf.getUnsignedByte(buf.readerIndex())) != null;
    }

    public static PacketCodecType codecOf(int header) {
        if ((header & MARKER) == 0 || (header & RESERVED_MASK) != 0) {
            return null;
        }
        PacketCodecType codec = PacketCodecType.byWireId(header & CODEC_MASK);
        return codec == PacketCodecType.STORED ? null : codec;
    }
}
