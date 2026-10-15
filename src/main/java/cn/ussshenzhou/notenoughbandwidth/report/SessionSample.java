package cn.ussshenzhou.notenoughbandwidth.report;

import cn.ussshenzhou.notenoughbandwidth.stat.TrafficSnapshot;

public class SessionSample {

    private final String peer;
    private final String codec;
    private final boolean compressing;
    private final TrafficSnapshot traffic;

    /**
     * @param peer        player name on a server, {@code "server"} on a client
     * @param codec       the backend this connection settled on
     * @param compressing whether the handshake completed and frames are being compressed
     * @param traffic     this connection's own totals
     */
    public SessionSample(String peer, String codec, boolean compressing, TrafficSnapshot traffic) {
        this.peer = peer;
        this.codec = codec;
        this.compressing = compressing;
        this.traffic = traffic;
    }

    public String peer() {
        return peer;
    }

    public String codec() {
        return codec;
    }

    public boolean compressing() {
        return compressing;
    }

    public TrafficSnapshot traffic() {
        return traffic;
    }
}
