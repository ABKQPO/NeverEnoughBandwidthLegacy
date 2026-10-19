package cn.ussshenzhou.notenoughbandwidth.stat;

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
        if (outboundRawBytes < 0 || outboundWireBytes < 0
            || outboundFrames < 0
            || outboundPackets < 0
            || inboundRawBytes < 0
            || inboundWireBytes < 0
            || inboundFrames < 0
            || inboundPackets < 0
            || !Double.isFinite(outboundRawRate)
            || outboundRawRate < 0.0D
            || !Double.isFinite(outboundWireRate)
            || outboundWireRate < 0.0D
            || !Double.isFinite(inboundRawRate)
            || inboundRawRate < 0.0D
            || !Double.isFinite(inboundWireRate)
            || inboundWireRate < 0.0D) {
            throw new IllegalArgumentException("Traffic snapshot values are invalid");
        }
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
            safeAdd(outboundRawBytes, other.outboundRawBytes),
            safeAdd(outboundWireBytes, other.outboundWireBytes),
            safeAdd(outboundFrames, other.outboundFrames),
            safeAdd(outboundPackets, other.outboundPackets),
            safeAdd(inboundRawBytes, other.inboundRawBytes),
            safeAdd(inboundWireBytes, other.inboundWireBytes),
            safeAdd(inboundFrames, other.inboundFrames),
            safeAdd(inboundPackets, other.inboundPackets),
            safeRateAdd(outboundRawRate, other.outboundRawRate),
            safeRateAdd(outboundWireRate, other.outboundWireRate),
            safeRateAdd(inboundRawRate, other.inboundRawRate),
            safeRateAdd(inboundWireRate, other.inboundWireRate));
    }

    private static long safeAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static double safeRateAdd(double left, double right) {
        double result = left + right;
        return Double.isFinite(result) ? result : Double.MAX_VALUE;
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
        return safeAdd(outboundRawBytes, inboundRawBytes);
    }

    public long totalWireBytes() {
        return safeAdd(outboundWireBytes, inboundWireBytes);
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
