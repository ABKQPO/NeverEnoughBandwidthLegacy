package cn.ussshenzhou.notenoughbandwidth.stat;

/**
 * A point-in-time copy of {@link TrafficStats}, safe to pass between threads, to put in a packet,
 * or to add up across connections.
 * <p>
 * Written out longhand rather than as a record so the jar stays plain Java 8 bytecode; see the
 * note next to {@code enableModernJavaSyntax} in gradle.properties.
 *
 * @author USS_Shenzhou
 */
public class TrafficSnapshot {

    public static final TrafficSnapshot EMPTY = new TrafficSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    private final long outboundRawBytes;
    private final long outboundWireBytes;
    private final long outboundFrames;
    private final long outboundPackets;
    private final long inboundRawBytes;
    private final long inboundWireBytes;
    private final long inboundFrames;
    private final long inboundPackets;
    private final double outboundRawRate;
    private final double outboundWireRate;
    private final double inboundRawRate;
    private final double inboundWireRate;

    public TrafficSnapshot(long outboundRawBytes, long outboundWireBytes, long outboundFrames, long outboundPackets,
        long inboundRawBytes, long inboundWireBytes, long inboundFrames, long inboundPackets, double outboundRawRate,
        double outboundWireRate, double inboundRawRate, double inboundWireRate) {
        this.outboundRawBytes = outboundRawBytes;
        this.outboundWireBytes = outboundWireBytes;
        this.outboundFrames = outboundFrames;
        this.outboundPackets = outboundPackets;
        this.inboundRawBytes = inboundRawBytes;
        this.inboundWireBytes = inboundWireBytes;
        this.inboundFrames = inboundFrames;
        this.inboundPackets = inboundPackets;
        this.outboundRawRate = outboundRawRate;
        this.outboundWireRate = outboundWireRate;
        this.inboundRawRate = inboundRawRate;
        this.inboundWireRate = inboundWireRate;
    }

    public static TrafficSnapshot of(TrafficStats stats) {
        return new TrafficSnapshot(
            stats.outboundRawBytes(),
            stats.outboundWireBytes(),
            stats.outboundFrameCount(),
            stats.outboundPacketCount(),
            stats.inboundRawBytes(),
            stats.inboundWireBytes(),
            stats.inboundFrameCount(),
            stats.inboundPacketCount(),
            stats.outboundRawBytesPerSecond(),
            stats.outboundWireBytesPerSecond(),
            stats.inboundRawBytesPerSecond(),
            stats.inboundWireBytesPerSecond());
    }

    public TrafficSnapshot plus(TrafficSnapshot other) {
        return new TrafficSnapshot(
            outboundRawBytes + other.outboundRawBytes,
            outboundWireBytes + other.outboundWireBytes,
            outboundFrames + other.outboundFrames,
            outboundPackets + other.outboundPackets,
            inboundRawBytes + other.inboundRawBytes,
            inboundWireBytes + other.inboundWireBytes,
            inboundFrames + other.inboundFrames,
            inboundPackets + other.inboundPackets,
            outboundRawRate + other.outboundRawRate,
            outboundWireRate + other.outboundWireRate,
            inboundRawRate + other.inboundRawRate,
            inboundWireRate + other.inboundWireRate);
    }

    public long outboundRawBytes() {
        return outboundRawBytes;
    }

    public long outboundWireBytes() {
        return outboundWireBytes;
    }

    public long outboundFrames() {
        return outboundFrames;
    }

    public long outboundPackets() {
        return outboundPackets;
    }

    public long inboundRawBytes() {
        return inboundRawBytes;
    }

    public long inboundWireBytes() {
        return inboundWireBytes;
    }

    public long inboundFrames() {
        return inboundFrames;
    }

    public long inboundPackets() {
        return inboundPackets;
    }

    public double outboundRawRate() {
        return outboundRawRate;
    }

    public double outboundWireRate() {
        return outboundWireRate;
    }

    public double inboundRawRate() {
        return inboundRawRate;
    }

    public double inboundWireRate() {
        return inboundWireRate;
    }

    public long totalRawBytes() {
        return outboundRawBytes + inboundRawBytes;
    }

    public long totalWireBytes() {
        return outboundWireBytes + inboundWireBytes;
    }

    /**
     * @return average packets merged into one outbound frame, the aggregation win on its own
     */
    public double outboundPacketsPerFrame() {
        return outboundFrames == 0 ? 0.0D : (double) outboundPackets / outboundFrames;
    }

    public double inboundPacketsPerFrame() {
        return inboundFrames == 0 ? 0.0D : (double) inboundPackets / inboundFrames;
    }
}
