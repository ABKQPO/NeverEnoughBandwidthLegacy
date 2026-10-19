package cn.ussshenzhou.notenoughbandwidth.codec.zstd;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.github.luben.zstd.Zstd;

import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cn.ussshenzhou.notenoughbandwidth.util.NebVarInt;
import io.netty.buffer.ByteBuf;

public class BandwidthDictionary {

    public static final int MAX_DICTIONARY_BYTES = 32 * 1024;
    public static final int MAX_SAMPLE_BYTES = 16 * 1024;
    public static final Snapshot EMPTY = new Snapshot(0, new byte[0]);

    private static final int MIN_SAMPLES = 64;
    private static final int MIN_SAMPLE_BYTES = 64 * 1024;
    private static final int MAX_SAMPLES = 512;
    private static final int MAX_BUFFERED_BYTES = 1024 * 1024;
    private static final long MIN_DICTIONARY_TRAFFIC = 256L * 1024L;
    private static final long RETRAIN_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final ExecutorService TRAINER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "NEB Dictionary Trainer");
        thread.setDaemon(true);
        return thread;
    });

    private final Object lock = new Object();
    private volatile Snapshot current = EMPTY;
    private List<byte[]> samples = new ArrayList<>();
    private int sampleBytes;
    private int versionCounter;
    private long generation;
    private volatile long lastTraining;
    private volatile boolean training;
    private volatile boolean coolingDown;

    public BandwidthDictionary() {}

    public Snapshot snapshot() {
        return NebConfig.enableBandwidthDictionary ? current : EMPTY;
    }

    public void observe(ByteBuf packet) {
        if (!NebConfig.enableBandwidthDictionary || packet.readableBytes() < 32) {
            return;
        }
        if (training || (coolingDown && System.nanoTime() - lastTraining < RETRAIN_NANOS)) {
            return;
        }
        synchronized (lock) {
            long now = System.nanoTime();
            if (training || (coolingDown && now - lastTraining < RETRAIN_NANOS)) {
                return;
            }
            int size = Math.min(packet.readableBytes(), MAX_SAMPLE_BYTES);
            if (samples.size() < MAX_SAMPLES && size <= MAX_BUFFERED_BYTES - sampleBytes) {
                byte[] sample = new byte[size];
                packet.getBytes(packet.readerIndex(), sample);
                samples.add(sample);
                sampleBytes += size;
            }
            if (samples.size() < MIN_SAMPLES || sampleBytes < MIN_SAMPLE_BYTES) {
                // A corpus consisting entirely of tiny packets must not fill the sampler forever.
                if (samples.size() == MAX_SAMPLES) {
                    samples.clear();
                    sampleBytes = 0;
                }
                return;
            }
            List<byte[]> corpus = samples;
            samples = new ArrayList<>();
            sampleBytes = 0;
            training = true;
            long taskGeneration = generation;
            int level = NebConfig.zstdLevel;
            TRAINER.execute(() -> train(corpus, taskGeneration, level));
        }
    }

    public void reset() {
        synchronized (lock) {
            generation++;
            current = EMPTY;
            samples.clear();
            sampleBytes = 0;
            versionCounter = 0;
            coolingDown = false;
        }
    }

    private void train(List<byte[]> corpus, long taskGeneration, int level) {
        try {
            List<byte[]> trainingSamples = new ArrayList<>();
            List<byte[]> validationSamples = new ArrayList<>();
            for (int i = 0; i < corpus.size(); i++) {
                (i % 4 == 0 ? validationSamples : trainingSamples).add(corpus.get(i));
            }
            byte[] destination = new byte[MAX_DICTIONARY_BYTES];
            long size = Zstd.trainFromBuffer(trainingSamples.toArray(new byte[0][]), destination, false);
            if (Zstd.isError(size) || size < 512 || size > MAX_DICTIONARY_BYTES) {
                return;
            }
            byte[] dictionary = Arrays.copyOf(destination, (int) size);
            Snapshot baseline = current;
            long validationBytes = totalBytes(validationSamples);
            long baselineSize = score(validationSamples, baseline.bytes, level);
            long candidateSize = score(validationSamples, dictionary, level);
            long expectedTraffic = Math.max(MIN_DICTIONARY_TRAFFIC, (long) dictionary.length * 64L);
            long baselineProjected = scale(baselineSize, expectedTraffic, validationBytes);
            long candidateProjected = scale(candidateSize, expectedTraffic, validationBytes);
            long frameBytes = estimateDictionaryWireBytes(dictionary.length, baseline.version + 1);
            long netSaved = baselineProjected - candidateProjected - frameBytes;
            if (candidateProjected * 100 > baselineProjected * 98 || netSaved <= 0) {
                return;
            }
            synchronized (lock) {
                if (generation == taskGeneration && NebConfig.enableBandwidthDictionary
                    && current == baseline
                    && versionCounter < Integer.MAX_VALUE) {
                    versionCounter = Math.max(versionCounter, baseline.version) + 1;
                    current = new Snapshot(versionCounter, dictionary);
                    NebLog.debug(
                        "Published bandwidth dictionary {} ({} bytes, validation {} -> {}, net {} bytes).",
                        current.version,
                        size,
                        baselineProjected,
                        candidateProjected,
                        netSaved);
                }
            }
        } catch (Throwable failure) {
            NebLog.debug("Bandwidth dictionary training failed: {}", failure.toString());
        } finally {
            synchronized (lock) {
                training = false;
                if (generation == taskGeneration) {
                    lastTraining = System.nanoTime();
                    coolingDown = true;
                }
            }
        }
    }

    private static long score(List<byte[]> corpus, byte[] dictionary, int level) {
        try (ZstdStreamCodec codec = new ZstdStreamCodec(level, NebConfig.zstdWindowLog)) {
            if (dictionary.length != 0) {
                codec.loadDictionary(dictionary);
            }
            long size = 0;
            for (byte[] sample : corpus) {
                ByteBuffer source = ByteBuffer.allocateDirect(sample.length);
                source.put(sample)
                    .flip();
                ByteBuffer destination = ByteBuffer.allocateDirect(codec.compressBound(sample.length));
                codec.compress(source, destination);
                if (source.hasRemaining()) {
                    throw new IllegalStateException("NEB dictionary scorer did not consume a sample");
                }
                size = safeAdd(size, destination.position());
            }
            return size;
        }
    }

    private static long totalBytes(List<byte[]> corpus) {
        long size = 0;
        for (byte[] sample : corpus) {
            size = safeAdd(size, sample.length);
        }
        return size;
    }

    private static long scale(long value, long targetBytes, long sourceBytes) {
        if (value <= 0 || targetBytes <= 0 || sourceBytes <= 0) {
            return 0;
        }
        return (value * targetBytes + sourceBytes - 1) / sourceBytes;
    }

    private static long estimateDictionaryWireBytes(int dictionaryBytes, int version) {
        int bodyBytes = 1 + NebVarInt.sizeOf(version) + NebVarInt.sizeOf(dictionaryBytes) + dictionaryBytes;
        return (long) bodyBytes + NebVarInt.sizeOf(bodyBytes);
    }

    private static long safeAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    public static class Snapshot {

        private final int version;
        private final byte[] bytes;

        public Snapshot(int version, byte[] bytes) {
            if (version < 0 || bytes == null
                || bytes.length > MAX_DICTIONARY_BYTES
                || (version == 0) != (bytes.length == 0)) {
                throw new IllegalArgumentException("NEB: invalid dictionary snapshot");
            }
            this.version = version;
            this.bytes = bytes.clone();
        }

        public int version() {
            return version;
        }

        public int size() {
            return bytes.length;
        }

        public void writeTo(ByteBuf target) {
            target.writeBytes(bytes);
        }

        public byte[] copyBytes() {
            return bytes.clone();
        }
    }

}
