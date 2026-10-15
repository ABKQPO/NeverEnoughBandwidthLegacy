package cn.ussshenzhou.notenoughbandwidth.pipeline;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.ussshenzhou.notenoughbandwidth.util.NebVarInt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.CorruptedFrameException;

public class ChannelIndex {

    public static final int MAX_CHANNELS = 4096;

    private static final int MAX_NAME_BYTES = 80;

    private final Map<String, Integer> sent = new HashMap<>();
    private final List<String> sentOrder = new ArrayList<>();
    private final List<byte[]> received = new ArrayList<>();

    public int mark() {
        return sentOrder.size();
    }

    public void rollback(int mark) {
        while (sentOrder.size() > mark) {
            sent.remove(sentOrder.remove(sentOrder.size() - 1));
        }
    }

    /**
     * Rewrites one encoded packet body, shortening its channel name to a token.
     *
     * @param customPayloadId the packet id custom payloads use in this direction
     * @return a new buffer the caller owns, or {@code null} when the body is left alone
     */
    public ByteBuf encode(ByteBufAllocator allocator, ByteBuf body, int customPayloadId) {
        ByteBuf view = body.duplicate();
        if (!startsWithCustomPayload(view, customPayloadId)) {
            return null;
        }
        int nameLength = NebVarInt.read(view);
        if (nameLength <= 0 || nameLength > MAX_NAME_BYTES || nameLength > view.readableBytes()) {
            return null;
        }
        byte[] name = new byte[nameLength];
        view.readBytes(name);
        String channel = new String(name, StandardCharsets.UTF_8);

        Integer known = sent.get(channel);
        boolean define = known == null && sentOrder.size() < MAX_CHANNELS;
        int id = known != null ? known : 0;
        if (define) {
            id = sentOrder.size() + 1;
            sent.put(channel, id);
            sentOrder.add(channel);
        }

        ByteBuf result = allocator.buffer(body.readableBytes() + 8);
        try {
            NebVarInt.write(result, customPayloadId);
            NebVarInt.write(result, id == 0 ? 0 : id << 1 | (define ? 1 : 0));
            if (id == 0 || define) {
                NebVarInt.write(result, nameLength);
                result.writeBytes(name);
            }
            result.writeBytes(view);
            return result;
        } catch (Throwable failure) {
            result.release();
            throw failure;
        }
    }

    /**
     * Restores the channel name a token stands for, producing the body Minecraft expects.
     *
     * @return a new buffer the caller owns, or {@code null} when the body is left alone
     */
    public ByteBuf decode(ByteBufAllocator allocator, ByteBuf body, int customPayloadId) {
        ByteBuf view = body.duplicate();
        if (!startsWithCustomPayload(view, customPayloadId)) {
            return null;
        }
        int token = NebVarInt.read(view);
        if (token == 1 || token < 0 || token > (MAX_CHANNELS << 1) + 1) {
            throw new CorruptedFrameException("NEB: invalid channel index token " + token);
        }
        int id = token >>> 1;
        byte[] name;
        if (token == 0 || (token & 1) != 0) {
            int nameLength = NebVarInt.read(view);
            if (nameLength <= 0 || nameLength > MAX_NAME_BYTES || nameLength > view.readableBytes()) {
                throw new CorruptedFrameException("NEB: invalid indexed channel name length " + nameLength);
            }
            name = new byte[nameLength];
            view.readBytes(name);
            if (token != 0) {
                if (id != received.size() + 1) {
                    throw new CorruptedFrameException("NEB: out of order channel definition " + id);
                }
                received.add(name);
            }
        } else {
            if (id == 0 || id > received.size()) {
                throw new CorruptedFrameException("NEB: undefined channel index " + id);
            }
            name = received.get(id - 1);
        }

        ByteBuf result = allocator.buffer(view.readableBytes() + name.length + 8);
        try {
            NebVarInt.write(result, customPayloadId);
            NebVarInt.write(result, name.length);
            result.writeBytes(name);
            result.writeBytes(view);
            return result;
        } catch (Throwable failure) {
            result.release();
            throw failure;
        }
    }

    public void clear() {
        sent.clear();
        sentOrder.clear();
        received.clear();
    }

    private static boolean startsWithCustomPayload(ByteBuf view, int customPayloadId) {
        if (customPayloadId < 0 || !view.isReadable()) {
            return false;
        }
        return NebVarInt.read(view) == customPayloadId && view.isReadable();
    }
}
