package cn.ussshenzhou.notenoughbandwidth.network;

import net.minecraft.entity.player.EntityPlayerMP;

import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecRegistry;
import cn.ussshenzhou.notenoughbandwidth.stat.NebStats;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class StatQueryMessage implements IMessage {

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<StatQueryMessage, StatReportMessage> {

        @Override
        public StatReportMessage onMessage(StatQueryMessage message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null || !NebNetwork.isOperator(player)) {
                return null;
            }
            return new StatReportMessage(
                NebStats.snapshot(),
                PacketCodecRegistry.preferred()
                    .configName(),
                StatPayload.trim(
                    NebStats.outboundProfile()
                        .snapshot()),
                StatPayload.trim(
                    NebStats.inboundProfile()
                        .snapshot()),
                NebStats.timeline()
                    .snapshot());
        }
    }
}
