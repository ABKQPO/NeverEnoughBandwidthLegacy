package cn.ussshenzhou.notenoughbandwidth.stat;

/**
 * Side-wide totals, summed over every connection this process has had.
 * <p>
 * Per-connection numbers live on the session instead, so the server report can name the player who
 * is costing the most bandwidth. Packet kind attribution is only kept here, because "which packet
 * dominates the stream" is a property of the installation, not of one player.
 *
 * @author USS_Shenzhou
 */
public class NebStats {

    private static final TrafficStats TRAFFIC = new TrafficStats();
    private static final PacketProfile OUTBOUND = new PacketProfile();
    private static final PacketProfile INBOUND = new PacketProfile();

    public static TrafficStats traffic() {
        return TRAFFIC;
    }

    public static PacketProfile outboundProfile() {
        return OUTBOUND;
    }

    public static PacketProfile inboundProfile() {
        return INBOUND;
    }

    public static TrafficSnapshot snapshot() {
        return TrafficSnapshot.of(TRAFFIC);
    }

    public static void reset() {
        TRAFFIC.reset();
        OUTBOUND.reset();
        INBOUND.reset();
    }

    private NebStats() {}
}
