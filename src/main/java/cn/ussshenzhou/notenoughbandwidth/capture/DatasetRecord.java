package cn.ussshenzhou.notenoughbandwidth.capture;

import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;

public class DatasetRecord {

    public static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    public static final int MAX_RAW_BYTES = MAX_PAYLOAD_BYTES * 2;

    private final long timestampMillis;
    private final boolean outbound;
    private final PacketCodecType codec;
    private final int rawBytes;
    private final int wireBytes;
    private final int packetCount;
    private final byte[] payload;

    public DatasetRecord(long timestampMillis, boolean outbound, PacketCodecType codec, int rawBytes, int wireBytes,
        int packetCount, byte[] payload) {
        if (timestampMillis < 0 || codec == null
            || rawBytes <= 0
            || wireBytes <= 0
            || packetCount <= 0
            || payload == null
            || rawBytes > MAX_RAW_BYTES
            || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid bandwidth dataset record");
        }
        this.timestampMillis = timestampMillis;
        this.outbound = outbound;
        this.codec = codec;
        this.rawBytes = rawBytes;
        this.wireBytes = wireBytes;
        this.packetCount = packetCount;
        this.payload = payload.clone();
    }

    public long timestampMillis() {
        return timestampMillis;
    }

    public boolean outbound() {
        return outbound;
    }

    public PacketCodecType codec() {
        return codec;
    }

    public int rawBytes() {
        return rawBytes;
    }

    public int wireBytes() {
        return wireBytes;
    }

    public int packetCount() {
        return packetCount;
    }

    public byte[] copyPayload() {
        return payload.clone();
    }
}
