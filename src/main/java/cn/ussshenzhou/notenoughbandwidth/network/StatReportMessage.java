package cn.ussshenzhou.notenoughbandwidth.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import cn.ussshenzhou.notenoughbandwidth.stat.PacketKindSample;
import cn.ussshenzhou.notenoughbandwidth.stat.RemoteStats;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficSnapshot;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * The server's answer to {@link StatQueryMessage}.
 *
 * @author USS_Shenzhou
 */
public class StatReportMessage implements IMessage {

    private TrafficSnapshot snapshot = TrafficSnapshot.EMPTY;
    private String codec = "";
    private List<PacketKindSample> outbound = Collections.emptyList();
    private List<PacketKindSample> inbound = Collections.emptyList();

    public StatReportMessage() {}

    public StatReportMessage(TrafficSnapshot snapshot, String codec, List<PacketKindSample> outbound,
        List<PacketKindSample> inbound) {
        this.snapshot = snapshot;
        this.codec = codec;
        this.outbound = new ArrayList<>(outbound);
        this.inbound = new ArrayList<>(inbound);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        snapshot = StatPayload.readSnapshot(buf);
        codec = ByteBufUtils.readUTF8String(buf);
        outbound = StatPayload.readKinds(buf);
        inbound = StatPayload.readKinds(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        StatPayload.writeSnapshot(buf, snapshot);
        ByteBufUtils.writeUTF8String(buf, codec);
        StatPayload.writeKinds(buf, outbound);
        StatPayload.writeKinds(buf, inbound);
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

    public static class Handler implements IMessageHandler<StatReportMessage, IMessage> {

        @Override
        public IMessage onMessage(StatReportMessage message, MessageContext ctx) {
            RemoteStats.accept(message.snapshot(), message.codec(), message.outbound(), message.inbound());
            return null;
        }
    }
}
