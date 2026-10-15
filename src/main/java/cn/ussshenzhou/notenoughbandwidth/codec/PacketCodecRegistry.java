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

public class PacketCodecRegistry {

    private static final PacketCodecType[] PREFERENCE = { PacketCodecType.ZSTD_STREAM, PacketCodecType.DEFLATE_STREAM,
        PacketCodecType.ZSTD_BLOCK, PacketCodecType.STORED };

    private static final Map<PacketCodecType, PacketCodecFactory> FACTORIES = new EnumMap<>(PacketCodecType.class);

    private static volatile boolean bootstrapped = false;

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

    public static PacketCodecType negotiate(int peerMask) {
        return negotiate(peerMask, true);
    }

    public static PacketCodecType negotiate(int peerMask, boolean allowStateful) {
        for (PacketCodecType type : PREFERENCE) {
            if (!allowStateful && type.stateful()) {
                continue;
            }
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
            if (!builder.isEmpty()) {
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
