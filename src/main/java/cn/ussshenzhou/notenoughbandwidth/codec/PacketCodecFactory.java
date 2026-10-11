package cn.ussshenzhou.notenoughbandwidth.codec;

/**
 * Creates {@link PacketCodec} instances of one {@link PacketCodecType}.
 * <p>
 * Registering a factory is the only thing a new backend has to do to become selectable, both from
 * the config and from the capability handshake.
 *
 * @author USS_Shenzhou
 */
public interface PacketCodecFactory {

    PacketCodecType type();

    /**
     * Whether this backend can run here and now. Implementations must answer without throwing, even
     * when the classes they wrap are missing from the classpath.
     */
    boolean available();

    /**
     * Human readable reason shown in logs and reports, typically the backing library version.
     */
    String describe();

    PacketCodec create();
}
