package cn.ussshenzhou.notenoughbandwidth.report;

import java.util.List;

import cn.ussshenzhou.notenoughbandwidth.stat.PacketKindSample;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficSnapshot;

/**
 * Everything a report needs, frozen at one instant.
 * <p>
 * Written out longhand rather than as a record so the jar stays plain Java 8 bytecode; see the
 * note next to {@code enableModernJavaSyntax} in gradle.properties.
 *
 * @author USS_Shenzhou
 */
public class ReportSnapshot {

    private final long takenAtMillis;
    private final String side;
    private final String codec;
    private final String codecDetail;
    private final int aggregationWindowMillis;
    private final TrafficSnapshot traffic;
    private final List<PacketKindSample> outbound;
    private final List<PacketKindSample> inbound;
    private final List<SessionSample> sessions;

    /**
     * @param takenAtMillis           wall clock time the snapshot was taken
     * @param side                    {@code "server"} or {@code "client"}
     * @param codec                   the backend this side prefers
     * @param codecDetail             human readable description of that backend
     * @param aggregationWindowMillis the configured hold-back window, for context
     * @param traffic                 side-wide totals
     * @param outbound                heaviest outgoing packet kinds, heaviest first
     * @param inbound                 heaviest incoming packet kinds, heaviest first
     * @param sessions                live connections
     */
    public ReportSnapshot(long takenAtMillis, String side, String codec, String codecDetail,
        int aggregationWindowMillis, TrafficSnapshot traffic, List<PacketKindSample> outbound,
        List<PacketKindSample> inbound, List<SessionSample> sessions) {
        this.takenAtMillis = takenAtMillis;
        this.side = side;
        this.codec = codec;
        this.codecDetail = codecDetail;
        this.aggregationWindowMillis = aggregationWindowMillis;
        this.traffic = traffic;
        this.outbound = outbound;
        this.inbound = inbound;
        this.sessions = sessions;
    }

    public long takenAtMillis() {
        return takenAtMillis;
    }

    public String side() {
        return side;
    }

    public String codec() {
        return codec;
    }

    public String codecDetail() {
        return codecDetail;
    }

    public int aggregationWindowMillis() {
        return aggregationWindowMillis;
    }

    public TrafficSnapshot traffic() {
        return traffic;
    }

    public List<PacketKindSample> outbound() {
        return outbound;
    }

    public List<PacketKindSample> inbound() {
        return inbound;
    }

    public List<SessionSample> sessions() {
        return sessions;
    }
}
