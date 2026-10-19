import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

import com.github.luben.zstd.Zstd;

import cn.ussshenzhou.notenoughbandwidth.codec.DictionaryCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecRegistry;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import cn.ussshenzhou.notenoughbandwidth.codec.zstd.BandwidthDictionary;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cn.ussshenzhou.notenoughbandwidth.pipeline.ChannelIndex;
import cn.ussshenzhou.notenoughbandwidth.pipeline.DictionaryFrame;
import cn.ussshenzhou.notenoughbandwidth.pipeline.NebFrame;
import cn.ussshenzhou.notenoughbandwidth.util.NebVarInt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;

public class NebPacketSimulation {

    public static final int CUSTOM_PAYLOAD_ID = 0x3F;

    public static final int KIND_STRUCTURED = 0;

    public static final int KIND_RUNS = 1;

    public static final int KIND_TINY_CUSTOM = 2;

    public static final int KIND_CHANNEL = 3;

    public static final int KIND_ENTROPY = 4;

    public static final int KIND_TINY = 5;

    public static final double PACKETS_PER_SECOND = 729.0d;

    public static final long COLD_STREAM_BYTES = 32L * 1024L;

    public static final int MAX_SAMPLES = 512;

    public static final int MAX_SAMPLE_BYTES = 16 * 1024;

    public static final int[] WINDOWS = { 0, 10, 20, 40, 80 };

    public static final int[] THRESHOLDS = { 8192, 262144 };

    public static final ByteBufAllocator ALLOCATOR = UnpooledByteBufAllocator.DEFAULT;

    public static void main(String[] args) {
        PacketCodecRegistry.bootstrap();
        PacketCodecType type = PacketCodecType.ZSTD_STREAM;
        if (!PacketCodecRegistry.isAvailable(type)) {
            System.out.println("FAIL zstd_stream is unavailable");
            System.exit(1);
            return;
        }
        if (args.length > 0) {
            boolean ok = runDatasetMode(args[0], type);
            System.out.println(ok ? "RESULT PASS" : "RESULT FAIL");
            if (!ok) {
                System.exit(1);
            }
            return;
        }
        List<SimPacket> stream = buildStream(profile());
        long indexedRaw = 0L;
        long bypassBytes = 0L;
        for (SimPacket packet : stream) {
            int size = packet.body.length + NebVarInt.sizeOf(packet.body.length);
            indexedRaw += size;
            if (packet.bypass) {
                bypassBytes += size;
            }
        }
        System.out.println("NEB offline packet simulation");
        System.out.println(
            "codec=" + type.configName()
                + " level="
                + NebConfig.zstdLevel
                + " windowLog="
                + NebConfig.zstdWindowLog
                + " compressionThreshold="
                + NebConfig.compressionThresholdBytes
                + " maxAggregated="
                + NebFrame.MAX_AGGREGATED_BYTES);
        System.out.println(
            "profile packets=" + stream.size() + " indexedRaw=" + human(indexedRaw) + " bypass=" + human(bypassBytes));
        System.out.println();

        List<Scene> scenes = new ArrayList<>();
        for (int threshold : THRESHOLDS) {
            for (int window : WINDOWS) {
                scenes.add(runScene(stream, type, window, threshold, false, null));
                scenes.add(runScene(stream, type, window, threshold, true, null));
            }
        }
        printSceneTable(scenes);

        Scene reference = null;
        Scene best = null;
        for (Scene scene : scenes) {
            if (scene.windowMillis == 20 && scene.flushThreshold == 262144 && scene.rearm) {
                reference = scene;
            }
            if (best == null || scene.wireBytes < best.wireBytes) {
                best = scene;
            }
        }
        if (reference == null) {
            System.out.println("RESULT FAIL");
            System.exit(1);
            return;
        }

        System.out.println();
        System.out.println("Dictionary experiment (reference scene: window 20 ms, threshold 262144, rearm)");
        byte[] dictionary = dictionaryExperiment(reference, type);
        if (dictionary != null) {
            System.out.println();
            System.out.println("Reference scene replayed with the trained dictionary forced on (gate verdict may be REJECT)");
            Scene forced = runScene(stream, type, 20, 262144, true, dictionary);
            System.out.println(
                "baseline wire=" + human(reference.wireBytes)
                    + " forced wire="
                    + human(forced.wireBytes)
                    + " delta="
                    + percent(reference.wireBytes, forced.wireBytes)
                    + " dictionaryFrames="
                    + forced.dictionaryFrames
                    + " framesBefore="
                    + forced.framesBeforeDictionary
                    + " framesAfter="
                    + forced.framesAfterDictionary);
        }

        System.out.println();
        System.out.println("Reset bit stress: incompressible frames only, never allowed to warm up");
        Scene stress = resetStress(type);
        System.out.println(
            "frames=" + stress.frames
                + " codecResets="
                + stress.codecResets
                + " decoderResets="
                + stress.decoderResets
                + " storedFrames="
                + stress.storedFrames
                + " wire="
                + human(stress.wireBytes)
                + " raw="
                + human(stress.rawBytes)
                + " mismatch="
                + stress.originalsMismatch);

        System.out.println();
        boolean ok = true;
        for (Scene scene : scenes) {
            if (!scene.roundTripOk || scene.declaredSizeMismatch != 0
                || scene.trailingBytes != 0
                || scene.codecResets != scene.decoderResets
                || scene.originalsMismatch != 0) {
                ok = false;
                System.out.println(
                    "FAIL scene " + scene.describe()
                        + " roundTrip="
                        + scene.roundTripOk
                        + " sizeMismatch="
                        + scene.declaredSizeMismatch
                        + " trailing="
                        + scene.trailingBytes
                        + " resets="
                        + scene.codecResets
                        + "/"
                        + scene.decoderResets
                        + " originals="
                        + scene.originalsMismatch);
            }
        }
        System.out.println();
        System.out.println("stale timer (old) versus rearmed timer (new), same window and threshold");
        System.out.printf(Locale.ROOT, "%-6s %-9s %12s %12s %10s %10s%n", "window", "threshold", "deltaFrames", "deltaWire", "frame%", "wire%");
        for (Scene rearmScene : scenes) {
            if (!rearmScene.rearm) {
                continue;
            }
            for (Scene staleScene : scenes) {
                if (staleScene.rearm || staleScene.windowMillis != rearmScene.windowMillis
                    || staleScene.flushThreshold != rearmScene.flushThreshold) {
                    continue;
                }
                long frameDelta = staleScene.frames - rearmScene.frames;
                long wireDelta = staleScene.wireBytes - rearmScene.wireBytes;
                System.out.printf(
                    Locale.ROOT,
                    "%-6s %-9d %12d %12d %9.2f%% %9.2f%%%n",
                    rearmScene.windowMillis + "ms",
                    rearmScene.flushThreshold,
                    frameDelta,
                    wireDelta,
                    staleScene.frames == 0 ? 0.0d : frameDelta * 100.0d / staleScene.frames,
                    staleScene.wireBytes == 0 ? 0.0d : wireDelta * 100.0d / staleScene.wireBytes);
            }
        }
        System.out.println();
        System.out.println("aggregation window sweep at the default threshold 262144 (rearm)");
        System.out.printf(Locale.ROOT, "%-6s %10s %11s %10s %10s%n", "window", "pkts/frame", "wire", "wire/raw", "vs 20ms");
        long baselineWire = 0L;
        for (Scene scene : scenes) {
            if (scene.flushThreshold == 262144 && scene.rearm && scene.windowMillis == 20) {
                baselineWire = scene.wireBytes;
            }
        }
        for (Scene scene : scenes) {
            if (scene.flushThreshold != 262144 || !scene.rearm) {
                continue;
            }
            System.out.printf(
                Locale.ROOT,
                "%-6s %10.2f %11s %10.4f %10s%n",
                scene.windowMillis + "ms",
                scene.frames == 0 ? 0.0d : (double) scene.packets / scene.frames,
                human(scene.wireBytes),
                scene.rawBytes == 0 ? 0.0d : (double) scene.wireBytes / scene.rawBytes,
                baselineWire == 0L ? "n/a" : percent(baselineWire, scene.wireBytes));
        }
        System.out.println(
            "comparison best=" + best.describe()
                + " wire="
                + human(best.wireBytes)
                + " reference="
                + human(reference.wireBytes));
        if (!stress.roundTripOk) {
            ok = false;
            System.out.println("FAIL reset bit stress scene");
        }
        System.out.println(ok ? "RESULT PASS" : "RESULT FAIL");
        if (!ok) {
            System.exit(1);
        }
    }

