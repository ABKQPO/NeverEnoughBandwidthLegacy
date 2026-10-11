package cn.ussshenzhou.notenoughbandwidth.codec;

import java.io.Closeable;
import java.nio.ByteBuffer;

/**
 * One compression backend bound to one direction-pair of one connection.
 * <p>
 * Implementations may be stateful: {@link #compress} and {@link #decompress} are allowed to carry
 * history from one call to the next, which is where most of the gain comes from. The contract is
 * therefore that the <em>n</em>-th {@code decompress} call on the receiving side sees exactly the
 * output of the <em>n</em>-th {@code compress} call on the sending side, in order. TCP guarantees
 * this, and the pipeline never reorders or drops a frame.
 * <p>
 * All buffers are passed with {@code position}/{@code limit} already set to the region of interest.
 * A codec must consume {@code src} completely and leave {@code dst} with its {@code position} just
 * past the bytes it produced.
 *
 * @author USS_Shenzhou
 */
public interface PacketCodec extends Closeable {

    PacketCodecType type();

    /**
     * @return whether this backend needs {@code src} and {@code dst} to be direct buffers
     */
    boolean requiresDirectBuffers();

    /**
     * Worst case output size for {@code rawSize} input bytes, used to size the destination buffer.
     */
    int compressBound(int rawSize);

    void compress(ByteBuffer src, ByteBuffer dst);

    void decompress(ByteBuffer src, ByteBuffer dst);

    @Override
    void close();
}
