package cn.ussshenzhou.notenoughbandwidth.pipeline;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cn.ussshenzhou.notenoughbandwidth.session.NebSession;
import cn.ussshenzhou.notenoughbandwidth.session.NebSessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;

/**
 * Decides where in a Netty pipeline the two NEB handlers belong, and refuses to install them when
 * the pipeline does not look like what we expect.
 *
 * <pre>
 * head → timeout → [legacy_query] → splitter → neb_expander → decoder
 *                                                              ↓
 *      ← prepender ← neb_aggregator ← encoder ← packet_handler ←
 * </pre>
 *
 * The aggregator goes immediately before {@code encoder} so that, walking outbound from the tail,
 * it is reached after the encoder has produced a packet body and before the prepender turns it
 * into a frame. The expander goes immediately after {@code splitter} for the mirror reason. Both
 * positions survive the later insertion of the encryption handlers, which Minecraft places around
 * {@code splitter} and {@code prepender}.
 * <p>
 * Handlers are installed as soon as the channel goes active, long before compression is turned on.
 * That ordering is deliberate: the expander has to already be in place when the first frame of a
 * peer that enabled compression before us arrives, and installing it later would leave a window in
 * which frames are read with the wrong reader.
 *
 * @author USS_Shenzhou
 */
public class NebPipeline {

    private static final String ENCODER = "encoder";
    private static final String SPLITTER = "splitter";

    /**
     * @return the session now backing this channel, or {@code null} when NEB does not apply
     */
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

    private NebPipeline() {}
}
