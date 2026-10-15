package cn.ussshenzhou.notenoughbandwidth.stat;

import java.util.Arrays;

public class RateWindow {

    private static final int DEFAULT_BUCKETS = 20;
    private static final long DEFAULT_BUCKET_MILLIS = 100L;

    private final long[] buckets;
    private final long bucketMillis;
    private long bucketStart;
    private int index;

    public RateWindow() {
        this(DEFAULT_BUCKETS, DEFAULT_BUCKET_MILLIS);
    }

    public RateWindow(int bucketCount, long bucketMillis) {
        this.buckets = new long[Math.max(1, bucketCount)];
        this.bucketMillis = Math.max(1L, bucketMillis);
        this.bucketStart = System.currentTimeMillis();
    }

    public synchronized void add(long bytes) {
        advance(System.currentTimeMillis());
        buckets[index] += bytes;
    }

    /**
     * @return the average rate over the whole window, in bytes per second
     */
    public synchronized double bytesPerSecond() {
        advance(System.currentTimeMillis());
        long total = 0;
        for (long bucket : buckets) {
            total += bucket;
        }
        return total * 1000.0D / (buckets.length * bucketMillis);
    }

    public synchronized void reset() {
        Arrays.fill(buckets, 0L);
        bucketStart = System.currentTimeMillis();
        index = 0;
    }

    private void advance(long now) {
        long elapsed = now - bucketStart;
        if (elapsed < bucketMillis) {
            return;
        }
        long steps = elapsed / bucketMillis;
        if (steps >= buckets.length) {
            Arrays.fill(buckets, 0L);
            index = 0;
        } else {
            for (long i = 0; i < steps; i++) {
                index = (index + 1) % buckets.length;
                buckets[index] = 0L;
            }
        }
        bucketStart += steps * bucketMillis;
    }
}
