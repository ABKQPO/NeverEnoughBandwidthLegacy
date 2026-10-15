package cn.ussshenzhou.notenoughbandwidth.pipeline;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cn.ussshenzhou.notenoughbandwidth.session.NebSession;
import cn.ussshenzhou.notenoughbandwidth.session.NebSessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;

public class NebPipeline {

    private static final String ENCODER = "encoder";
    private static final String SPLITTER = "splitter";

    public static NebSession install(Channel channel) {
        if (!NebConfig.enabled || channel == null) {
            return null;
        }
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(ModConstants.HANDLER_AGGREGATOR) != null) {
            return NebSessionRegistry.of(channel);
        }
        if (pipeline.get(ENCODER) == null || pipeline.get(SPLITTER) == null) {
            // An in-memory connection for single player, which carries Packet objects, not bytes.
            return null;
        }
        try {
            NebSession session = NebSessionRegistry.open(channel);
            pipeline.addBefore(ENCODER, ModConstants.HANDLER_AGGREGATOR, new PacketAggregator(session));
            pipeline.addAfter(SPLITTER, ModConstants.HANDLER_EXPANDER, new PacketExpander(session));
            return session;
        } catch (Throwable t) {
            NebLog.error(
                "Could not install the NEB handlers on {}, this connection stays uncompressed.",
                channel.remoteAddress(),
                t);
            return null;
        }
    }
}
