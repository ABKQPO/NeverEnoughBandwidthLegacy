package cn.ussshenzhou.notenoughbandwidth.stat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TrafficTimeline {

    public static final int DEFAULT_SECONDS = 120;
    private static final long BUCKET_MILLIS = 1000L;

    private final TrafficPoint[] buckets;
    private long currentBucket = Long.MIN_VALUE;
    private long inboundRaw;
    private long inboundWire;
    private long outboundRaw;
    private long outboundWire;

    public TrafficTimeline() {
        this(DEFAULT_SECONDS);
    }

    public TrafficTimeline(int seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("Traffic timeline size must be positive");
        }
        buckets = new TrafficPoint[seconds];
    }

    public synchronized void recordInbound(long rawBytes, long wireBytes) {
        add(false, rawBytes, wireBytes, System.currentTimeMillis());
    }

    public synchronized void recordOutbound(long rawBytes, long wireBytes) {
        add(true, rawBytes, wireBytes, System.currentTimeMillis());
    }

    public synchronized List<TrafficPoint> snapshot() {
        advance(System.currentTimeMillis());
        if (currentBucket == Long.MIN_VALUE) {
            return Collections.emptyList();
        }
        List<TrafficPoint> result = new ArrayList<>(buckets.length);
        long first = Math.max(0L, currentBucket - buckets.length + 1L);
        for (long bucket = first; bucket <= currentBucket; bucket++) {
            if (bucket == currentBucket) {
                result
                    .add(new TrafficPoint(bucket * BUCKET_MILLIS, inboundRaw, inboundWire, outboundRaw, outboundWire));
                continue;
            }
            TrafficPoint point = buckets[index(bucket)];
            if (point != null && point.timestampMillis() / BUCKET_MILLIS == bucket) {
                result.add(point);
            } else {
                result.add(new TrafficPoint(bucket * BUCKET_MILLIS, 0, 0, 0, 0));
            }
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized void reset() {
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = null;
        }
        currentBucket = Long.MIN_VALUE;
        inboundRaw = 0;
        inboundWire = 0;
        outboundRaw = 0;
        outboundWire = 0;
    }

    private void add(boolean outbound, long rawBytes, long wireBytes, long timestampMillis) {
        if (rawBytes < 0 || wireBytes < 0) {
            throw new IllegalArgumentException("Traffic byte counts cannot be negative");
        }
        advance(timestampMillis);
        if (outbound) {
            outboundRaw = safeAdd(outboundRaw, rawBytes);
            outboundWire = safeAdd(outboundWire, wireBytes);
        } else {
            inboundRaw = safeAdd(inboundRaw, rawBytes);
            inboundWire = safeAdd(inboundWire, wireBytes);
        }
    }

    private void advance(long timestampMillis) {
        long bucket = timestampMillis / BUCKET_MILLIS;
        if (currentBucket == bucket) {
            return;
        }
        if (currentBucket != Long.MIN_VALUE) {
            long start = Math.max(currentBucket + 1L, bucket - buckets.length + 1L);
            for (long empty = start; empty <= bucket; empty++) {
                buckets[index(empty)] = new TrafficPoint(empty * BUCKET_MILLIS, 0, 0, 0, 0);
            }
            buckets[index(currentBucket)] = new TrafficPoint(
                currentBucket * BUCKET_MILLIS,
                inboundRaw,
                inboundWire,
                outboundRaw,
                outboundWire);
        }
        currentBucket = bucket;
        inboundRaw = 0;
        inboundWire = 0;
        outboundRaw = 0;
        outboundWire = 0;
    }

    private int index(long bucket) {
        return (int) Math.floorMod(bucket, buckets.length);
    }

    private static long safeAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
