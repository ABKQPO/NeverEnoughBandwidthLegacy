package cn.ussshenzhou.notenoughbandwidth.codec.zstd;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.github.luben.zstd.Zstd;

import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.codec.DictionaryCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecRegistry;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
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
    private static final long DICTIONARY_AMORTIZATION_FACTOR = 128L;
    private static final long RETRAIN_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final ExecutorService TRAINER = new ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(8),
        task -> {
            Thread thread = new Thread(task, "NEB Dictionary Trainer");
            thread.setDaemon(true);
            return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());

    private final Object lock = new Object();
    private volatile Snapshot current = EMPTY;
    private List<byte[]> samples = new ArrayList<>();
    private int sampleBytes;
    private int versionCounter;
    private long generation;
    private volatile long lastTraining;
    private volatile boolean training;
    private volatile boolean coolingDown;
    private PacketCodecType sampleType;

    public BandwidthDictionary() {}

    public static long minimumTrafficFor(int dictionaryBytes) {
        if (dictionaryBytes <= 0) {
            return MIN_DICTIONARY_TRAFFIC;
        }
        return Math.max(MIN_DICTIONARY_TRAFFIC, (long) dictionaryBytes * DICTIONARY_AMORTIZATION_FACTOR);
    }

    public Snapshot snapshot() {
        return NebConfig.enableBandwidthDictionary ? current : EMPTY;
    }

    public void observe(ByteBuf packet) {
        observe(packet, PacketCodecType.ZSTD_STREAM);
    }

    public void observe(ByteBuf packet, PacketCodecType codecType) {
        if (!isSupported(codecType)) {
            return;
        }
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
            if (sampleType != null && sampleType != codecType) {
                samples.clear();
                sampleBytes = 0;
            }
            sampleType = codecType;
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
            PacketCodecType taskType = sampleType;
            try {
                TRAINER.execute(() -> train(corpus, taskGeneration, taskType));
            } catch (RejectedExecutionException rejected) {
                training = false;
                lastTraining = now;
                coolingDown = true;
                NebLog.debug("Bandwidth dictionary training queue is full; deferring this candidate.");
            }
        }
    }

    public void reset() {
        synchronized (lock) {
            generation++;
            current = EMPTY;
            samples.clear();
            sampleBytes = 0;
            versionCounter = 0;
            sampleType = null;
            training = false;
            coolingDown = false;
        }
    }

    private void train(List<byte[]> corpus, long taskGeneration, PacketCodecType codecType) {
        try {
            List<byte[]> trainingSamples = new ArrayList<>();
            List<Integer> validationIndexes = new ArrayList<>();
            for (int i = 0; i < corpus.size(); i++) {
                if (i % 4 == 0) {
                    validationIndexes.add(i);
                } else {
                    trainingSamples.add(corpus.get(i));
                }
            }
            byte[] destination = new byte[MAX_DICTIONARY_BYTES];
            long size = Zstd.trainFromBuffer(trainingSamples.toArray(new byte[0][]), destination, false);
            if (Zstd.isError(size) || size < 512 || size > MAX_DICTIONARY_BYTES) {
                return;
            }
            byte[] dictionary = Arrays.copyOf(destination, (int) size);
            Snapshot baseline = current;
            long validationBytes = totalBytes(corpus, validationIndexes);
            long baselineSize = score(corpus, validationIndexes, baseline.bytes, codecType);
            long candidateSize = score(corpus, validationIndexes, dictionary, codecType);
            long expectedTraffic = minimumTrafficFor(dictionary.length);
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
                if (generation == taskGeneration) {
                    training = false;
                    lastTraining = System.nanoTime();
                    coolingDown = true;
                }
            }
        }
    }

    private static long score(List<byte[]> corpus, List<Integer> validationIndexes, byte[] dictionary,
        PacketCodecType codecType) {
        try (PacketCodec codec = PacketCodecRegistry.create(codecType)) {
            if (!(codec instanceof DictionaryCodec aware)) {
                throw new IllegalStateException("NEB dictionary scorer requires a dictionary codec");
            }
            if (dictionary.length != 0) {
                aware.loadDictionary(dictionary);
            }
            int maxBound = 0;
            for (byte[] sample : corpus) {
                int bound = codec.compressBound(sample.length);
                if (bound <= 0) {
                    throw new IllegalStateException("NEB dictionary scorer received an invalid codec bound");
                }
                maxBound = Math.max(maxBound, bound);
            }
            ByteBuffer source = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES);
            ByteBuffer destination = ByteBuffer.allocateDirect(maxBound);
            long size = 0;
            int validationIndex = 0;
            for (int i = 0; i < corpus.size(); i++) {
                byte[] sample = corpus.get(i);
                source.clear();
                source.put(sample);
                source.flip();
                destination.clear();
                destination.limit(codec.compressBound(sample.length));
                codec.compress(source, destination);
                if (source.hasRemaining()) {
                    throw new IllegalStateException("NEB dictionary scorer did not consume a sample");
                }
                if (validationIndex < validationIndexes.size() && validationIndexes.get(validationIndex) == i) {
                    size = safeAdd(size, frameWireBytes(sample.length, destination.position()));
                    validationIndex++;
                }
            }
            return size;
        }
    }

    private static long frameWireBytes(int rawSize, int compressedSize) {
        int frameSize = 1 + NebVarInt.sizeOf(rawSize) + compressedSize;
        long wireSize = (long) frameSize + NebVarInt.sizeOf(frameSize);
        return wireSize >= rawSize ? rawSize : wireSize;
    }

    private static long totalBytes(List<byte[]> corpus, List<Integer> indexes) {
        long size = 0;
        for (int index : indexes) {
            size = safeAdd(size, corpus.get(index).length);
        }
        return size;
    }

    private static boolean isSupported(PacketCodecType codecType) {
        return codecType == PacketCodecType.ZSTD_STREAM || codecType == PacketCodecType.ZSTD_BLOCK;
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
