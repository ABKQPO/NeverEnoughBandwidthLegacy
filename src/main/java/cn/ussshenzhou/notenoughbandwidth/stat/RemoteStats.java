package cn.ussshenzhou.notenoughbandwidth.stat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RemoteStats {

    private static volatile TrafficSnapshot snapshot = TrafficSnapshot.EMPTY;
    private static volatile String codec = "";
    private static volatile List<PacketKindSample> outbound = Collections.emptyList();
    private static volatile List<PacketKindSample> inbound = Collections.emptyList();
    private static volatile List<TrafficPoint> timeline = Collections.emptyList();
    private static volatile long receivedAt;

    public static void accept(TrafficSnapshot newSnapshot, String newCodec, List<PacketKindSample> newOutbound,
        List<PacketKindSample> newInbound) {
        accept(newSnapshot, newCodec, newOutbound, newInbound, Collections.emptyList());
    }

    public static void accept(TrafficSnapshot newSnapshot, String newCodec, List<PacketKindSample> newOutbound,
        List<PacketKindSample> newInbound, List<TrafficPoint> newTimeline) {
        snapshot = newSnapshot;
        codec = newCodec;
        outbound = Collections.unmodifiableList(new ArrayList<>(newOutbound));
        inbound = Collections.unmodifiableList(new ArrayList<>(newInbound));
        timeline = Collections.unmodifiableList(new ArrayList<>(newTimeline));
        receivedAt = System.currentTimeMillis();
    }

    public static void clear() {
        snapshot = TrafficSnapshot.EMPTY;
        codec = "";
        outbound = Collections.emptyList();
        inbound = Collections.emptyList();
        timeline = Collections.emptyList();
        receivedAt = 0L;
    }

    public static boolean present() {
        return receivedAt != 0L;
    }

    public static TrafficSnapshot snapshot() {
        return snapshot;
    }

    public static String codec() {
        return codec;
    }

    public static List<PacketKindSample> outbound() {
        return outbound;
    }

    public static List<PacketKindSample> inbound() {
        return inbound;
    }

    public static long receivedAt() {
        return receivedAt;
    }

    public static List<TrafficPoint> timeline() {
        return timeline;
    }

    private RemoteStats() {}
}
