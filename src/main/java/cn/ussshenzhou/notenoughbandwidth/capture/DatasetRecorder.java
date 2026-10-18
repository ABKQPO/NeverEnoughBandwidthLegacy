package cn.ussshenzhou.notenoughbandwidth.capture;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import io.netty.buffer.ByteBuf;

public class DatasetRecorder {

    public static final long DEFAULT_LIMIT_MILLIS = 120_000L;
    public static final long DEFAULT_LIMIT_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_CAPTURE_SECONDS = 3600L;
    public static final long MAX_CAPTURE_MEBIBYTES = 1024L;

    private static final int FORMAT_VERSION = 1;
    private static final int QUEUE_CAPACITY = 64;
    private static final long POLL_MILLIS = 100L;
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final byte[] MAGIC = { 'N', 'E', 'B', 'D' };
    private static final DatasetRecorder GLOBAL = new DatasetRecorder();

    private final Object lock = new Object();
    private CaptureState state = CaptureState.IDLE;
    private ArrayBlockingQueue<DatasetRecord> queue;
    private Thread writer;
    private Path datasetPath;
    private Path analysisPath;
    private long startedAtMillis;
    private long limitMillis;
    private long limitBytes;
    private long acceptedRecords;
    private long acceptedBytes;
    private long droppedRecords;
    private Throwable failure;

    public static DatasetRecorder global() {
        return GLOBAL;
    }

    public DatasetStatus start(Path directory, String side, long seconds, long mebibytes) throws IOException {
        if (directory == null || side == null || side.isEmpty()) {
            throw new IllegalArgumentException("Dataset directory and side are required");
        }
        if (!isSafeSide(side)) {
            throw new IllegalArgumentException("Dataset side may contain only letters, digits, '.', '-' or '_'");
        }
        if (side.getBytes(StandardCharsets.UTF_8).length > 256) {
            throw new IllegalArgumentException("Dataset side label is too long");
        }
        if (seconds <= 0 || seconds > MAX_CAPTURE_SECONDS) {
            throw new IllegalArgumentException(
                "Capture duration must be between 1 and " + MAX_CAPTURE_SECONDS + " seconds");
        }
        if (mebibytes <= 0 || mebibytes > MAX_CAPTURE_MEBIBYTES) {
            throw new IllegalArgumentException("Capture size must be between 1 and " + MAX_CAPTURE_MEBIBYTES + " MiB");
        }
        long requestedMillis = seconds * 1000L;
        long requestedBytes = mebibytes * 1024L * 1024L;
        synchronized (lock) {
            if (state != CaptureState.IDLE) {
                throw new IllegalStateException("A bandwidth dataset capture is already active");
            }
            Files.createDirectories(directory);
            String stamp = FILE_STAMP.format(
                Instant.now()
                    .atZone(ZoneId.systemDefault()));
            String stem = "neb-dataset-" + side + '-' + stamp;
            datasetPath = directory.resolve(stem + ".bin");
            analysisPath = directory.resolve(stem + ".csv");
            startedAtMillis = System.currentTimeMillis();
            limitMillis = requestedMillis;
            limitBytes = requestedBytes;
            acceptedRecords = 0;
            acceptedBytes = 0;
            droppedRecords = 0;
            failure = null;
            queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
            state = CaptureState.ACTIVE;
            writer = new Thread(() -> writeDataset(side), "NEB Dataset Writer");
            writer.setDaemon(true);
            writer.start();
            return statusLocked(System.currentTimeMillis());
        }
    }

    public void stop() {
        synchronized (lock) {
            if (state == CaptureState.ACTIVE) {
                state = CaptureState.STOPPING;
            }
        }
    }

    public void record(boolean outbound, PacketCodecType codec, int wireBytes, int packetCount, ByteBuf payload) {
        record(outbound, codec, wireBytes, packetCount, payload == null ? 0 : payload.readableBytes(), payload);
    }