    public static boolean runDatasetMode(String datasetName, PacketCodecType type) {
        Path path = Paths.get(datasetName);
        List<SimPacket> stream;
        try {
            stream = readDataset(path);
        } catch (IOException failure) {
            System.out.println("FAIL cannot read " + path + ": " + failure.getMessage());
            return false;
        }
        long recordedWire = 0L;
        long recordedRaw = 0L;
        for (SimPacket packet : stream) {
            recordedRaw += packet.body.length + NebVarInt.sizeOf(packet.body.length);
            if (packet.bypass) {
                recordedWire += packet.body.length + NebVarInt.sizeOf(packet.body.length);
            }
        }
        System.out.println("NEB offline replay of a captured dataset");
        System.out.println("dataset=" + path);
        System.out.println(
            "records=" + stream.size()
                + " recordedRaw="
                + human(recordedRaw)
                + " bypassRecords="
                + human(recordedWire)
                + " codec="
                + type.configName());
        System.out.println("each record is already one aggregated frame, so the aggregation sweep is skipped");
        System.out.println();
        Scene scene = runScene(stream, type, 0, Integer.MAX_VALUE, true, null);
        System.out.println(
            "replay frames=" + scene.frames
                + " packets="
                + scene.packets
                + " raw="
                + human(scene.rawBytes)
                + " wire="
                + human(scene.wireBytes)
                + " wire/raw="
                + String.format(Locale.ROOT, "%.4f", scene.rawBytes == 0 ? 0.0d : (double) scene.wireBytes / scene.rawBytes)
                + " header="
                + String.format(
                    Locale.ROOT,
                    "%.3f%%",
                    scene.wireBytes == 0 ? 0.0d
                        : (scene.frameHeaderBytes + scene.frameLengthBytes) * 100.0d / scene.wireBytes)
                + " stored="
                + scene.storedFrames
                + " reset="
                + scene.resetFrames);
        System.out.println(
            "roundTrip=" + (scene.roundTripOk ? "OK" : "BROKEN")
                + " sizeMismatch="
                + scene.declaredSizeMismatch
                + " trailing="
                + scene.trailingBytes
                + " originals="
                + scene.originalsMismatch
                + " resets="
                + scene.codecResets
                + "/"
                + scene.decoderResets);
        System.out.println();
        System.out.println("Dictionary experiment on this dataset");
        byte[] dictionary = dictionaryExperiment(scene, type);
        if (dictionary != null) {
            Scene forced = runScene(stream, type, 0, Integer.MAX_VALUE, true, dictionary);
            System.out.println(
                "forced dictionary replay wire=" + human(forced.wireBytes)
                    + " baseline="
                    + human(scene.wireBytes)
                    + " delta="
                    + percent(scene.wireBytes, forced.wireBytes)
                    + " dictionaryFrames="
                    + forced.dictionaryFrames);
        }
        return scene.roundTripOk && scene.declaredSizeMismatch == 0
            && scene.trailingBytes == 0
            && scene.originalsMismatch == 0
            && scene.codecResets == scene.decoderResets;
    }

