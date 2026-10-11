package cn.ussshenzhou.notenoughbandwidth.stat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Attributes raw bytes to packet kinds so a report can say which traffic is actually worth money.
 * <p>
 * Compression happens per frame, not per packet, so a per-packet compressed size does not exist.
 * What does exist, and is what you need in order to act, is the pre-compression share of every
 * kind: that is the number that tells you which mod or which vanilla packet is flooding the link.
 * The frame-level ratio is reported separately by {@link TrafficStats}.
 *
 * @author USS_Shenzhou
 */
public class PacketProfile {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public void record(String label, int rawBytes) {
        Entry entry = entries.get(label);
        if (entry == null) {
            Entry created = new Entry();
            entry = entries.putIfAbsent(label, created);
            if (entry == null) {
                entry = created;
            }
        }
        entry.count.incrementAndGet();
        entry.rawBytes.addAndGet(rawBytes);
    }

    public void reset() {
        entries.clear();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public long totalRawBytes() {
        long total = 0;
        for (Entry entry : entries.values()) {
            total += entry.rawBytes.get();
        }
        return total;
    }

    /**
     * @return every tracked kind, heaviest first
     */
    public List<PacketKindSample> snapshot() {
        List<PacketKindSample> samples = new ArrayList<>(entries.size());
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            samples.add(
                new PacketKindSample(entry.getKey(), entry.getValue().count.get(), entry.getValue().rawBytes.get()));
        }
        samples.sort(
            Comparator.comparingLong(PacketKindSample::rawBytes)
                .reversed());
        return samples;
    }

    private static class Entry {

        private final AtomicLong count = new AtomicLong();
        private final AtomicLong rawBytes = new AtomicLong();
    }
}
