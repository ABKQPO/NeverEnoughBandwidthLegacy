package cn.ussshenzhou.notenoughbandwidth.capture;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;

public class DatasetAnalysis {

    private final Path dataset;
    private final Path csv;
    private final String side;
    private final List<CodecResult> results;

    public DatasetAnalysis(Path dataset, Path csv, String side, List<CodecResult> results) {
        this.dataset = dataset;
        this.csv = csv;
        this.side = side;
        this.results = Collections.unmodifiableList(new ArrayList<>(results));
    }

    public Path dataset() {
        return dataset;
    }

    public Path csv() {
        return csv;
    }

    public String side() {
        return side;
    }

    public List<CodecResult> results() {
        return results;
    }

    public static class CodecResult {

        private final boolean outbound;
        private final PacketCodecType codec;
        private final long rawBytes;
        private final long wireBytes;
        private final long frameCount;
        private final long packetCount;

        public CodecResult(boolean outbound, PacketCodecType codec, long rawBytes, long wireBytes, long frameCount,
            long packetCount) {
            this.outbound = outbound;
            this.codec = codec;
            this.rawBytes = rawBytes;
            this.wireBytes = wireBytes;
            this.frameCount = frameCount;
            this.packetCount = packetCount;
        }

        public boolean outbound() {
            return outbound;
        }

        public PacketCodecType codec() {
            return codec;
        }

        public long rawBytes() {
            return rawBytes;
        }

        public long wireBytes() {
            return wireBytes;
        }

        public long frameCount() {
            return frameCount;
        }

        public long packetCount() {
            return packetCount;
        }

        public double ratio() {
            return rawBytes == 0 ? 0 : (double) wireBytes / rawBytes;
        }
    }
}
