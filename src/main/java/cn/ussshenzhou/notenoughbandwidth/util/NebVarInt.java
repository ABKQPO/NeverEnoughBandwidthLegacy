package cn.ussshenzhou.notenoughbandwidth.util;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.CorruptedFrameException;

/**
 * The 7-bit little-endian variable length integer Minecraft uses everywhere.
 * <p>
 * Reimplemented here instead of borrowing {@code PacketBuffer} so the pipeline layer stays free of
 * Minecraft types, and so the absolute-indexed {@link #readAt} variant is available for inspecting
 * a packet body without disturbing its reader index.
 *
 * @author USS_Shenzhou
 */
public class NebVarInt {

    private static final int MAX_BYTES = 5;

    public static int sizeOf(int value) {
        for (int bytes = 1; bytes < MAX_BYTES; bytes++) {
            if ((value & -1 << bytes * 7) == 0) {
                return bytes;
            }
        }
        return MAX_BYTES;
    }

    public static void write(ByteBuf buf, int value) {
        int remaining = value;
        while ((remaining & -128) != 0) {
            buf.writeByte(remaining & 127 | 128);
            remaining >>>= 7;
        }
        buf.writeByte(remaining);
    }

    public static int read(ByteBuf buf) {
        int result = 0;
        int shift = 0;
        byte current;
        do {
            if (shift >= MAX_BYTES * 7) {
                throw new CorruptedFrameException("NEB: varint is wider than " + MAX_BYTES + " bytes");
            }
            current = buf.readByte();
            result |= (current & 127) << shift;
            shift += 7;
        } while ((current & 128) != 0);
        return result;
    }

    /**
     * Reads the varint at {@code cursor.index} without touching the buffer's reader index, then
     * leaves {@code cursor} pointing just past it.
     *
     * @return false when the buffer ends mid-varint
     */
    public static boolean readAt(ByteBuf buf, Cursor cursor) {
        int result = 0;
        int shift = 0;
        int index = cursor.index;
        int end = buf.writerIndex();
        while (true) {
            if (index >= end || shift >= MAX_BYTES * 7) {
                return false;
            }
            int current = buf.getByte(index++);
            result |= (current & 127) << shift;
            shift += 7;
            if ((current & 128) == 0) {
                cursor.value = result;
                cursor.index = index;
                return true;
            }
        }
    }

    /**
     * Reusable position-and-value holder for {@link #readAt}, so profiling allocates nothing.
     */
    public static class Cursor {

        public int index;
        public int value;

        public Cursor at(int index) {
            this.index = index;
            this.value = 0;
            return this;
        }
    }

    private NebVarInt() {}
}
