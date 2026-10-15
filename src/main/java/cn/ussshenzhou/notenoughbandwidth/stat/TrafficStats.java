package cn.ussshenzhou.notenoughbandwidth.stat;

import java.util.concurrent.atomic.AtomicLong;

public class TrafficStats {

    private final AtomicLong outboundRaw = new AtomicLong();
    private final AtomicLong outboundWire = new AtomicLong();
    private final AtomicLong outboundFrames = new AtomicLong();
    private final AtomicLong outboundPackets = new AtomicLong();

    private final AtomicLong inboundRaw = new AtomicLong();
    private final AtomicLong inboundWire = new AtomicLong();
    private final AtomicLong inboundFrames = new AtomicLong();
    private final AtomicLong inboundPackets = new AtomicLong();

    private final RateWindow outboundRawRate = new RateWindow();
    private final RateWindow outboundWireRate = new RateWindow();
    private final RateWindow inboundRawRate = new RateWindow();
    private final RateWindow inboundWireRate = new RateWindow();

    private final AtomicLong since = new AtomicLong(System.currentTimeMillis());

    public void recordOutbound(long raw, long wire, int packets) {
        outboundRaw.addAndGet(raw);
        outboundWire.addAndGet(wire);
        outboundFrames.incrementAndGet();
        outboundPackets.addAndGet(packets);
        outboundRawRate.add(raw);
        outboundWireRate.add(wire);
    }

    public void recordInbound(long raw, long wire, int packets) {
        inboundRaw.addAndGet(raw);
        inboundWire.addAndGet(wire);
        inboundFrames.incrementAndGet();
        inboundPackets.addAndGet(packets);
        inboundRawRate.add(raw);
        inboundWireRate.add(wire);
    }

    public void addAll(TrafficStats other) {
        outboundRaw.addAndGet(other.outboundRaw.get());
        outboundWire.addAndGet(other.outboundWire.get());
        outboundFrames.addAndGet(other.outboundFrames.get());
        outboundPackets.addAndGet(other.outboundPackets.get());
        inboundRaw.addAndGet(other.inboundRaw.get());
        inboundWire.addAndGet(other.inboundWire.get());
        inboundFrames.addAndGet(other.inboundFrames.get());
        inboundPackets.addAndGet(other.inboundPackets.get());
    }

    public void reset() {
        outboundRaw.set(0);
        outboundWire.set(0);
        outboundFrames.set(0);
        outboundPackets.set(0);
        inboundRaw.set(0);
        inboundWire.set(0);
        inboundFrames.set(0);
        inboundPackets.set(0);
        outboundRawRate.reset();
        outboundWireRate.reset();
        inboundRawRate.reset();
        inboundWireRate.reset();
        since.set(System.currentTimeMillis());
    }

    public long outboundRawBytes() {
        return outboundRaw.get();
    }

    public long outboundWireBytes() {
        return outboundWire.get();
    }

    public long outboundFrameCount() {
        return outboundFrames.get();
    }

    public long outboundPacketCount() {
        return outboundPackets.get();
    }

    public long inboundRawBytes() {
        return inboundRaw.get();
    }

    public long inboundWireBytes() {
        return inboundWire.get();
    }

    public long inboundFrameCount() {
        return inboundFrames.get();
    }

    public long inboundPacketCount() {
        return inboundPackets.get();
    }

    public double outboundRawBytesPerSecond() {
        return outboundRawRate.bytesPerSecond();
    }

    public double outboundWireBytesPerSecond() {
        return outboundWireRate.bytesPerSecond();
    }

    public double inboundRawBytesPerSecond() {
        return inboundRawRate.bytesPerSecond();
    }

    public double inboundWireBytesPerSecond() {
        return inboundWireRate.bytesPerSecond();
    }

    public long measuringSinceMillis() {
        return since.get();
    }
}
