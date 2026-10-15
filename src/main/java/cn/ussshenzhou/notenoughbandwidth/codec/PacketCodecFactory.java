package cn.ussshenzhou.notenoughbandwidth.codec;

/**
 * Creates {@link PacketCodec} instances of one {@link PacketCodecType}.
 * <p>
 * Registering a factory is the only thing a new backend has to do to become selectable, both from
 * the config and from the capability handshake.
 */
public interface PacketCodecFactory {

    PacketCodecType type();

    /**
     * Whether this backend can run here and now. Implementations must answer without throwing, even
     * when the classes they wrap are missing from the classpath.
     */
    boolean available();

    String describe();

    PacketCodec create();
}
