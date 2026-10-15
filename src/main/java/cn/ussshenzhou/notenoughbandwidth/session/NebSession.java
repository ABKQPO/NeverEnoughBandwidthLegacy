package cn.ussshenzhou.notenoughbandwidth.session;

import java.util.EnumMap;
import java.util.Map;

import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecRegistry;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficStats;
import io.netty.channel.Channel;

public class NebSession {

    private final Channel channel;
    private final TrafficStats stats = new TrafficStats();
    private final Map<PacketCodecType, PacketCodec> inboundCodecs = new EnumMap<>(PacketCodecType.class);

    private volatile boolean outboundEnabled;
    private volatile PacketCodecType outboundType = PacketCodecType.STORED;
    private volatile String peerLabel;
    private volatile int peerProtocolVersion = -1;
    private PacketCodec outboundCodec;

    public NebSession(Channel channel) {
        this.channel = channel;
        this.peerLabel = String.valueOf(channel.remoteAddress());
    }

    public Channel channel() {
        return channel;
    }

    public TrafficStats stats() {
        return stats;
    }

    public String peerLabel() {
        return peerLabel;
    }

    public void peerLabel(String label) {
        if (label != null && !label.isEmpty()) {
            this.peerLabel = label;
        }
    }

    public int peerProtocolVersion() {
        return peerProtocolVersion;
    }

    public boolean outboundEnabled() {
        return outboundEnabled;
    }

    public PacketCodecType outboundType() {
        return outboundType;
    }

    /**
     * Starts compressing what we send. Safe to call from any thread and more than once; the switch
     * itself happens on the event loop, strictly between two frames.
     */
    public void enableOutbound(PacketCodecType type, int peerProtocol) {
        this.peerProtocolVersion = peerProtocol;
        if (outboundEnabled && outboundType == type) {
            return;
        }
        channel.eventLoop()
            .execute(() -> {
                closeOutboundCodec();
                outboundType = type;
                outboundEnabled = true;
                NebLog.info(
                    "Compression enabled for {} using {} ({})",
                    peerLabel,
                    type.configName(),
                    PacketCodecRegistry.describe(type));
            });
    }

    public PacketCodec outboundCodec() {
        if (outboundCodec == null || outboundCodec.type() != outboundType) {
            closeOutboundCodec();
            outboundCodec = PacketCodecRegistry.create(outboundType);
        }
        return outboundCodec;
    }

    public PacketCodec inboundCodec(PacketCodecType type) {
        PacketCodec codec = inboundCodecs.get(type);
        if (codec == null) {
            codec = PacketCodecRegistry.create(type);
            inboundCodecs.put(type, codec);
        }
        return codec;
    }

    public void release() {
        closeOutboundCodec();
        for (PacketCodec codec : inboundCodecs.values()) {
            closeQuietly(codec);
        }
        inboundCodecs.clear();
        outboundEnabled = false;
    }

    private void closeOutboundCodec() {
        if (outboundCodec != null) {
            closeQuietly(outboundCodec);
            outboundCodec = null;
        }
    }

    private static void closeQuietly(PacketCodec codec) {
        try {
            codec.close();
        } catch (Throwable t) {
            NebLog.warn("Failed to release a {} codec.", codec.type(), t);
        }
    }
}
