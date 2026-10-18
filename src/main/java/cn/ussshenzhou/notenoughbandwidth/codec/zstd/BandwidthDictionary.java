package cn.ussshenzhou.notenoughbandwidth.codec.zstd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.github.luben.zstd.Zstd;

import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import io.netty.buffer.ByteBuf;

public class BandwidthDictionary {

    public static final int MAX_DICTIONARY_BYTES = 32 * 1024;
    public static final int MAX_SAMPLE_BYTES = 16 * 1024;
    public static final Snapshot EMPTY = new Snapshot(0, new byte[0]);

    private static final int MIN_SAMPLES = 64;
    private static final int MIN_SAMPLE_BYTES = 64 * 1024;
    private static final int MAX_SAMPLES = 512;
    private static final int MAX_BUFFERED_BYTES = 1024 * 1024;
    private static final long RETRAIN_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final Object LOCK = new Object();
    private static final ExecutorService TRAINER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "NEB Dictionary Trainer");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile Snapshot current = EMPTY;
    private static List<byte[]> samples = new ArrayList<>();
    private static int sampleBytes;
    private static long generation;
    private static volatile long lastTraining;
    private static volatile boolean training;
    private static volatile boolean coolingDown;

    public static Snapshot snapshot() {
        return NebConfig.enableBandwidthDictionary ? current : EMPTY;
    }

    public static void observe(ByteBuf packet) {
        if (!NebConfig.enableBandwidthDictionary || packet.readableBytes() < 32) {
            return;
        }
        if (training || (coolingDown && System.nanoTime() - lastTraining < RETRAIN_NANOS)) {
            return;
        }
        synchronized (LOCK) {
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

    public static void reset() {
        synchronized (LOCK) {
            generation++;
            current = EMPTY;
            samples.clear();
            sampleBytes = 0;
            // Keep a running worker reserved until its finally block, including across server restarts.
            coolingDown = false;
        }
    }

    private static void train(List<byte[]> corpus, long taskGeneration, int level) {
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
            long baselineSize = score(validationSamples, baseline.bytes, level);
            long candidateSize = score(validationSamples, dictionary, level);
            if (candidateSize * 100 > baselineSize * 98) {
                return;
            }
            synchronized (LOCK) {
                if (generation == taskGeneration && NebConfig.enableBandwidthDictionary
                    && current == baseline
                    && baseline.version < Integer.MAX_VALUE) {
                    current = new Snapshot(baseline.version + 1, dictionary);
                    NebLog.debug(
                        "Published bandwidth dictionary {} ({} bytes, validation {} -> {}).",
                        current.version,
                        size,
                        baselineSize,
                        candidateSize);
                }
            }
        } catch (Throwable failure) {
            NebLog.debug("Bandwidth dictionary training failed: {}", failure.toString());
        } finally {
            synchronized (LOCK) {
                training = false;
                if (generation == taskGeneration) {
                    lastTraining = System.nanoTime();
                    coolingDown = true;
                }
            }
        }
    }

    private static long score(List<byte[]> corpus, byte[] dictionary, int level) {
        long size = 0;
        for (byte[] sample : corpus) {
            size += dictionary.length == 0 ? Zstd.compress(sample, level).length
                : Zstd.compressUsingDict(sample, dictionary, level).length;
        }
        return size;
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

    private BandwidthDictionary() {}
}
