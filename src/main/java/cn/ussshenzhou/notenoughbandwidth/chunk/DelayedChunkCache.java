package cn.ussshenzhou.notenoughbandwidth.chunk;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public class DelayedChunkCache<T> {

    private final Map<Long, Retained<T>> retained = new LinkedHashMap<>();
    private int lastCenterX;
    private int lastCenterZ;
    private int lastRadius;
    private boolean positioned;

    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public boolean retain(int x, int z, T value, int centerX, int centerZ, int radius, int limit, long now,
        long timeoutNanos, Consumer<T> evict) {
        if (limit <= 0 || timeoutNanos <= 0 || outside(x, z, centerX, centerZ, radius)) {
            return false;
        }
        expire(centerX, centerZ, radius, limit, now, timeoutNanos, evict);
        long key = key(x, z);
        retained.remove(key);
        retained.put(key, new Retained<>(value, now));
        trim(limit, evict);
        return true;
    }

    public T restore(int x, int z) {
        Retained<T> entry = retained.remove(key(x, z));
        return entry == null ? null : entry.value;
    }

    public boolean contains(int x, int z) {
        return retained.containsKey(key(x, z));
    }

    public void expire(int centerX, int centerZ, int radius, int limit, long now, long timeoutNanos,
        Consumer<T> evict) {
        boolean moved = !positioned || centerX != lastCenterX || centerZ != lastCenterZ || radius != lastRadius;
        lastCenterX = centerX;
        lastCenterZ = centerZ;
        lastRadius = radius;
        positioned = true;
        Iterator<Map.Entry<Long, Retained<T>>> iterator = retained.entrySet()
            .iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Retained<T>> entry = iterator.next();
            long key = entry.getKey();
            Retained<T> value = entry.getValue();
            boolean expired = now - value.since >= timeoutNanos;
            // Timestamps follow insertion order; stationary players only need the expired prefix.
            if (!moved && !expired) {
                break;
            }
            if (expired || outside((int) (key >> 32), (int) key, centerX, centerZ, radius)) {
                iterator.remove();
                evict.accept(value.value);
            }
        }
        trim(Math.max(0, limit), evict);
    }

    public void clear(Consumer<T> evict) {
        trim(0, evict);
    }

    public boolean isEmpty() {
        return retained.isEmpty();
    }

    private void trim(int limit, Consumer<T> evict) {
        Iterator<Retained<T>> iterator = retained.values()
            .iterator();
        while (retained.size() > limit && iterator.hasNext()) {
            T value = iterator.next().value;
            iterator.remove();
            evict.accept(value);
        }
    }

    private static boolean outside(int x, int z, int centerX, int centerZ, int radius) {
        return Math.max(Math.abs((long) x - centerX), Math.abs((long) z - centerZ)) > radius;
    }

    public static class Retained<T> {

        private final T value;
        private final long since;

        public Retained(T value, long since) {
            this.value = value;
            this.since = since;
        }
    }
}