    public static List<SimPacket> readDataset(Path path) throws IOException {
        List<SimPacket> stream = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(
            new BufferedInputStream(Files.newInputStream(path, StandardOpenOption.READ)))) {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (magic[0] != 'N' || magic[1] != 'E' || magic[2] != 'B' || magic[3] != 'D') {
                throw new IOException("not a NEB dataset");
            }
            int version = input.readInt();
            if (version != 1) {
                throw new IOException("unsupported dataset version " + version);
            }
            long timestamp = input.readLong();
            int sideLength = input.readUnsignedShort();
            byte[] side = new byte[sideLength];
            input.readFully(side);
            long nanos = 0L;
            while (true) {
                int direction = input.read();
                if (direction < 0) {
                    break;
                }
                if (direction > 1) {
                    throw new IOException("invalid direction " + direction);
                }
                PacketCodecType codec = PacketCodecType.byWireId(input.readUnsignedByte());
                if (codec == null) {
                    throw new IOException("unknown codec in dataset");
                }
                long deltaMillis = input.readLong();
                input.readInt();
                input.readInt();
                input.readInt();
                int payloadLength = input.readInt();
                if (payloadLength <= 0 || payloadLength > 16 * 1024 * 1024) {
                    throw new IOException("invalid payload length " + payloadLength);
                }
                byte[] payload = new byte[payloadLength];
                try {
                    input.readFully(payload);
                } catch (EOFException truncated) {
                    throw new IOException("truncated dataset", truncated);
                }
                nanos += Math.max(0L, deltaMillis) * 1_000_000L;
                if (direction != 1) {
                    continue;
                }
                stream.add(new SimPacket(payload, nanos, codec == PacketCodecType.STORED, codec.configName()));
            }
        }
        if (stream.isEmpty()) {
            throw new IOException("dataset holds no outbound records");
        }
        return stream;
    }

    public static Scene resetStress(PacketCodecType type) {
        SplittableRandom random = new SplittableRandom(7L);
        List<SimPacket> stream = new ArrayList<>();
        long arrival = 0L;
        for (int pair = 0; pair < 100; pair++) {
            arrival += 1_000_000L;
            stream.add(new SimPacket(entropy(8192, random), arrival, false, "stressEntropy"));
            arrival += 1_000_000L;
            stream.add(new SimPacket(structured(300, random, pair), arrival, false, "stressCompressible"));
        }
        Scene scene = runScene(stream, type, 0, 262144, true, null);
        if (scene.codecResets == 0 || scene.codecResets != scene.decoderResets
            || scene.originalsMismatch != 0
            || scene.declaredSizeMismatch != 0) {
            scene.roundTripOk = false;
        }
        return scene;
    }

    public static List<Mix> profile() {
        List<Mix> mix = new ArrayList<>();
        mix.add(new Mix("S21PacketChunkData", 6713, 3197, KIND_STRUCTURED, false, null));
        mix.add(new Mix("S22PacketMultiBlockChange", 27734, 524, KIND_RUNS, false, null));
        mix.add(new Mix("GalacticraftCore", 333679, 41, KIND_TINY_CUSTOM, false, "GalacticraftCore"));
        mix.add(new Mix("S26PacketMapChunkBulk", 749, 12361, KIND_ENTROPY, false, null));
        mix.add(new Mix("vnChannel", 9655, 39, KIND_TINY_CUSTOM, false, "vnChannel"));
        mix.add(new Mix("S23PacketBlockChange", 11069, 79, KIND_RUNS, false, null));
        mix.add(new Mix("S1CPacketEntityMetadata", 37009, 17, KIND_TINY, false, null));
        mix.add(new Mix("S19PacketEntityHeadLook", 60918, 7, KIND_TINY, false, null));
        mix.add(new Mix("hodgepodge", 535, 591, KIND_CHANNEL, false, "hodgepodge"));
        mix.add(new Mix("S14PacketEntityRelMove", 46216, 9, KIND_TINY, false, null));
        mix.add(new Mix("BQ_NET_CHAN", 186, 851, KIND_CHANNEL, false, "BQ_NET_CHAN"));
        mix.add(new Mix("S00PacketKeepAlive", 919, 6, KIND_TINY, true, null));
        return mix;
    }

    public static List<SimPacket> buildStream(List<Mix> mix) {
        SplittableRandom random = new SplittableRandom(20261019L);
        int total = 0;
        for (Mix entry : mix) {
            total += entry.count;
        }
        int[] produced = new int[mix.size()];
        double[] credit = new double[mix.size()];
        List<SimPacket> stream = new ArrayList<>(total);
        long arrival = 0L;
        long step = (long) (1_000_000_000.0d / PACKETS_PER_SECOND);
        for (int slot = 0; slot < total; slot++) {
            int selected = -1;
            double bestCredit = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < mix.size(); i++) {
                credit[i] += (double) mix.get(i).count / total;
                if (produced[i] >= mix.get(i).count) {
                    continue;
                }
                if (credit[i] > bestCredit) {
                    bestCredit = credit[i];
                    selected = i;
                }
            }
            if (selected < 0) {
                break;
            }
            credit[selected] -= 1.0d;
            produced[selected]++;
            arrival += step + random.nextInt(3) * (step / 4);
            Mix entry = mix.get(selected);
            stream.add(new SimPacket(body(entry, random, produced[selected]), arrival, entry.bypass, entry.label));
        }
        return stream;
    }

    public static byte[] body(Mix entry, SplittableRandom random, int sequence) {
        switch (entry.kind) {
            case KIND_STRUCTURED:
                return structured(entry.averageBytes, random, sequence);
            case KIND_RUNS:
                return runs(entry.averageBytes, random, sequence);
            case KIND_TINY_CUSTOM:
                return custom(entry.channel, Math.max(4, entry.averageBytes - entry.channel.length() - 3), random);
            case KIND_CHANNEL:
                return custom(entry.channel, entry.averageBytes, random);
            case KIND_ENTROPY:
                return entropy(entry.averageBytes, random);
            default:
                return tiny(entry.averageBytes, random, sequence);
        }
    }

    public static byte[] structured(int size, SplittableRandom random, int sequence) {
        byte[] data = new byte[Math.max(2, size)];
        data[0] = 0x21;
        int index = 1;
        while (index < data.length) {
            int roll = random.nextInt(100);
            if (roll < 55) {
                data[index++] = 0;
            } else if (roll < 76) {
                data[index++] = 1;
            } else if (roll < 88) {
                data[index++] = (byte) random.nextInt(24);
            } else if (roll < 96) {
                data[index++] = (byte) (sequence & 0xFF);
            } else {
                data[index++] = (byte) random.nextInt(256);
            }
        }
        return data;
    }

    public static byte[] runs(int size, SplittableRandom random, int sequence) {
        byte[] data = new byte[Math.max(3, size)];
        data[0] = 0x22;
        data[1] = (byte) (sequence & 0xFF);
        int index = 2;
        while (index < data.length) {
            int runLength = 1 + random.nextInt(24);
            byte value = random.nextInt(8) == 0 ? (byte) random.nextInt(256) : (byte) random.nextInt(4);
            for (int i = 0; i < runLength && index < data.length; i++) {
                data[index++] = value;
            }
        }
        return data;
    }

    public static byte[] custom(String channel, int payloadSize, SplittableRandom random) {
        byte[] name = channel.getBytes(StandardCharsets.UTF_8);
        ByteBuf buffer = Unpooled.buffer(name.length + payloadSize + 8);
        try {
            NebVarInt.write(buffer, CUSTOM_PAYLOAD_ID);
            NebVarInt.write(buffer, name.length);
            buffer.writeBytes(name);
            for (int i = 0; i < payloadSize; i++) {
                buffer.writeByte(random.nextInt(6) == 0 ? random.nextInt(256) : 0x01);
            }
            byte[] data = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), data);
            return data;
        } finally {
            buffer.release();
        }
    }

    public static byte[] entropy(int size, SplittableRandom random) {
        byte[] data = new byte[Math.max(2, size)];
        data[0] = 0x26;
        for (int i = 1; i < data.length; i++) {
            data[i] = (byte) random.nextInt(256);
        }
        return data;
    }

    public static byte[] tiny(int size, SplittableRandom random, int sequence) {
        byte[] data = new byte[Math.max(2, size)];
        int[] ids = { 0x19, 0x1C, 0x14, 0x12, 0x00 };
        data[0] = (byte) ids[sequence % ids.length];
        for (int i = 1; i < data.length; i++) {
            data[i] = (byte) (random.nextInt(10) == 0 ? random.nextInt(256) : (sequence >>> (i & 3)) & 0x03);
        }
        return data;
    }

    public static Scene runScene(List<SimPacket> stream, PacketCodecType type, int windowMillis, int flushThreshold,
        boolean rearm, byte[] dictionary) {
        Scene scene = new Scene(windowMillis, flushThreshold, rearm);
        Session session = new Session(scene, type, dictionary);
        try {
            for (SimPacket packet : stream) {
                session.write(packet);
            }
            session.emit();
        } finally {
            session.close();
        }
        return scene;
    }

    public static class Session {

        public final Scene scene;

        public final PacketCodecType type;

        public final ChannelIndex outboundIndex = new ChannelIndex();

        public final ChannelIndex inboundIndex = new ChannelIndex();

        public final ByteBuf accumulator = Unpooled.buffer();

        public final List<byte[]> framePackets = new ArrayList<>();

        public final long windowNanos;

        public final boolean rearm;

        public final byte[] dictionary;

        public final long dictionaryGate;

        public PacketCodec encoder;

        public PacketCodec decoder;

        public boolean pendingReset;

        public boolean streamWarm;

        public long streamInput;

        public boolean flushScheduled;

        public long scheduledAt;

        public boolean dictionaryInstalled;

        public long dictionaryTraffic;

        public Session(Scene scene, PacketCodecType type, byte[] dictionary) {
            this.scene = scene;
            this.type = type;
            this.rearm = scene.rearm;
            this.windowNanos = scene.windowMillis * 1_000_000L;
            this.dictionary = dictionary;
            this.dictionaryGate = dictionary == null ? Long.MAX_VALUE
                : BandwidthDictionary.minimumTrafficFor(dictionary.length);
            this.encoder = PacketCodecRegistry.create(type);
            this.decoder = PacketCodecRegistry.create(type);
        }

        public void installDictionary() {
            encoder.close();
            decoder.close();
            encoder = PacketCodecRegistry.create(type);
            decoder = PacketCodecRegistry.create(type);
            if (encoder instanceof DictionaryCodec enc) {
                enc.loadDictionary(dictionary);
            }
            if (decoder instanceof DictionaryCodec dec) {
                dec.loadDictionary(dictionary);
            }
            streamWarm = false;
            streamInput = 0L;
        }

        public void write(SimPacket packet) {
            if (flushScheduled && packet.arrivalNanos >= scheduledAt) {
                flushScheduled = false;
                emit();
            }
            int entrySize = packet.body.length + NebVarInt.sizeOf(packet.body.length);
            if (packet.bypass) {
                emit();
                emitSingle(packet.body.length);
                return;
            }
            if (accumulator.readableBytes() > 0
                && entrySize > NebFrame.MAX_AGGREGATED_BYTES - accumulator.readableBytes()) {
                emit();
            }
            if (entrySize > NebFrame.MAX_AGGREGATED_BYTES) {
                emitSingle(packet.body.length);
                return;
            }
            NebVarInt.write(accumulator, packet.body.length);
            accumulator.writeBytes(packet.body);
            framePackets.add(packet.body);
            if (accumulator.readableBytes() >= scene.flushThreshold) {
                emit();
            }
            if (!flushScheduled) {
                flushScheduled = true;
                scheduledAt = packet.arrivalNanos + windowNanos;
            }
        }

        public void emitSingle(int bodySize) {
            int size = bodySize + NebVarInt.sizeOf(bodySize);
            scene.frames++;
            scene.framesBeforeDictionary++;
            scene.packets++;
            scene.bypassFrames++;
            scene.rawBytes += size;
            scene.wireBytes += size;
            scene.plainBytes += size;
        }

        public void emit() {
            if (rearm) {
                flushScheduled = false;
                scheduledAt = 0L;
            }
            if (accumulator.readableBytes() == 0) {
                return;
            }
            int rawSize = accumulator.readableBytes();
            int packets = framePackets.size();
            List<byte[]> originals = new ArrayList<>(framePackets);
            framePackets.clear();
            if (rawSize < NebConfig.compressionThresholdBytes) {
                verifyPlain(originals);
                commitPlain(rawSize, packets);
                accumulator.clear();
                return;
            }
            int mark = outboundIndex.mark();
            ByteBuf indexed = indexChannels(accumulator, outboundIndex);
            ByteBuf payload = indexed == null ? accumulator : indexed;
            byte[] input = new byte[payload.readableBytes()];
            payload.getBytes(payload.readerIndex(), input);
            if (indexed != null) {
                indexed.release();
            }
            if (dictionary != null && !dictionaryInstalled && dictionaryTraffic >= dictionaryGate) {
                dictionaryInstalled = true;
                installDictionary();
                scene.dictionaryFrames++;
            }
            ByteBuf frame = compress(input);
            if (frame == null) {
                outboundIndex.rollback(mark);
                verifyPlain(originals);
                commitPlain(rawSize, packets);
                accumulator.clear();
                return;
            }
            accumulator.clear();
            scene.frames++;
            if (dictionary != null) {
                if (dictionaryInstalled) {
                    scene.framesAfterDictionary++;
                } else {
                    scene.framesBeforeDictionary++;
                }
                dictionaryTraffic += rawSize;
            }
            scene.packets += packets;
            scene.rawBytes += rawSize;
            int frameSize = frame.readableBytes();
            int wireSize = frameSize + NebVarInt.sizeOf(frameSize);
            scene.wireBytes += wireSize;
            scene.frameHeaderBytes += 1 + NebVarInt.sizeOf(rawSize);
            scene.frameLengthBytes += NebVarInt.sizeOf(frameSize);
            if (pendingReset) {
                scene.resetFrames++;
            }
            collectSample(input);
            verifyFrame(frame, input.length, originals);
            frame.release();
        }

        public void commitPlain(int rawSize, int packets) {
            scene.frames++;
            scene.framesBeforeDictionary++;
            scene.packets += packets;
            scene.rawBytes += rawSize;
            scene.wireBytes += rawSize;
            scene.plainBytes += rawSize;
            scene.storedFrames++;
        }

        public ByteBuf compress(byte[] input) {
            if (encoder == null) {
                encoder = PacketCodecRegistry.create(type);
            }
            int bound = encoder.compressBound(input.length);
            ByteBuffer source = ByteBuffer.allocateDirect(input.length);
            source.put(input);
            source.flip();
            ByteBuffer destination = ByteBuffer.allocateDirect(bound);
            ByteBuf frame = ALLOCATOR.buffer(1 + NebVarInt.sizeOf(input.length) + bound);
            try {
                frame.writeByte(NebFrame.header(type, pendingReset));
                NebVarInt.write(frame, input.length);
                encoder.compress(source, destination);
                if (source.hasRemaining()) {
                    throw new IllegalStateException("codec did not consume the payload");
                }
                destination.flip();
                frame.writeBytes(destination);
                int frameSize = frame.readableBytes();
                boolean expands = frameSize + NebVarInt.sizeOf(frameSize) >= input.length;
                if (!expands || (type.stateful() && streamWarm)) {
                    pendingReset = false;
                    streamInput = Math.min(Long.MAX_VALUE - input.length, streamInput) + input.length;
                    if (streamInput >= COLD_STREAM_BYTES) {
                        streamWarm = true;
                    }
                    return frame;
                }
            } catch (RuntimeException failure) {
                frame.release();
                throw failure;
            }
            frame.release();
            if (type.stateful()) {
                encoder.close();
                encoder = PacketCodecRegistry.create(type);
                if (dictionaryInstalled && dictionary != null) {
                    ((DictionaryCodec) encoder).loadDictionary(dictionary);
                }
                pendingReset = true;
                streamWarm = false;
                streamInput = 0L;
                scene.codecResets++;
            }
            return null;
        }

        public void verifyPlain(List<byte[]> originals) {
            ByteBuf view = accumulator.duplicate();
            int index = 0;
            while (view.isReadable()) {
                int size = NebVarInt.read(view);
                byte[] slice = new byte[size];
                view.readBytes(slice);
                if (index >= originals.size() || !Arrays.equals(slice, originals.get(index))) {
                    scene.originalsMismatch++;
                }
                index++;
            }
            if (index != originals.size()) {
                scene.originalsMismatch++;
            }
        }

        public void verifyFrame(ByteBuf frame, int expectedRawSize, List<byte[]> originals) {
            int header = frame.getUnsignedByte(frame.readerIndex());
            if (NebFrame.codecOf(header) != type) {
                scene.roundTripOk = false;
                throw new IllegalStateException("unexpected frame header " + Integer.toHexString(header));
            }
            if (NebFrame.resetsContext(header)) {
                decoder.close();
                decoder = PacketCodecRegistry.create(type);
                if (dictionaryInstalled && dictionary != null) {
                    ((DictionaryCodec) decoder).loadDictionary(dictionary);
                }
                scene.decoderResets++;
            }
            ByteBuf view = frame.duplicate();
            view.skipBytes(1);
            int declared = NebVarInt.read(view);
            if (declared != expectedRawSize) {
                scene.declaredSizeMismatch++;
            }
            byte[] compressed = new byte[view.readableBytes()];
            view.readBytes(compressed);
            ByteBuffer source = ByteBuffer.allocateDirect(compressed.length);
            source.put(compressed);
            source.flip();
            ByteBuffer destination = ByteBuffer.allocateDirect(declared);
            decoder.decompress(source, destination);
            if (destination.position() != declared) {
                scene.declaredSizeMismatch++;
            }
            if (source.hasRemaining()) {
                scene.trailingBytes++;
            }
            destination.flip();
            byte[] restored = new byte[declared];
            destination.get(restored);
            ByteBuf raw = Unpooled.wrappedBuffer(restored);
            try {
                int index = 0;
                while (raw.isReadable()) {
                    int size = NebVarInt.read(raw);
                    ByteBuf body = raw.readSlice(size);
                    ByteBuf decoded = inboundIndex.decode(ALLOCATOR, body, CUSTOM_PAYLOAD_ID);
                    byte[] actual = new byte[decoded == null ? size : decoded.readableBytes()];
                    if (decoded == null) {
                        body.getBytes(body.readerIndex(), actual);
                    } else {
                        decoded.getBytes(decoded.readerIndex(), actual);
                        decoded.release();
                    }
                    if (index >= originals.size() || !Arrays.equals(actual, originals.get(index))) {
                        scene.originalsMismatch++;
                    }
                    index++;
                }
                if (index != originals.size()) {
                    scene.originalsMismatch++;
                }
            } finally {
                raw.release();
            }
        }

        public void collectSample(byte[] input) {
            if (scene.samples.size() >= MAX_SAMPLES) {
                return;
            }
            int size = Math.min(input.length, MAX_SAMPLE_BYTES);
            scene.samples.add(Arrays.copyOf(input, size));
        }

        public void close() {
            accumulator.release();
            encoder.close();
            decoder.close();
        }
    }

    public static ByteBuf indexChannels(ByteBuf payload, ChannelIndex index) {
        if (!carriesCustomPayload(payload)) {
            return null;
        }
        ByteBuf result = ALLOCATOR.buffer(payload.readableBytes() + 16);
        ByteBuf view = payload.duplicate();
        try {
            while (view.isReadable()) {
                int bodySize = NebVarInt.read(view);
                ByteBuf body = view.readSlice(bodySize);
                ByteBuf token = index.encode(ALLOCATOR, body, CUSTOM_PAYLOAD_ID);
                if (token == null) {
                    NebVarInt.write(result, bodySize);
                    result.writeBytes(body, body.readerIndex(), bodySize);
                } else {
                    try {
                        NebVarInt.write(result, token.readableBytes());
                        result.writeBytes(token);
                    } finally {
                        token.release();
                    }
                }
            }
            return result;
        } catch (RuntimeException failure) {
            result.release();
            throw failure;
        }
    }

    public static boolean carriesCustomPayload(ByteBuf payload) {
        NebVarInt.Cursor cursor = new NebVarInt.Cursor();
        int index = payload.readerIndex();
        int end = payload.writerIndex();
        while (index < end) {
            cursor.at(index);
            if (!NebVarInt.readAt(payload, cursor)) {
                return false;
            }
            int bodySize = cursor.value;
            int bodyStart = cursor.index;
            if (bodySize <= 0 || bodySize > end - bodyStart) {
                return false;
            }
            cursor.at(bodyStart);
            if (NebVarInt.readAt(payload, cursor) && cursor.value == CUSTOM_PAYLOAD_ID) {
                return true;
            }
            index = bodyStart + bodySize;
        }
        return false;
    }

    public static byte[] dictionaryExperiment(Scene reference, PacketCodecType type) {
        List<byte[]> samples = reference.samples;
        if (samples.size() < 64) {
            System.out.println("SKIP not enough samples: " + samples.size());
            return null;
        }
        List<byte[]> training = new ArrayList<>();
        List<Integer> validation = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            if (i % 4 == 0) {
                validation.add(i);
            } else {
                training.add(samples.get(i));
            }
        }
        byte[] destination = new byte[BandwidthDictionary.MAX_DICTIONARY_BYTES];
        long trained = Zstd.trainFromBuffer(training.toArray(new byte[0][]), destination, false);
        if (Zstd.isError(trained) || trained < 512) {
            System.out.println(
                "SKIP training failed: " + (Zstd.isError(trained) ? Zstd.getErrorName(trained) : trained));
            return null;
        }
        byte[] dictionary = Arrays.copyOf(destination, (int) trained);
        long validationBytes = 0L;
        for (int index : validation) {
            validationBytes += samples.get(index).length;
        }
        long baseline = score(samples, validation, new byte[0], type);
        long candidate = score(samples, validation, dictionary, type);
        long expectedTraffic = BandwidthDictionary.minimumTrafficFor(dictionary.length);
        long baselineProjected = scale(baseline, expectedTraffic, validationBytes);
        long candidateProjected = scale(candidate, expectedTraffic, validationBytes);
        long frameBytes = dictionaryFrameBytes(dictionary.length, 1);
        long netSaved = baselineProjected - candidateProjected - frameBytes;
        boolean improvement = candidateProjected * 100L <= baselineProjected * 98L;
        boolean publish = improvement && netSaved > 0L;
        System.out.println(
            "samples=" + samples.size()
                + " training="
                + training.size()
                + " validation="
                + validation.size()
                + " dictionaryBytes="
                + dictionary.length);
        System.out.println(
            "validationStreamWire base=" + baseline
                + " candidate="
                + candidate
                + " saving="
                + percent(baseline, candidate));
        System.out.println(
            "gate traffic=" + human(expectedTraffic)
                + " projectedBase="
                + baselineProjected
                + " projectedCandidate="
                + candidateProjected
                + " dictionaryFrame="
                + frameBytes
                + " net="
                + netSaved
                + " improvementOver2Percent="
                + improvement
                + " netPositive="
                + (netSaved > 0L)
                + " verdict="
                + (publish ? "PUBLISH" : "REJECT"));
        BandwidthDictionary.Snapshot snapshot = new BandwidthDictionary.Snapshot(1, dictionary);
        ByteBuf control = DictionaryFrame.encode(ALLOCATOR, snapshot);
        boolean matches = DictionaryFrame.matches(control);
        BandwidthDictionary.Snapshot decoded = matches ? DictionaryFrame.decode(control) : null;
        control.release();
        boolean controlOk = decoded != null && decoded.version() == 1
            && decoded.size() == dictionary.length
            && Arrays.equals(decoded.copyBytes(), dictionary);
        System.out.println("dictionaryControlFrameRoundTrip=" + (controlOk ? "PASS" : "FAIL"));
        if (!controlOk) {
            throw new IllegalStateException("dictionary control frame round trip failed");
        }
        return dictionary;
    }

    public static long score(List<byte[]> samples, List<Integer> validation, byte[] dictionary, PacketCodecType type) {
        PacketCodec codec = PacketCodecRegistry.create(type);
        try {
            if (!(codec instanceof DictionaryCodec aware)) {
                throw new IllegalStateException("dictionary codec required");
            }
            if (dictionary.length != 0) {
                aware.loadDictionary(dictionary);
            }
            long total = 0L;
            int cursor = 0;
            for (int i = 0; i < samples.size(); i++) {
                byte[] sample = samples.get(i);
                ByteBuffer source = ByteBuffer.allocateDirect(sample.length);
                source.put(sample);
                source.flip();
                ByteBuffer destination = ByteBuffer.allocateDirect(codec.compressBound(sample.length));
                codec.compress(source, destination);
                if (cursor < validation.size() && validation.get(cursor) == i) {
                    total += frameWireBytes(sample.length, destination.position());
                    cursor++;
                }
            }
            return total;
        } finally {
            codec.close();
        }
    }

    public static long frameWireBytes(int rawSize, int compressedSize) {
        int frameSize = 1 + NebVarInt.sizeOf(rawSize) + compressedSize;
        long wireSize = (long) frameSize + NebVarInt.sizeOf(frameSize);
        return wireSize >= rawSize ? rawSize : wireSize;
    }

    public static long dictionaryFrameBytes(int dictionaryBytes, int version) {
        int body = 1 + NebVarInt.sizeOf(version) + NebVarInt.sizeOf(dictionaryBytes) + dictionaryBytes;
        return (long) body + NebVarInt.sizeOf(body);
    }

    public static long scale(long value, long target, long source) {
        if (value <= 0L || target <= 0L || source <= 0L) {
            return 0L;
        }
        return (value * target + source - 1L) / source;
    }

    public static String percent(long baseline, long candidate) {
        if (baseline <= 0L) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.2f%%", (baseline - candidate) * 100.0d / baseline);
    }

    public static String human(long bytes) {
        if (bytes < 1024L) {
            return bytes + "B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1fKiB", bytes / 1024.0d);
        }
        return String.format(Locale.ROOT, "%.2fMiB", bytes / (1024.0d * 1024.0d));
    }

    public static void printSceneTable(List<Scene> scenes) {
        System.out.printf(
            Locale.ROOT,
            "%-6s %-9s %-6s %8s %11s %11s %11s %12s %8s %8s %7s %7s%n",
            "window",
            "threshold",
            "model",
            "frames",
            "pkts/frame",
            "indexedRaw",
            "wire",
            "wire/raw",
            "header%",
            "stored",
            "reset",
            "resets");
        for (Scene scene : scenes) {
            long headerBytes = scene.frameHeaderBytes + scene.frameLengthBytes;
            double headerPercent = scene.wireBytes == 0 ? 0.0d : headerBytes * 100.0d / scene.wireBytes;
            System.out.printf(
                Locale.ROOT,
                "%-6s %-9d %-6s %8d %11.2f %11s %11s %12.4f %7.3f%% %7d %7d %7d%n",
                scene.windowMillis + "ms",
                scene.flushThreshold,
                scene.rearm ? "rearm" : "stale",
                scene.frames,
                scene.frames == 0 ? 0.0d : (double) scene.packets / scene.frames,
                human(scene.rawBytes),
                human(scene.wireBytes),
                scene.rawBytes == 0 ? 0.0d : (double) scene.wireBytes / scene.rawBytes,
                headerPercent,
                scene.storedFrames,
                scene.resetFrames,
                scene.codecResets);
        }
    }

    public static class Mix {

        public final String label;

        public final int count;

        public final int averageBytes;

        public final int kind;

        public final boolean bypass;

        public final String channel;

        public Mix(String label, int count, int averageBytes, int kind, boolean bypass, String channel) {
            this.label = label;
            this.count = count;
            this.averageBytes = averageBytes;
            this.kind = kind;
            this.bypass = bypass;
            this.channel = channel;
        }
    }

    public static class SimPacket {

        public final byte[] body;

        public final long arrivalNanos;

        public final boolean bypass;

        public final String label;

        public SimPacket(byte[] body, long arrivalNanos, boolean bypass, String label) {
            this.body = body;
            this.arrivalNanos = arrivalNanos;
            this.bypass = bypass;
            this.label = label;
        }
    }

    public static class Scene {

        public final int windowMillis;

        public final int flushThreshold;

        public final boolean rearm;

        public final List<byte[]> samples = new ArrayList<>();

        public long frames;

        public long framesBeforeDictionary;

        public long framesAfterDictionary;

        public long packets;

        public long rawBytes;

        public long wireBytes;

        public long frameHeaderBytes;

        public long frameLengthBytes;

        public long plainBytes;

        public long storedFrames;

        public long bypassFrames;

        public long resetFrames;

        public long codecResets;

        public long decoderResets;

        public long declaredSizeMismatch;

        public long trailingBytes;

        public long originalsMismatch;

        public long dictionaryFrames;

        public boolean roundTripOk = true;

        public Scene(int windowMillis, int flushThreshold, boolean rearm) {
            this.windowMillis = windowMillis;
            this.flushThreshold = flushThreshold;
            this.rearm = rearm;
        }

        public String describe() {
            return windowMillis + "ms/" + flushThreshold + "/" + (rearm ? "rearm" : "stale");
        }
    }

    private NebPacketSimulation() {}
}
