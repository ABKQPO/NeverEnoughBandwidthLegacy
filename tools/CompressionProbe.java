import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import com.github.luben.zstd.Zstd;

import cn.ussshenzhou.notenoughbandwidth.codec.DictionaryCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.DeflateStreamCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import cn.ussshenzhou.notenoughbandwidth.codec.zstd.BandwidthDictionary;
import cn.ussshenzhou.notenoughbandwidth.codec.zstd.ZstdBlockCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.zstd.ZstdStreamCodec;
import cn.ussshenzhou.notenoughbandwidth.capture.DatasetAnalyzer;

public class CompressionProbe {

    private static final int DEFAULT_FRAMES = 256;
    private static final int DEFAULT_ITERATIONS = 1;
    private static final long DEFAULT_SEED = 0x4E45424CL;
    private static final int[] ZSTD_LEVELS = { 3, 19 };
    private static final int MAX_DATASET_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private static final int MAX_DATASET_RAW_BYTES = MAX_DATASET_PAYLOAD_BYTES * 2;
    private static final int DICTIONARY_WINDOW_LOG = 22;
    private static final long DICTIONARY_MIN_TRAFFIC = 256L * 1024L;

    public static void main(String[] args) {
        Options options = Options.parse(args);
        String result;
        if (options.trainDictionary) {
            result = trainDictionary(options);
        } else {
            StringBuilder output = new StringBuilder();
            appendLine(
                output,
                "scenario,codec,iteration,frames,packets,stored_frames,stored_wire_bytes,input_bytes,packet_metadata_bytes,raw_bytes,raw_input_delta_bytes,codec_payload_bytes,compression_frame_header_bytes,raw_size_metadata_bytes,frame_length_bytes,stored_packet_length_bytes,wire_bytes,codec_ratio,wire_ratio,transport_metadata_percent,frame_flush_overhead_bytes,saved_percent");
            if (options.dataset != null) {
                if (options.analyzeDataset) {
                    Path csv = options.dataset.resolveSibling(
                        options.dataset.getFileName().toString().replaceFirst("\\.[^.]+$", ".csv"));
                    try {
                        DatasetAnalyzer.analyze(options.dataset, csv);
                    } catch (IOException failure) {
                        throw new IllegalStateException("Could not analyze dataset " + options.dataset, failure);
                    }
                }
                runDataset(options.dataset, options, output);
            } else {
                for (int iteration = 0; iteration < options.iterations; iteration++) {
                    long seed = options.seed + iteration;
                    for (Scenario scenario : scenarios(options.frames, seed)) {
                        runScenario(scenario, options, iteration + 1, output);
                    }
                }
            }
            result = output.toString();
        }
        System.out.print(result);
        if (options.output != null) {
            try {
                Path parent = options.output.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                    options.output,
                    result,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            } catch (IOException failure) {
                throw new IllegalStateException("Could not write probe output " + options.output, failure);
            }
        }
    }

