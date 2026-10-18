package cn.ussshenzhou.notenoughbandwidth.pipeline;

import cn.ussshenzhou.notenoughbandwidth.codec.zstd.BandwidthDictionary;
import cn.ussshenzhou.notenoughbandwidth.codec.zstd.BandwidthDictionary.Snapshot;
import cn.ussshenzhou.notenoughbandwidth.util.NebVarInt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.CorruptedFrameException;

public class DictionaryFrame {

    public static final int HEADER = 0x90;

    public static boolean matches(ByteBuf frame) {
        if (!frame.isReadable() || frame.getUnsignedByte(frame.readerIndex()) != HEADER) {
            return false;
        }
        ByteBuf view = frame.duplicate();
        try {
            view.skipBytes(1);
            int version = NebVarInt.read(view);
            int size = NebVarInt.read(view);
            return version >= 0 && size >= 0
                && size <= BandwidthDictionary.MAX_DICTIONARY_BYTES
                && size == view.readableBytes()
                && (version == 0) == (size == 0);
        } catch (IndexOutOfBoundsException | CorruptedFrameException ignored) {
            return false;
        }
    }

    public static ByteBuf encode(ByteBufAllocator allocator, Snapshot dictionary) {
        ByteBuf frame = allocator.buffer(dictionary.size() + 11);
        try {
            frame.writeByte(HEADER);
            NebVarInt.write(frame, dictionary.version());
            NebVarInt.write(frame, dictionary.size());
            dictionary.writeTo(frame);
            return frame;
        } catch (Throwable failure) {
            frame.release();
            throw failure;
        }
    }

    public static Snapshot decode(ByteBuf frame) {
        frame.skipBytes(1);
        int version = NebVarInt.read(frame);
        int size = NebVarInt.read(frame);
        if (version < 0 || size < 0
            || size > BandwidthDictionary.MAX_DICTIONARY_BYTES
            || size != frame.readableBytes()
            || (version == 0) != (size == 0)) {
            throw new CorruptedFrameException("NEB: invalid dictionary control frame");
        }
        byte[] bytes = new byte[size];
        frame.readBytes(bytes);
        return new Snapshot(version, bytes);
    }

    private DictionaryFrame() {}
}
