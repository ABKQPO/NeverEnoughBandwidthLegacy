package cn.ussshenzhou.notenoughbandwidth.codec;

public enum PacketCodecType {

    STORED(0, "stored", false),
    ZSTD_STREAM(1, "zstd_stream", true),
    ZSTD_BLOCK(2, "zstd_block", false),
    DEFLATE_STREAM(3, "deflate_stream", true);

    public static final int MAX_WIRE_ID = 15;

    private static final PacketCodecType[] BY_WIRE_ID = new PacketCodecType[MAX_WIRE_ID + 1];

    static {
        for (PacketCodecType type : values()) {
            BY_WIRE_ID[type.wireId] = type;
        }
    }

    private final int wireId;
    private final String configName;
    private final boolean stateful;

    PacketCodecType(int wireId, String configName, boolean stateful) {
        this.wireId = wireId;
        this.configName = configName;
        this.stateful = stateful;
    }

    public int wireId() {
        return wireId;
    }

    public String configName() {
        return configName;
    }

    public boolean stateful() {
        return stateful;
    }

    public static PacketCodecType byWireId(int wireId) {
        if (wireId < 0 || wireId > MAX_WIRE_ID) {
            return null;
        }
        return BY_WIRE_ID[wireId];
    }

    public static PacketCodecType byConfigName(String name) {
        for (PacketCodecType type : values()) {
            if (type.configName.equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }
}