    public void record(boolean outbound, PacketCodecType codec, int wireBytes, int packetCount, int rawBytes,
        ByteBuf payload) {
        if (codec == null || payload == null || wireBytes <= 0 || packetCount <= 0) {
            return;
        }
        int payloadBytes = payload.readableBytes();
        if (rawBytes <= 0 || rawBytes > DatasetRecord.MAX_RAW_BYTES
            || payloadBytes <= 0
            || payloadBytes > DatasetRecord.MAX_PAYLOAD_BYTES) {
            return;
        }
        DatasetRecord record;
        synchronized (lock) {
            if (state != CaptureState.ACTIVE) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - startedAtMillis >= limitMillis) {
                state = CaptureState.STOPPING;
                return;
            }
            if (rawBytes > limitBytes - acceptedBytes || queue.remainingCapacity() == 0) {
                droppedRecords++;
                if (rawBytes > limitBytes - acceptedBytes) {
                    state = CaptureState.STOPPING;
                }
                return;
            }
            byte[] bytes = new byte[payloadBytes];
            payload.getBytes(payload.readerIndex(), bytes);
            record = new DatasetRecord(now, outbound, codec, rawBytes, wireBytes, packetCount, bytes);
            acceptedBytes += rawBytes;
            acceptedRecords++;
            if (!queue.offer(record)) {
                acceptedBytes -= rawBytes;
                acceptedRecords--;
                droppedRecords++;
                return;
            }
            if (acceptedBytes >= limitBytes) {
                state = CaptureState.STOPPING;
            }
        }
    }

    public DatasetStatus status() {
        synchronized (lock) {
            return statusLocked(System.currentTimeMillis());
        }
    }

    private DatasetStatus statusLocked(long now) {
        long elapsed = startedAtMillis == 0 ? 0 : Math.max(0, now - startedAtMillis);
        return new DatasetStatus(
            state == CaptureState.ACTIVE,
            state == CaptureState.STOPPING,
            datasetPath,
            analysisPath,
            startedAtMillis,
            elapsed,
            acceptedRecords,
            acceptedBytes,
            droppedRecords,
            limitBytes,
            limitMillis);
    }

    private void writeDataset(String side) {
        Path currentDataset;
        Path currentAnalysis;
        ArrayBlockingQueue<DatasetRecord> currentQueue;
        long currentStarted;
        synchronized (lock) {
            currentDataset = datasetPath;
            currentAnalysis = analysisPath;
            currentQueue = queue;
            currentStarted = startedAtMillis;
        }
        try (DataOutputStream output = new DataOutputStream(
            new BufferedOutputStream(
                Files.newOutputStream(
                    currentDataset,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)))) {
            writeHeader(output, side, currentStarted);
            long previousTimestamp = currentStarted;
            while (true) {
                DatasetRecord record = currentQueue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (record != null) {
                    writeRecord(output, record, previousTimestamp);
                    previousTimestamp = record.timestampMillis();
                } else if (isStopping() || hasExpired()) {
                    break;
                }
            }
        } catch (Throwable t) {
            synchronized (lock) {
                failure = t;
            }
            NebLog.error("Failed to write the bandwidth dataset.", t);
        } finally {
            try {
                DatasetAnalyzer.analyze(currentDataset, currentAnalysis);
            } catch (Throwable t) {
                synchronized (lock) {
                    failure = t;
                }
                NebLog.error("Failed to analyze the bandwidth dataset.", t);
            }
            synchronized (lock) {
                state = CaptureState.IDLE;
                queue = null;
                writer = null;
            }
        }
    }

    private boolean isStopping() {
        synchronized (lock) {
            return state == CaptureState.STOPPING;
        }
    }

    private boolean hasExpired() {
        synchronized (lock) {
            if (state == CaptureState.ACTIVE && System.currentTimeMillis() - startedAtMillis >= limitMillis) {
                state = CaptureState.STOPPING;
                return true;
            }
            return state == CaptureState.STOPPING;
        }
    }

    private static void writeHeader(DataOutputStream output, String side, long startedAt) throws IOException {
        output.write(MAGIC);
        output.writeInt(FORMAT_VERSION);
        output.writeLong(startedAt);
        byte[] sideBytes = side.getBytes(StandardCharsets.UTF_8);
        output.writeShort(sideBytes.length);
        output.write(sideBytes);
    }

    private static void writeRecord(DataOutputStream output, DatasetRecord record, long previousTimestamp)
        throws IOException {
        output.writeByte(record.outbound() ? 1 : 0);
        output.writeByte(
            record.codec()
                .wireId());
        output.writeLong(Math.max(0, record.timestampMillis() - previousTimestamp));
        output.writeInt(record.rawBytes());
        output.writeInt(record.wireBytes());
        output.writeInt(record.packetCount());
        byte[] payload = record.copyPayload();
        output.writeInt(payload.length);
        output.write(payload);
    }

    private static boolean isSafeSide(String side) {
        for (int i = 0; i < side.length(); i++) {
            char character = side.charAt(i);
            if (!(Character.isLetterOrDigit(character) || character == '.' || character == '-' || character == '_')) {
                return false;
            }
        }
        return true;
    }

    private enum CaptureState {
        IDLE,
        ACTIVE,
        STOPPING
    }
}
