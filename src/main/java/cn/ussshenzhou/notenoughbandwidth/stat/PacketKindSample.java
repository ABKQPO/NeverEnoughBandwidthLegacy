package cn.ussshenzhou.notenoughbandwidth.stat;

public class PacketKindSample {

    private final String label;
    private final long count;
    private final long rawBytes;

    /**
     * @param label    packet class name, with the mod channel appended for custom payloads
     * @param count    how many packets of this kind crossed the pipeline
     * @param rawBytes their combined pre-compression size, including NEB's per-packet length prefix
     */
    public PacketKindSample(String label, long count, long rawBytes) {
        if (label == null || label.isEmpty() || count < 0 || rawBytes < 0) {
            throw new IllegalArgumentException("Invalid packet profile sample");
        }
        this.label = label;
        this.count = count;
        this.rawBytes = rawBytes;
    }

    public String label() {
        return label;
    }

    public long count() {
        return count;
    }

    public long rawBytes() {
        return rawBytes;
    }

    public double averageBytes() {
        return count == 0 ? 0.0D : (double) rawBytes / count;
    }
}
