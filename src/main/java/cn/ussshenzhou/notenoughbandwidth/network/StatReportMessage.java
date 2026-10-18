package cn.ussshenzhou.notenoughbandwidth.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import cn.ussshenzhou.notenoughbandwidth.stat.PacketKindSample;
import cn.ussshenzhou.notenoughbandwidth.stat.RemoteStats;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficPoint;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficSnapshot;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class StatReportMessage implements IMessage {

    private TrafficSnapshot snapshot = TrafficSnapshot.EMPTY;
    private String codec = "";
    private List<PacketKindSample> outbound = Collections.emptyList();
    private List<PacketKindSample> inbound = Collections.emptyList();
    private List<TrafficPoint> timeline = Collections.emptyList();

    public StatReportMessage() {}

    public StatReportMessage(TrafficSnapshot snapshot, String codec, List<PacketKindSample> outbound,
        List<PacketKindSample> inbound) {
        this(snapshot, codec, outbound, inbound, Collections.emptyList());
    }

    public StatReportMessage(TrafficSnapshot snapshot, String codec, List<PacketKindSample> outbound,
        List<PacketKindSample> inbound, List<TrafficPoint> timeline) {
        this.snapshot = snapshot;
        this.codec = codec;
        this.outbound = new ArrayList<>(outbound);
        this.inbound = new ArrayList<>(inbound);
        this.timeline = new ArrayList<>(timeline);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        snapshot = StatPayload.readSnapshot(buf);
        codec = ByteBufUtils.readUTF8String(buf);
        outbound = StatPayload.readKinds(buf);
        inbound = StatPayload.readKinds(buf);
        timeline = StatPayload.readTimeline(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        StatPayload.writeSnapshot(buf, snapshot);
        ByteBufUtils.writeUTF8String(buf, codec);
        StatPayload.writeKinds(buf, outbound);
        StatPayload.writeKinds(buf, inbound);
        StatPayload.writeTimeline(buf, timeline);
    }

    public TrafficSnapshot snapshot() {
        return snapshot;
    }

    public String codec() {
        return codec;
    }

    public List<PacketKindSample> outbound() {
        return outbound;
    }

    public List<PacketKindSample> inbound() {
        return inbound;
    }

    public List<TrafficPoint> timeline() {
        return timeline;
    }

    public static class Handler implements IMessageHandler<StatReportMessage, IMessage> {

        @Override
        public IMessage onMessage(StatReportMessage message, MessageContext ctx) {
            RemoteStats
                .accept(message.snapshot(), message.codec(), message.outbound(), message.inbound(), message.timeline());
            return null;
        }
    }
}
