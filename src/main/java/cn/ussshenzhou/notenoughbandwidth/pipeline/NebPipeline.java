package cn.ussshenzhou.notenoughbandwidth.pipeline;

import java.util.concurrent.TimeUnit;

import net.minecraft.util.MessageDeserializer2;
import net.minecraft.util.MessageSerializer;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cn.ussshenzhou.notenoughbandwidth.session.NebSession;
import cn.ussshenzhou.notenoughbandwidth.session.NebSessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;

public class NebPipeline {

    private static final String ENCODER = "encoder";
    private static final String SPLITTER = "splitter";
    private static final int MAX_INSTALL_RETRIES = 200;
    private static final int INSTALL_RETRY_DELAY_MILLIS = 50;
    private static final AttributeKey<Integer> INSTALL_RETRIES = new AttributeKey<>("neb_install_retries");
    private static final AttributeKey<Boolean> INSTALL_RETRY_SCHEDULED = new AttributeKey<>(
        "neb_install_retry_scheduled");

    public static NebSession install(Channel channel) {
        if (!NebConfig.enabled || channel == null || !channel.isOpen() || isLocal(channel)) {
            return null;
        }
        if (!channel.eventLoop()
            .inEventLoop()) {
            channel.eventLoop()
                .execute(() -> install(channel));
            return null;
        }
        ChannelPipeline pipeline = channel.pipeline();
        String encoder = findHandler(pipeline, ENCODER, MessageSerializer.class);
        String splitter = findHandler(pipeline, SPLITTER, MessageDeserializer2.class);
        if (encoder == null || splitter == null) {
            scheduleRetry(channel);
            return null;
        }
        NebSession session = NebSessionRegistry.open(channel);
        if (pipeline.get(ModConstants.HANDLER_AGGREGATOR) != null
            && pipeline.get(ModConstants.HANDLER_EXPANDER) != null) {
            channel.attr(INSTALL_RETRIES)
                .remove();
            channel.attr(INSTALL_RETRY_SCHEDULED)
                .remove();
            return session;
        }
        boolean aggregatorAdded = false;
        boolean expanderAdded = false;
        try {
            if (pipeline.get(ModConstants.HANDLER_AGGREGATOR) == null) {
                pipeline.addBefore(encoder, ModConstants.HANDLER_AGGREGATOR, new PacketAggregator(session));
                aggregatorAdded = true;
            }
            if (pipeline.get(ModConstants.HANDLER_EXPANDER) == null) {
                pipeline.addAfter(splitter, ModConstants.HANDLER_EXPANDER, new PacketExpander(session));
                expanderAdded = true;
            }
            channel.attr(INSTALL_RETRIES)
                .remove();
            channel.attr(INSTALL_RETRY_SCHEDULED)
                .remove();
            NebLog.debug(
                "Installed the NEB handlers on {} (splitter {}, encoder {}).",
                channel.remoteAddress(),
                splitter,
                encoder);
            return session;
        } catch (Throwable t) {
            if (aggregatorAdded && pipeline.get(ModConstants.HANDLER_AGGREGATOR) != null) {
                pipeline.remove(ModConstants.HANDLER_AGGREGATOR);
            }
            if (expanderAdded && pipeline.get(ModConstants.HANDLER_EXPANDER) != null) {
                pipeline.remove(ModConstants.HANDLER_EXPANDER);
            }
            scheduleRetry(channel);
            NebLog.error(
                "Could not install the NEB handlers on {}, retrying while the pipeline settles.",
                channel.remoteAddress(),
                t);
            return null;
        }
    }

    private static String findHandler(ChannelPipeline pipeline, String preferred, Class<?> type) {
        if (type.isInstance(pipeline.get(preferred))) {
            return preferred;
        }
        for (String name : pipeline.names()) {
            if (type.isInstance(pipeline.get(name))) {
                return name;
            }
        }
        return null;
    }

    private static void scheduleRetry(Channel channel) {
        if (!channel.isOpen() || Boolean.TRUE.equals(
            channel.attr(INSTALL_RETRY_SCHEDULED)
                .get())) {
            return;
        }
        Integer retries = channel.attr(INSTALL_RETRIES)
            .get();
        int attempt = retries == null ? 0 : retries;
        if (attempt >= MAX_INSTALL_RETRIES) {
            NebLog.warn(
                "Unable to install the NEB handlers on {}; the transport anchors never appeared.",
                channel.remoteAddress());
            return;
        }
        channel.attr(INSTALL_RETRIES)
            .set(attempt + 1);
        channel.attr(INSTALL_RETRY_SCHEDULED)
            .set(Boolean.TRUE);
        ScheduledFuture<?> task = channel.eventLoop()
            .schedule(() -> {
                channel.attr(INSTALL_RETRY_SCHEDULED)
                    .remove();
                install(channel);
            }, INSTALL_RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        task.addListener(ignored -> {
            if (!channel.isOpen()) {
                channel.attr(INSTALL_RETRY_SCHEDULED)
                    .remove();
            }
        });
    }

    private static boolean isLocal(Channel channel) {
        return channel instanceof LocalChannel || channel instanceof LocalServerChannel;
    }
}
