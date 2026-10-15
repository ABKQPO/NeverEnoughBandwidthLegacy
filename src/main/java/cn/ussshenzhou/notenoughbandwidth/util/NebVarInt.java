package cn.ussshenzhou.notenoughbandwidth.util;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.CorruptedFrameException;

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

    public static class Cursor {

        public int index;
        public int value;

        public Cursor at(int index) {
            this.index = index;
            this.value = 0;
            return this;
        }
    }
}