    private static String trainDictionary(Options options) {
        if (options.dataset == null) {
            throw new IllegalArgumentException("--train-dictionary requires --dataset=PATH");
        }
        if (options.dictionaryOut == null) {
            throw new IllegalArgumentException("--train-dictionary requires --dictionary-out=PATH");
        }
        DatasetInput input;
        try {
            input = readDataset(options.dataset);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read dataset " + options.dataset, failure);
        }
        List<Frame> samples = input.frames.stream()
            .filter(frame -> frame.outbound && !frame.stored && frame.codec == PacketCodecType.ZSTD_STREAM)
            .toList();
        if (samples.size() < 64) {
            throw new IllegalArgumentException("Dataset has fewer than 64 outbound zstd_stream samples");
        }
        List<byte[]> training = new ArrayList<>();
        List<byte[]> validation = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            byte[] payload = samples.get(i).payload;
            (i % 4 == 0 ? validation : training).add(
                Arrays.copyOf(payload, Math.min(payload.length, BandwidthDictionary.MAX_SAMPLE_BYTES)));
        }
        byte[] destination = new byte[BandwidthDictionary.MAX_DICTIONARY_BYTES];
        long dictionarySize = Zstd.trainFromBuffer(training.toArray(new byte[0][]), destination, false);
        if (Zstd.isError(dictionarySize)
            || dictionarySize < 512
            || dictionarySize > BandwidthDictionary.MAX_DICTIONARY_BYTES) {
            throw new IllegalStateException("Zstd dictionary training failed: " + Zstd.getErrorName(dictionarySize));
        }
        byte[] dictionary = Arrays.copyOf(destination, (int) dictionarySize);
        int level = options.zstdLevels[0];
        long baselineBytes = streamScore(validation, new byte[0], level);
        long candidateBytes = streamScore(validation, dictionary, level);
        long validationRaw = totalBytes(validation);
        long expectedTraffic = Math.max(DICTIONARY_MIN_TRAFFIC, dictionary.length * 64L);
        long projectedBaseline = scale(baselineBytes, expectedTraffic, validationRaw);
        long projectedCandidate = scale(candidateBytes, expectedTraffic, validationRaw);
        long dictionaryFrameBytes = estimateDictionaryWireBytes(dictionary.length, 1);
        long netSaved = projectedBaseline - projectedCandidate - dictionaryFrameBytes;
        boolean accepted = candidateBytes * 100 <= baselineBytes * 98 && netSaved > 0;
        if (accepted) {
            try {
                Path parent = options.dictionaryOut.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(options.dictionaryOut, dictionary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (IOException failure) {
                throw new IllegalStateException("Could not write dictionary " + options.dictionaryOut, failure);
            }
        }
        StringBuilder output = new StringBuilder();
        appendLine(output,
            "dataset,codec,level,training_samples,validation_samples,validation_raw_bytes,dictionary_bytes,baseline_stream_bytes,candidate_stream_bytes,projected_baseline_bytes,projected_candidate_bytes,dictionary_frame_bytes,net_saved_bytes,accepted");
        appendLine(output, String.format(Locale.ROOT,
            "%s,zstd_stream,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s",
            options.dataset,
            level,
            training.size(),
            validation.size(),
            validationRaw,
            dictionary.length,
            baselineBytes,
            candidateBytes,
            projectedBaseline,
            projectedCandidate,
            dictionaryFrameBytes,
            netSaved,
            accepted));
        return output.toString();
    }

    private static long streamScore(List<byte[]> samples, byte[] dictionary, int level) {
        try (ZstdStreamCodec codec = new ZstdStreamCodec(level, DICTIONARY_WINDOW_LOG)) {
            if (dictionary.length != 0) {
                ((DictionaryCodec) codec).loadDictionary(dictionary);
            }
            long result = 0;
            for (byte[] sample : samples) {
                ByteBuffer source = direct(sample);
                ByteBuffer destination = ByteBuffer.allocateDirect(codec.compressBound(sample.length));
                codec.compress(source, destination);
                if (source.hasRemaining()) {
                    throw new IllegalStateException("Dictionary probe compressor did not consume a sample");
                }
                result = safeAdd(result, destination.position());
            }
            return result;
        }
    }

    private static long totalBytes(List<byte[]> samples) {
        long result = 0;
        for (byte[] sample : samples) {
            result = safeAdd(result, sample.length);
        }
        return result;
    }

    private static long scale(long value, long targetBytes, long sourceBytes) {
        if (value <= 0 || targetBytes <= 0 || sourceBytes <= 0) {
            return 0;
        }
        return (value * targetBytes + sourceBytes - 1) / sourceBytes;
    }

    private static long estimateDictionaryWireBytes(int dictionaryBytes, int version) {
        int bodyBytes = 1 + varIntSize(version) + varIntSize(dictionaryBytes) + dictionaryBytes;
        return (long) bodyBytes + varIntSize(bodyBytes);
    }

    private static long safeAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static void runDataset(Path dataset, Options options, StringBuilder output) {
        try {
            DatasetInput input = readDataset(dataset);
            for (boolean outbound : new boolean[] { true, false }) {
                List<Frame> records = new ArrayList<>();
                for (Frame frame : input.frames) {
                    if (frame.outbound == outbound) {
                        records.add(frame);
                    }
                }
                if (records.isEmpty()) {
                    continue;
                }
                Scenario scenario = new Scenario(
                    "dataset_" + (outbound ? "outbound" : "inbound"),
                    records.size(),
                    0,
                    0,
                    Pattern.REPETITIVE,
                    options.seed);
                for (int iteration = 1; iteration <= options.iterations; iteration++) {
                    for (int level : options.zstdLevels) {
                        measure(scenario, "zstd_stream_l" + level, new ZstdStreamCodec(level, 22), records,
                            iteration, output);
                        measure(scenario, "zstd_block_l" + level, new ZstdBlockCodec(level), records, iteration,
                            output);
                    }
                    measure(scenario, "deflate_stream", new DeflateStreamCodec(3), records, iteration, output);
                    reportDatasetStored(scenario, records, iteration, output);
                    reportDatasetRecorded(scenario, records, iteration, output);
                    reportDatasetAggregate(scenario, records, options, iteration, output);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read dataset " + dataset, failure);
        }
    }

    private static List<Scenario> scenarios(int frames, long seed) {
        List<Scenario> scenarios = new ArrayList<>();
        scenarios.add(new Scenario("tiny_repetitive", frames, 32, 1, Pattern.REPETITIVE, seed));
        scenarios.add(new Scenario("small_repetitive", frames, 64, 16, Pattern.REPETITIVE, seed));
        scenarios.add(new Scenario("mixed", frames, 256, 16, Pattern.MIXED, seed));
        scenarios.add(new Scenario("large_mixed", frames, 1024, 16, Pattern.MIXED, seed));
        scenarios.add(new Scenario("incompressible", frames, 128, 16, Pattern.RANDOM, seed));
        return scenarios;
    }

    private static void runScenario(Scenario scenario, Options options, int iteration, StringBuilder output) {
        List<Frame> frames = createFrames(scenario);
        for (int level : options.zstdLevels) {
            measure(scenario, "zstd_stream_l" + level, new ZstdStreamCodec(level, 22), frames, iteration, output);
            measure(scenario, "zstd_block_l" + level, new ZstdBlockCodec(level), frames, iteration, output);
        }
        measure(scenario, "deflate_stream", new DeflateStreamCodec(3), frames, iteration, output);
        reportStored(scenario, frames, iteration, output);
        reportSyntheticAggregate(scenario, frames, options, iteration, output);
    }

    private static List<Frame> createFrames(Scenario scenario) {
        List<Frame> frames = new ArrayList<>(scenario.frameCount);
        Random random = new Random(scenario.seed);
        for (int frameIndex = 0; frameIndex < scenario.frameCount; frameIndex++) {
            ByteBuffer payload = ByteBuffer.allocate(scenario.packetSize * scenario.packetsPerFrame + 64);
            for (int packet = 0; packet < scenario.packetsPerFrame; packet++) {
                int size = scenario.packetSize + ((frameIndex + packet) & 3) - 1;
                writeVarInt(payload, size);
                for (int offset = 0; offset < size; offset++) {
                    payload.put(value(scenario.pattern, frameIndex, packet, offset, random));
                }
            }
            payload.flip();
            byte[] bytes = new byte[payload.remaining()];
            payload.get(bytes);
            frames.add(new Frame(bytes, scenario.packetsPerFrame));
        }
        return frames;
    }

    private static byte value(Pattern pattern, int frame, int packet, int offset, Random random) {
        if (pattern == Pattern.RANDOM) {
            return (byte) random.nextInt(256);
        }
        int marker = (offset + packet * 13 + frame * 17) & 0x3FF;
        if (pattern == Pattern.MIXED && marker >= 820) {
            return (byte) random.nextInt(256);
        }
        return (byte) ('A' + (marker & 15));
    }

    private static void measure(Scenario scenario, String name, PacketCodec codec, List<Frame> frames, int iteration,
        StringBuilder output) {
        PacketCodec decoder = createDecoder(name);
        long raw = 0L;
        long codecBytes = 0L;
        long wireBytes = 0L;
        long inputBytes = 0L;
        long packetMetadataBytes = 0L;
        long nebHeaderBytes = 0L;
        long compressionFrameHeaderBytes = 0L;
        long rawSizeMetadataBytes = 0L;
        long frameLengthBytes = 0L;
        long storedPacketLengthBytes = 0L;
        long packets = 0L;
        long storedFrames = 0L;
        long storedWireBytes = 0L;
        try {
            for (Frame frame : frames) {
                if (frame.stored) {
                    storedFrames++;
                    raw += frame.rawBytes;
                    inputBytes += frame.payload.length;
                    packetMetadataBytes += Math.max(0, frame.rawBytes - frame.payload.length);
                    storedPacketLengthBytes += Math.max(0, frame.recordedWireBytes - frame.payload.length);
                    wireBytes += frame.recordedWireBytes;
                    storedWireBytes += frame.recordedWireBytes;
                    packets += frame.packetCount;
                    continue;
                }
                PacketMetrics metrics = packetMetrics(frame.payload, frame.packetCount);
                ByteBuffer source = direct(frame.payload);
                ByteBuffer compressed = ByteBuffer.allocateDirect(codec.compressBound(frame.payload.length));
                codec.compress(source, compressed);
                int compressedSize = compressed.position();
                compressed.flip();
                ByteBuffer expanded = ByteBuffer.allocateDirect(frame.payload.length);
                decoder.decompress(compressed, expanded);
                expanded.flip();
                if (!matches(expanded, frame.payload)) {
                    throw new IllegalStateException(name + " round trip mismatch");
                }
                int header = 1 + varIntSize(frame.payload.length);
                int frameLength = header + compressedSize;
                raw += frame.rawBytes;
                inputBytes += frame.payload.length;
                packetMetadataBytes += metrics.packetMetadataBytes;
                codecBytes += compressedSize;
                nebHeaderBytes += header;
                compressionFrameHeaderBytes++;
                rawSizeMetadataBytes += varIntSize(frame.payload.length);
                int outerLength = varIntSize(frameLength);
                frameLengthBytes += outerLength;
                wireBytes += compressedSize + header + outerLength;
                packets += frame.packetCount;
            }
            long aggregateBytes = aggregateCodecBytes(name, frames);
            appendResult(
                output,
                scenario,
                name,
                iteration,
                frames.size(),
                packets,
                storedFrames,
                storedWireBytes,
                inputBytes,
                packetMetadataBytes,
                raw,
                codecBytes,
                nebHeaderBytes,
                compressionFrameHeaderBytes,
                rawSizeMetadataBytes,
                frameLengthBytes,
                storedPacketLengthBytes,
                wireBytes,
                aggregateBytes);
        } finally {
            codec.close();
            decoder.close();
        }
    }

    private static PacketCodec createDecoder(String name) {
        if (name.startsWith("zstd_stream_l")) {
            return new ZstdStreamCodec(level(name), 22);
        }
        if (name.startsWith("zstd_block_l")) {
            return new ZstdBlockCodec(level(name));
        }
        return new DeflateStreamCodec(3);
    }

    private static int level(String name) {
        return Integer.parseInt(name.substring(name.lastIndexOf('l') + 1));
    }

    private static long aggregateCodecBytes(String name, List<Frame> frames) {
        int total = 0;
        for (Frame frame : frames) {
            if (!frame.stored) {
                if (Integer.MAX_VALUE - total < frame.payload.length) {
                    return -1L;
                }
                total += frame.payload.length;
            }
        }
        if (total == 0) {
            return 0L;
        }
        byte[] joined = new byte[total];
        int offset = 0;
        for (Frame frame : frames) {
            if (!frame.stored) {
                System.arraycopy(frame.payload, 0, joined, offset, frame.payload.length);
                offset += frame.payload.length;
            }
        }
        PacketCodec codec = createCodec(name);
        PacketCodec decoder = createDecoder(name);
        try {
            ByteBuffer source = direct(joined);
            ByteBuffer compressed = ByteBuffer.allocateDirect(codec.compressBound(total));
            codec.compress(source, compressed);
            if (source.hasRemaining()) {
                throw new IllegalStateException(name + " aggregate compressor did not consume input");
            }
            int compressedSize = compressed.position();
            compressed.flip();
            ByteBuffer expanded = ByteBuffer.allocateDirect(total);
            decoder.decompress(compressed, expanded);
            expanded.flip();
            if (!matches(expanded, joined)) {
                throw new IllegalStateException(name + " aggregate round trip mismatch");
            }
            return compressedSize;
        } finally {
            codec.close();
            decoder.close();
        }
    }

    private static PacketCodec createCodec(String name) {
        if (name.startsWith("zstd_stream_l")) {
            return new ZstdStreamCodec(level(name), 22);
        }
        if (name.startsWith("zstd_block_l")) {
            return new ZstdBlockCodec(level(name));
        }
        return new DeflateStreamCodec(3);
    }

    private static void reportStored(Scenario scenario, List<Frame> frames, int iteration, StringBuilder output) {
        long raw = 0L;
        long packets = 0L;
        long wire = 0L;
        long packetMetadataBytes = 0L;
        long storedPacketLengthBytes = 0L;
        for (Frame frame : frames) {
            raw += frame.payload.length;
            packets += frame.packetCount;
            PacketMetrics metrics = packetMetrics(frame.payload, frame.packetCount);
            packetMetadataBytes += metrics.packetMetadataBytes;
            int offset = 0;
            while (offset < frame.payload.length) {
                int[] length = readVarInt(frame.payload, offset);
                if (length[0] <= 0 || length[0] > frame.payload.length - length[1]) {
                    throw new IllegalStateException("Invalid generated packet payload");
                }
                wire += length[0] + varIntSize(length[0]);
                storedPacketLengthBytes += varIntSize(length[0]);
                offset = length[1] + length[0];
            }
        }
        appendResult(
            output,
            scenario,
            "stored",
            iteration,
            frames.size(),
            packets,
            frames.size(),
            wire,
            raw,
            packetMetadataBytes,
            raw,
            0L,
            0L,
            0L,
            0L,
            0L,
            storedPacketLengthBytes,
            wire,
            0L);
    }

    private static void reportDatasetStored(Scenario scenario, List<Frame> frames, int iteration,
        StringBuilder output) {
        long raw = 0L;
        long packets = 0L;
        long storedFrames = 0L;
        long storedWireBytes = 0L;
        long input = 0L;
        long packetMetadataBytes = 0L;
        for (Frame frame : frames) {
            raw += frame.rawBytes;
            packets += frame.packetCount;
            if (frame.stored) {
                storedFrames++;
                storedWireBytes += frame.recordedWireBytes;
            }
            input += frame.payload.length;
            packetMetadataBytes += Math.max(0, frame.rawBytes - frame.payload.length);
        }
        appendResult(
            output,
            scenario,
            "stored",
            iteration,
            frames.size(),
            packets,
            storedFrames,
            storedWireBytes,
            input,
            packetMetadataBytes,
            raw,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            raw,
            0L);
    }

    private static void reportDatasetRecorded(Scenario scenario, List<Frame> frames, int iteration,
        StringBuilder output) {
        long raw = 0L;
        long wire = 0L;
        long packets = 0L;
        long storedFrames = 0L;
        long storedWireBytes = 0L;
        long input = 0L;
        long packetMetadataBytes = 0L;
        for (Frame frame : frames) {
            raw += frame.rawBytes;
            wire += frame.recordedWireBytes;
            packets += frame.packetCount;
            if (frame.stored) {
                storedFrames++;
                storedWireBytes += frame.recordedWireBytes;
            }
            input += frame.payload.length;
            packetMetadataBytes += Math.max(0, frame.rawBytes - frame.payload.length);
        }
        appendResult(
            output,
            scenario,
            "recorded",
            iteration,
            frames.size(),
            packets,
            storedFrames,
            storedWireBytes,
            input,
            packetMetadataBytes,
            raw,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            wire,
            0L);
    }

    /**
     * Measures one continuous codec stream after restoring stored packet length prefixes.
     * Compressed records keep their captured channel-indexed payload, so the result isolates
     * frame and small-packet overhead without pretending to reverse the channel index.
     */
    private static void reportDatasetAggregate(Scenario scenario, List<Frame> frames, Options options, int iteration,
        StringBuilder output) {
        Frame aggregate = aggregateFrame(frames);
        if (aggregate == null) {
            return;
        }
        reportAggregate(scenario, aggregate, options, iteration, output);
    }

    private static void reportSyntheticAggregate(Scenario scenario, List<Frame> frames, Options options,
        int iteration, StringBuilder output) {
        Frame aggregate = concatenateFrames(frames);
        reportAggregate(scenario, aggregate, options, iteration, output);
    }

    private static void reportAggregate(Scenario scenario, Frame aggregate, Options options, int iteration,
        StringBuilder output) {
        Scenario aggregateScenario = new Scenario(
            scenario.name + "_aggregate",
            1,
            aggregate.payload.length,
            aggregate.packetCount,
            Pattern.REPETITIVE,
            options.seed);
        List<Frame> singleFrame = List.of(aggregate);
        for (int level : options.zstdLevels) {
            measure(
                aggregateScenario,
                "zstd_stream_l" + level,
                new ZstdStreamCodec(level, 22),
                singleFrame,
                iteration,
                output);
            measure(
                aggregateScenario,
                "zstd_block_l" + level,
                new ZstdBlockCodec(level),
                singleFrame,
                iteration,
                output);
        }
        measure(
            aggregateScenario,
            "deflate_stream",
            new DeflateStreamCodec(3),
            singleFrame,
            iteration,
            output);
    }

    private static Frame aggregateFrame(List<Frame> frames) {
        long joinedBytes = 0L;
        long recordedRawBytes = 0L;
        int packetCount = 0;
        boolean outbound = true;
        for (Frame frame : frames) {
            if (Long.MAX_VALUE - recordedRawBytes < frame.rawBytes) {
                return null;
            }
            recordedRawBytes += frame.rawBytes;
            long frameBytes = frame.payload.length + (frame.stored ? varIntSize(frame.payload.length) : 0L);
            if (Long.MAX_VALUE - joinedBytes < frameBytes) {
                return null;
            }
            joinedBytes += frameBytes;
            if (Integer.MAX_VALUE - packetCount < frame.packetCount) {
                return null;
            }
            packetCount += frame.packetCount;
            outbound = frame.outbound;
        }
        if (joinedBytes <= 0L || joinedBytes > Integer.MAX_VALUE || recordedRawBytes > Integer.MAX_VALUE) {
            return null;
        }
        ByteArrayOutputStream joined = new ByteArrayOutputStream((int) joinedBytes);
        for (Frame frame : frames) {
            if (frame.stored) {
                writeVarInt(joined, frame.payload.length);
            }
            joined.write(frame.payload, 0, frame.payload.length);
        }
        byte[] payload = joined.toByteArray();
        if (payload.length != joinedBytes) {
            throw new IllegalStateException("Dataset aggregate size is inconsistent");
        }
        return new Frame(payload, packetCount, outbound, false, PacketCodecType.ZSTD_STREAM, (int) recordedRawBytes, 0);
    }

    private static Frame concatenateFrames(List<Frame> frames) {
        long joinedBytes = 0L;
        long rawBytes = 0L;
        int packetCount = 0;
        for (Frame frame : frames) {
            if (Long.MAX_VALUE - joinedBytes < frame.payload.length
                || Long.MAX_VALUE - rawBytes < frame.rawBytes
                || Integer.MAX_VALUE - packetCount < frame.packetCount) {
                throw new IllegalStateException("Synthetic aggregate exceeds probe limits");
            }
            joinedBytes += frame.payload.length;
            rawBytes += frame.rawBytes;
            packetCount += frame.packetCount;
        }
        if (joinedBytes <= 0L || joinedBytes > Integer.MAX_VALUE || rawBytes > Integer.MAX_VALUE) {
            throw new IllegalStateException("Synthetic aggregate exceeds probe limits");
        }
        byte[] payload = new byte[(int) joinedBytes];
        int offset = 0;
        for (Frame frame : frames) {
            System.arraycopy(frame.payload, 0, payload, offset, frame.payload.length);
            offset += frame.payload.length;
        }
        return new Frame(payload, packetCount, true, false, PacketCodecType.ZSTD_STREAM, (int) rawBytes, 0);
    }

    private static DatasetInput readDataset(Path dataset) throws IOException {
        List<Frame> frames = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(
            new BufferedInputStream(Files.newInputStream(dataset)))) {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (magic[0] != 'N' || magic[1] != 'E' || magic[2] != 'B' || magic[3] != 'D') {
                throw new IOException("Unknown NEB dataset header");
            }
            if (input.readInt() != 1) {
                throw new IOException("Unsupported NEB dataset version");
            }
            long startedAt = input.readLong();
            if (startedAt < 0L) {
                throw new IOException("Invalid dataset start timestamp");
            }
            int sideLength = input.readUnsignedShort();
            input.readFully(new byte[sideLength]);
            while (true) {
                int direction = input.read();
                if (direction < 0) {
                    break;
                }
                PacketCodecType codec = PacketCodecType.byWireId(input.readUnsignedByte());
                input.readLong();
                int rawBytes = input.readInt();
                int wireBytes = input.readInt();
                int packetCount = input.readInt();
                int payloadLength = input.readInt();
                if (codec == null
                    || rawBytes <= 0
                    || rawBytes > MAX_DATASET_RAW_BYTES
                    || wireBytes <= 0
                    || packetCount <= 0
                    || payloadLength <= 0
                    || payloadLength > MAX_DATASET_PAYLOAD_BYTES) {
                    throw new IOException("Invalid NEB dataset record");
                }
                byte[] payload = new byte[payloadLength];
                input.readFully(payload);
                frames.add(
                    new Frame(payload, packetCount, direction != 0, codec == PacketCodecType.STORED, codec, rawBytes,
                        wireBytes));
            }
        }
        return new DatasetInput(frames);
    }

    private static void appendResult(StringBuilder output, Scenario scenario, String codec, int iteration,
        int frames, long packets, long storedFrames, long storedWireBytes, long inputBytes,
        long packetMetadataBytes, long rawBytes, long codecBytes, long nebHeaderBytes,
        long compressionFrameHeaderBytes, long rawSizeMetadataBytes, long frameLengthBytes,
        long storedPacketLengthBytes, long wireBytes, long aggregateCodecBytes) {
        double codecRatio = inputBytes == 0L ? 0.0D : (double) codecBytes / inputBytes;
        double wireRatio = rawBytes == 0L ? 0.0D : (double) wireBytes / rawBytes;
        double metadata = wireBytes == 0L ? 0.0D
            : 100.0D * (nebHeaderBytes + frameLengthBytes + storedPacketLengthBytes) / wireBytes;
        double saved = 100.0D * (1.0D - wireRatio);
        long flushOverhead = aggregateCodecBytes < 0L ? 0L : codecBytes - aggregateCodecBytes;
        appendLine(
            output,
            String.format(
                Locale.ROOT,
                "%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%.3f,%d,%.3f",
                scenario.name,
                codec,
                iteration,
                frames,
                packets,
                storedFrames,
                storedWireBytes,
                inputBytes,
                packetMetadataBytes,
                rawBytes,
                rawBytes - inputBytes,
                codecBytes,
                compressionFrameHeaderBytes,
                rawSizeMetadataBytes,
                frameLengthBytes,
                storedPacketLengthBytes,
                wireBytes,
                codecRatio,
                wireRatio,
                metadata,
                flushOverhead,
                saved));
    }

    private static void appendLine(StringBuilder output, String line) {
        output.append(line)
            .append('\n');
    }

    private static PacketMetrics packetMetrics(byte[] payload, int expectedPackets) {
        int offset = 0;
        long packetMetadataBytes = 0L;
        int packets = 0;
        while (offset < payload.length) {
            int[] length = readVarInt(payload, offset);
            if (length[0] <= 0 || length[0] > payload.length - length[1]) {
                throw new IllegalStateException("Invalid packet envelope in probe input");
            }
            packetMetadataBytes += length[1] - offset;
            offset = length[1] + length[0];
            packets++;
        }
        if (packets != expectedPackets) {
            throw new IllegalStateException(
                "Packet envelope count " + packets + " does not match recorded count " + expectedPackets);
        }
        return new PacketMetrics(packetMetadataBytes);
    }

    private static ByteBuffer direct(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes).flip();
        return buffer;
    }

    private static boolean matches(ByteBuffer actual, byte[] expected) {
        if (actual.remaining() != expected.length) {
            return false;
        }
        for (byte value : expected) {
            if (actual.get() != value) {
                return false;
            }
        }
        return true;
    }

    private static void writeVarInt(ByteBuffer buffer, int value) {
        while ((value & ~0x7F) != 0) {
            buffer.put((byte) (value & 0x7F | 0x80));
            value >>>= 7;
        }
        buffer.put((byte) value);
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        while ((value & ~0x7F) != 0) {
            output.write(value & 0x7F | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    private static int[] readVarInt(byte[] bytes, int offset) {
        int value = 0;
        int shift = 0;
        int index = offset;
        while (index < bytes.length && shift < 35) {
            int current = bytes[index++] & 0xFF;
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return new int[] { value, index };
            }
            shift += 7;
        }
        return new int[] { -1, bytes.length };
    }

    private static int varIntSize(int value) {
        int size = 1;
        while ((value >>>= 7) != 0) {
            size++;
        }
        return size;
    }

    private enum Pattern {
        REPETITIVE,
        MIXED,
        RANDOM
    }

    private static class Scenario {

        private final String name;
        private final int frameCount;
        private final int packetSize;
        private final int packetsPerFrame;
        private final Pattern pattern;
        private final long seed;

        private Scenario(String name, int frameCount, int packetSize, int packetsPerFrame, Pattern pattern,
            long seed) {
            this.name = name;
            this.frameCount = frameCount;
            this.packetSize = packetSize;
            this.packetsPerFrame = packetsPerFrame;
            this.pattern = pattern;
            this.seed = seed;
        }
    }

    private static class Frame {

        private final byte[] payload;
        private final int packetCount;
        private final boolean outbound;
        private final boolean stored;
        private final PacketCodecType codec;
        private final int rawBytes;
        private final int recordedWireBytes;

        private Frame(byte[] payload, int packetCount) {
            this(payload, packetCount, true, false, PacketCodecType.ZSTD_STREAM, payload.length, 0);
        }

        private Frame(byte[] payload, int packetCount, boolean outbound, boolean stored, PacketCodecType codec,
            int rawBytes,
            int recordedWireBytes) {
            this.payload = payload;
            this.packetCount = packetCount;
            this.outbound = outbound;
            this.stored = stored;
            this.codec = codec;
            this.rawBytes = rawBytes;
            this.recordedWireBytes = recordedWireBytes;
        }
    }

    private static class PacketMetrics {

        private final long packetMetadataBytes;

        private PacketMetrics(long packetMetadataBytes) {
            this.packetMetadataBytes = packetMetadataBytes;
        }
    }

    private static class DatasetInput {

        private final List<Frame> frames;

        private DatasetInput(List<Frame> frames) {
            this.frames = frames;
        }
    }

    private static class Options {

        private final int frames;
        private final int iterations;
        private final long seed;
        private final int[] zstdLevels;
        private final Path dataset;
        private final Path output;
        private final boolean analyzeDataset;
        private final boolean trainDictionary;
        private final Path dictionaryOut;

        private Options(int frames, int iterations, long seed, int[] zstdLevels, Path dataset, Path output,
            boolean analyzeDataset, boolean trainDictionary, Path dictionaryOut) {
            this.frames = frames;
            this.iterations = iterations;
            this.seed = seed;
            this.zstdLevels = zstdLevels;
            this.dataset = dataset;
            this.output = output;
            this.analyzeDataset = analyzeDataset;
            this.trainDictionary = trainDictionary;
            this.dictionaryOut = dictionaryOut;
        }

        private static Options parse(String[] args) {
            int frames = DEFAULT_FRAMES;
            int iterations = DEFAULT_ITERATIONS;
            long seed = DEFAULT_SEED;
            int[] levels = ZSTD_LEVELS;
            Path dataset = null;
            Path output = null;
            boolean analyzeDataset = false;
            boolean trainDictionary = false;
            Path dictionaryOut = null;
            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                if (argument.startsWith("--frames=")) {
                    frames = positiveInt(argument.substring(9), "frames");
                } else if (argument.startsWith("--iterations=")) {
                    iterations = positiveInt(argument.substring(13), "iterations");
                } else if (argument.startsWith("--seed=")) {
                    seed = Long.decode(argument.substring(7));
                } else if (argument.startsWith("--zstd-levels=")) {
                    String[] values = argument.substring(14).split(",");
                    levels = new int[values.length];
                    for (int level = 0; level < values.length; level++) {
                        levels[level] = positiveInt(values[level], "zstd level");
                        if (levels[level] > 19) {
                            throw new IllegalArgumentException("zstd level must be between 1 and 19");
                        }
                    }
                } else if (argument.startsWith("--dataset=")) {
                    dataset = Path.of(argument.substring(10));
                } else if (argument.startsWith("--output=")) {
                    output = Path.of(argument.substring(9));
                } else if (argument.equals("--analyze-dataset")) {
                    analyzeDataset = true;
                } else if (argument.equals("--train-dictionary")) {
                    trainDictionary = true;
                } else if (argument.startsWith("--dictionary-out=")) {
                    dictionaryOut = Path.of(argument.substring(17));
                } else {
                    throw new IllegalArgumentException(
                        "Unknown argument " + argument
                            + "; use --frames=N --iterations=N --seed=N --zstd-levels=3,19"
                            + " --dataset=PATH --output=PATH --analyze-dataset"
                            + " --train-dictionary --dictionary-out=PATH");
                }
            }
            if (analyzeDataset && dataset == null) {
                throw new IllegalArgumentException("--analyze-dataset requires --dataset=PATH");
            }
            if (trainDictionary && dataset == null) {
                throw new IllegalArgumentException("--train-dictionary requires --dataset=PATH");
            }
            if (dictionaryOut != null && !trainDictionary) {
                throw new IllegalArgumentException("--dictionary-out requires --train-dictionary");
            }
            return new Options(
                frames,
                iterations,
                seed,
                levels,
                dataset,
                output,
                analyzeDataset,
                trainDictionary,
                dictionaryOut);
        }

        private static int positiveInt(String value, String name) {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return parsed;
        }
    }
}
