package cn.ussshenzhou.notenoughbandwidth.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecRegistry;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cn.ussshenzhou.notenoughbandwidth.session.NebSession;
import cn.ussshenzhou.notenoughbandwidth.session.NebSessionRegistry;
import cn.ussshenzhou.notenoughbandwidth.stat.NebStats;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficSnapshot;

public class ReportBuilder {

    public static ReportSnapshot capture(String side) {
        PacketCodecType preferred = PacketCodecRegistry.preferred();
        return new ReportSnapshot(
            System.currentTimeMillis(),
            side,
            preferred.configName(),
            PacketCodecRegistry.describe(preferred),
            NebConfig.aggregationWindowMillis,
            NebStats.snapshot(),
            NebStats.outboundProfile()
                .snapshot(),
            NebStats.inboundProfile()
                .snapshot(),
            sessions());
    }

    private static List<SessionSample> sessions() {
        List<SessionSample> samples = new ArrayList<>();
        for (NebSession session : NebSessionRegistry.live()) {
            samples.add(
                new SessionSample(
                    session.peerLabel(),
                    session.outboundType()
                        .configName(),
                    session.outboundEnabled(),
                    TrafficSnapshot.of(session.stats())));
        }
        samples.sort(
            Comparator.comparingLong(
                (SessionSample sample) -> sample.traffic()
                    .totalRawBytes())
                .reversed());
        return samples;
    }
}
