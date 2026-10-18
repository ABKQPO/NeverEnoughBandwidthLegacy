package cn.ussshenzhou.notenoughbandwidth.stat;

public class TrafficPoint {

    private final long timestampMillis;
    private final long inboundRawBytes;
    private final long inboundWireBytes;
    private final long outboundRawBytes;
    private final long outboundWireBytes;

    public TrafficPoint(long timestampMillis, long inboundRawBytes, long inboundWireBytes, long outboundRawBytes,
        long outboundWireBytes) {
        this.timestampMillis = timestampMillis;
        this.inboundRawBytes = inboundRawBytes;
        this.inboundWireBytes = inboundWireBytes;
        this.outboundRawBytes = outboundRawBytes;
        this.outboundWireBytes = outboundWireBytes;
    }

    public long timestampMillis() {
        return timestampMillis;
    }

    public long inboundRawBytes() {
        return inboundRawBytes;
    }

    public long inboundWireBytes() {
        return inboundWireBytes;
    }

    public long outboundRawBytes() {
        return outboundRawBytes;
    }

    public long outboundWireBytes() {
        return outboundWireBytes;
    }
}
