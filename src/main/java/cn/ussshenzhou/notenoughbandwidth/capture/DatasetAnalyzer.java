package cn.ussshenzhou.notenoughbandwidth.capture;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecRegistry;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import cn.ussshenzhou.notenoughbandwidth.util.NebVarInt;

public class DatasetAnalyzer {

    private static final int FORMAT_VERSION = 1;
    private static final int MAX_SIDE_BYTES = 256;
    private static final byte[] MAGIC = { 'N', 'E', 'B', 'D' };

    public static DatasetAnalysis analyze(Path dataset, Path csv) throws IOException {
        ParsedDataset parsed = read(dataset);
        List<DatasetAnalysis.CodecResult> results = replay(parsed.records);
        writeCsv(csv, parsed.side, results);
        NebLog.info("Analyzed bandwidth dataset {} into {}.", dataset, csv);
        return new DatasetAnalysis(dataset, csv, parsed.side, results);
    }

    private static ParsedDataset read(Path dataset) throws IOException {
        List<DatasetRecord> records = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(
            new BufferedInputStream(Files.newInputStream(dataset, StandardOpenOption.READ)))) {
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            for (int i = 0; i < MAGIC.length; i++) {
                if (magic[i] != MAGIC[i]) {
                    throw new IOException("Unknown NEB dataset header");
                }
            }
            int version = input.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported NEB dataset version " + version);
            }
            long timestamp = input.readLong();
            int sideLength = input.readUnsignedShort();
            if (sideLength > MAX_SIDE_BYTES) {
                throw new IOException("NEB dataset side label is too long");
            }
            byte[] sideBytes = new byte[sideLength];
            input.readFully(sideBytes);
            String side = new String(sideBytes, StandardCharsets.UTF_8);
            while (true) {
                int direction = input.read();
                if (direction < 0) {
                    break;
                }
                try {
                    if (direction > 1) {
                        throw new IOException("Invalid NEB dataset direction");
                    }
                    PacketCodecType codec = PacketCodecType.byWireId(input.readUnsignedByte());
                    if (codec == null) {
                        throw new IOException("Unknown codec in NEB dataset");
                    }
                    long delta = input.readLong();
                    if (delta < 0 || Long.MAX_VALUE - timestamp < delta) {
                        throw new IOException("Invalid NEB dataset timestamp");
                    }
                    timestamp += delta;
                    int rawBytes = input.readInt();
                    int wireBytes = input.readInt();
                    int packetCount = input.readInt();
                    int payloadLength = input.readInt();
                    if (rawBytes <= 0 || rawBytes > DatasetRecord.MAX_RAW_BYTES
                        || wireBytes <= 0
                        || packetCount <= 0
                        || payloadLength <= 0
                        || payloadLength > DatasetRecord.MAX_PAYLOAD_BYTES) {
                        throw new IOException("Invalid NEB dataset record");
                    }
                    byte[] payload = new byte[payloadLength];
                    input.readFully(payload);
                    records.add(
                        new DatasetRecord(timestamp, direction != 0, codec, rawBytes, wireBytes, packetCount, payload));
                } catch (EOFException end) {
                    throw new IOException("Truncated NEB dataset record", end);
                }
            }
            return new ParsedDataset(side, records);
        }
    }

    private static List<DatasetAnalysis.CodecResult> replay(List<DatasetRecord> records) {
        List<PacketCodecType> available = PacketCodecRegistry.available();
        List<DatasetAnalysis.CodecResult> result = new ArrayList<>();
        for (boolean outbound : new boolean[] { false, true }) {
            List<DatasetRecord> direction = records.stream()
                .filter(record -> record.outbound() == outbound)
                .toList();
            if (direction.isEmpty()) {
                continue;
            }
            for (PacketCodecType type : available) {
                result.add(replayCodec(outbound, type, direction));
            }
        }
        return result;
    }

    private static DatasetAnalysis.CodecResult replayCodec(boolean outbound, PacketCodecType type,
        List<DatasetRecord> records) {
        PacketCodec codec = type == PacketCodecType.STORED ? null : PacketCodecRegistry.create(type);
        long rawBytes = 0;
        long wireBytes = 0;
        long packetCount = 0;
        try {
            for (DatasetRecord record : records) {
                rawBytes = safeAdd(rawBytes, record.rawBytes());
                packetCount = safeAdd(packetCount, record.packetCount());
                if (record.codec() == PacketCodecType.STORED) {
                    wireBytes = safeAdd(wireBytes, record.wireBytes());
                    continue;
                }
                if (type == PacketCodecType.STORED) {
                    wireBytes = safeAdd(wireBytes, storedWireBytes(record));
                    continue;
                }
                byte[] payload = record.copyPayload();
                int compressed = compress(codec, payload);
                int frameBytes = compressed + 1 + NebVarInt.sizeOf(payload.length);
                wireBytes = safeAdd(wireBytes, (long) frameBytes + NebVarInt.sizeOf(frameBytes));
            }
            return new DatasetAnalysis.CodecResult(outbound, type, rawBytes, wireBytes, records.size(), packetCount);
        } finally {
            if (codec != null) {
                codec.close();
            }
        }
    }

    private static long storedWireBytes(DatasetRecord record) {
        byte[] payload = record.copyPayload();
        int offset = 0;
        long total = 0;
        for (int packet = 0; packet < record.packetCount(); packet++) {
            int[] result = readVarInt(payload, offset);
            int size = result[0];
            offset = result[1];
            if (size <= 0 || size > payload.length - offset) {
                return record.wireBytes();
            }
            long entry = (long) size + NebVarInt.sizeOf(size);
            total = safeAdd(total, entry);
            offset += size;
        }
        return offset == payload.length ? total : record.wireBytes();
    }

    private static int[] readVarInt(byte[] payload, int offset) {
        int value = 0;
        int shift = 0;
        int index = offset;
        while (index < payload.length && shift < 35) {
            int current = payload[index++] & 0xff;
            value |= (current & 0x7f) << shift;
            if ((current & 0x80) == 0) {
                return new int[] { value, index };
            }
            shift += 7;
        }
        return new int[] { -1, payload.length };
    }

    private static int compress(PacketCodec codec, byte[] payload) {
        int bound = codec.compressBound(payload.length);
        ByteBuffer source = ByteBuffer.allocateDirect(payload.length);
        source.put(payload)
            .flip();
        ByteBuffer destination = ByteBuffer.allocateDirect(bound);
        int before = destination.position();
        codec.compress(source, destination);
        if (source.hasRemaining()) {
            throw new IllegalStateException("NEB dataset codec did not consume the sample");
        }
        return destination.position() - before;
    }

    private static void writeCsv(Path csv, String side, List<DatasetAnalysis.CodecResult> results) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
            csv,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
            writer.write("side,direction,codec,raw_bytes,wire_bytes,frames,packets,ratio,saved_percent\n");
            for (DatasetAnalysis.CodecResult result : results) {
                writer.write(side);
                writer.write(',');
                writer.write(result.outbound() ? "outbound" : "inbound");
                writer.write(',');
                writer.write(
                    result.codec()
                        .configName());
                writer.write(',');
                writer.write(Long.toString(result.rawBytes()));
                writer.write(',');
                writer.write(Long.toString(result.wireBytes()));
                writer.write(',');
                writer.write(Long.toString(result.frameCount()));
                writer.write(',');
                writer.write(Long.toString(result.packetCount()));
                writer.write(',');
                writer.write(String.format(Locale.ROOT, "%.6f", result.ratio()));
                writer.write(',');
                writer.write(String.format(Locale.ROOT, "%.3f", (1.0D - result.ratio()) * 100.0D));
                writer.write('\n');
            }
        }
    }

    private static long safeAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static class ParsedDataset {

        private final String side;
        private final List<DatasetRecord> records;

        private ParsedDataset(String side, List<DatasetRecord> records) {
            this.side = side;
            this.records = records;
        }
    }

    private DatasetAnalyzer() {}
}
