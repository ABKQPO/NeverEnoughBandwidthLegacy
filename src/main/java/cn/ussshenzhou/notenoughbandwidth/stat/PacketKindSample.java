package cn.ussshenzhou.notenoughbandwidth.stat;

/**
 * Immutable view of one packet kind's share of the stream, produced when a report is taken.
 * <p>
 * Written out longhand rather than as a record so the jar stays plain Java 8 bytecode; see the
 * note next to {@code enableModernJavaSyntax} in gradle.properties.
 *
 * @author USS_Shenzhou
 */
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
