package cn.ussshenzhou.notenoughbandwidth.codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.codec.zstd.ZstdBlockCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.zstd.ZstdStreamCodec;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;

/**
 * The set of compression backends this installation can use, and the policy that picks one.
 * <p>
 * Backends are discovered through {@link PacketCodecFactory}, so adding one is a single
 * {@link #register} call and a new {@link PacketCodecType} constant - nothing else in the mod has
 * to change. {@link #preference()} is ordered best-first and is also what the capability handshake
 * walks when it intersects the two peers' abilities.
 *
 * @author USS_Shenzhou
 */
public class PacketCodecRegistry {

    /**
     * Best first. A type missing from this list can still be decoded, it is just never chosen.
     */
    private static final PacketCodecType[] PREFERENCE = { PacketCodecType.ZSTD_STREAM, PacketCodecType.ZSTD_BLOCK,
        PacketCodecType.DEFLATE_STREAM, PacketCodecType.STORED };

    private static final Map<PacketCodecType, PacketCodecFactory> FACTORIES = new EnumMap<>(PacketCodecType.class);

    private static volatile boolean bootstrapped = false;

    /**
     * Installs the built-in backends. Idempotent, so it is safe to call from several entry points.
     */
    public static synchronized void bootstrap() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;
        register(new ZstdStreamCodec.Factory(NebConfig.zstdLevel, NebConfig.zstdWindowLog));
        register(new ZstdBlockCodec.Factory(NebConfig.zstdLevel));
        register(new DeflateStreamCodec.Factory(NebConfig.zstdLevel));
        register(new StoredCodec.Factory());
        NebLog.info("Compression backends available: {}", describeAvailable());
    }

    public static synchronized void register(PacketCodecFactory factory) {
        FACTORIES.put(factory.type(), factory);
    }

    /**
     * @return the backend this side would like to use, honouring the config and falling back when
     *         the requested one cannot run here
     */
    public static PacketCodecType preferred() {
        bootstrap();
        String requested = NebConfig.codec;
        if (requested != null && !"auto".equalsIgnoreCase(requested.trim())) {
            PacketCodecType explicit = PacketCodecType.byConfigName(requested.trim());
            if (explicit == null) {
                NebLog.warn("Unknown codec '{}' in the config, falling back to auto selection.", requested);
            } else if (isAvailable(explicit)) {
                return explicit;
            } else {
                NebLog.warn(
                    "Codec '{}' is not usable here ({}), falling back to auto selection.",
                    requested,
                    describeUnavailable(explicit));
            }
        }
        for (PacketCodecType type : PREFERENCE) {
            if (isAvailable(type)) {
                return type;
            }
        }
        return PacketCodecType.STORED;
    }

    public static PacketCodecType[] preference() {
        return PREFERENCE.clone();
    }

    public static boolean isAvailable(PacketCodecType type) {
        bootstrap();
        PacketCodecFactory factory = FACTORIES.get(type);
        return factory != null && factory.available();
    }

    public static PacketCodec create(PacketCodecType type) {
        bootstrap();
        PacketCodecFactory factory = FACTORIES.get(type);
        if (factory == null || !factory.available()) {
            throw new IllegalStateException("NEB: codec " + type + " is not available on this side");
        }
        return factory.create();
    }

    public static String describe(PacketCodecType type) {
        bootstrap();
        PacketCodecFactory factory = FACTORIES.get(type);
        return factory == null ? "not registered" : factory.describe();
    }

    /**
     * @return a bit set of the wire ids this side can both produce and consume
     */
    public static int capabilityMask() {
        bootstrap();
        int mask = 0;
        for (PacketCodecType type : PacketCodecType.values()) {
            if (isAvailable(type)) {
                mask |= 1 << type.wireId();
            }
        }
        return mask;
    }

    /**
     * Intersects our abilities with a peer's capability mask and returns the best shared backend.
     */
    public static PacketCodecType negotiate(int peerMask) {
        for (PacketCodecType type : PREFERENCE) {
            if (isAvailable(type) && (peerMask & 1 << type.wireId()) != 0) {
                return type;
            }
        }
        return PacketCodecType.STORED;
    }

    public static List<PacketCodecType> available() {
        bootstrap();
        List<PacketCodecType> result = new ArrayList<>();
        for (PacketCodecType type : PREFERENCE) {
            if (isAvailable(type)) {
                result.add(type);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static String describeAvailable() {
        StringBuilder builder = new StringBuilder();
        for (PacketCodecType type : PREFERENCE) {
            if (!isAvailable(type)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(type.configName())
                .append(" (")
                .append(describe(type))
                .append(')');
        }
        return builder.toString();
    }

    private static String describeUnavailable(PacketCodecType type) {
        return FACTORIES.containsKey(type) ? describe(type) + " is unusable here" : "no factory registered";
    }

    private PacketCodecRegistry() {}
}
