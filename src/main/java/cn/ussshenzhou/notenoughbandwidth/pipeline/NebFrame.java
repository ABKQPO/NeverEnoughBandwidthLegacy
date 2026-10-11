package cn.ussshenzhou.notenoughbandwidth.pipeline;

import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import io.netty.buffer.ByteBuf;

/**
 * The NEB frame layout and the one trick that makes it safe to deploy.
 *
 * <pre>
 * ┌─────┬───────────┬──────────────────────────────────────────────┐
 * │ hdr │ (rawSize) │ payload                                      │
 * └─────┴───────────┴──────────────────────────────────────────────┘
 *
 * hdr     1 byte.  Bit 7 is always set. Bits 3..0 hold the PacketCodecType wire id.
 * rawSize varint.   Present only when the codec is not STORED: the payload's expanded size.
 * payload           One or more [varint bodySize][body] pairs, optionally compressed as a whole.
 *                   A body is exactly what Minecraft's packet encoder produced, that is a varint
 *                   packet id followed by the packet's own data.
 * </pre>
 *
 * <h4>Why bit 7</h4> This handler sits between Minecraft's frame splitter and its packet decoder,
 * so the first byte of a vanilla frame is always a varint packet id. Every id 1.7.10 defines is
 * below 0x80 and therefore encodes as a single byte with bit 7 clear. Setting bit 7 thus yields a
 * marker that can never collide with a real frame, which means the receiving side can tell NEB
 * frames from vanilla frames per frame, with no negotiated switch-over point and no window during
 * which the two sides disagree about how to read the stream. A peer without NEB simply never sees
 * one, because we only start emitting them after it has announced itself.
 *
 * @author USS_Shenzhou
 */
public class NebFrame {

    public static final int MARKER = 0x80;

    public static final int CODEC_MASK = 0x0F;

    public static int header(PacketCodecType codec) {
        return MARKER | codec.wireId() & CODEC_MASK;
    }

    /**
     * @return whether the buffer starts with a NEB frame rather than a plain Minecraft packet body
     */
    public static boolean isNebFrame(ByteBuf buf) {
        return buf.isReadable() && (buf.getUnsignedByte(buf.readerIndex()) & MARKER) != 0;
    }

    /**
     * @return the codec the frame was written with, or {@code null} for an unknown wire id
     */
    public static PacketCodecType codecOf(int header) {
        return PacketCodecType.byWireId(header & CODEC_MASK);
    }

    private NebFrame() {}
}
