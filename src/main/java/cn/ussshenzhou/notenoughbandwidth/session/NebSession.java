package cn.ussshenzhou.notenoughbandwidth.session;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.codec.DictionaryCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodec;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecRegistry;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import cn.ussshenzhou.notenoughbandwidth.codec.zstd.BandwidthDictionary;
import cn.ussshenzhou.notenoughbandwidth.codec.zstd.BandwidthDictionary.Snapshot;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficStats;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.DecoderException;

public class NebSession {

    private final Channel channel;
    private final TrafficStats stats = new TrafficStats();
    private final Map<PacketCodecType, PacketCodec> inboundCodecs = new EnumMap<>(PacketCodecType.class);
    private final BandwidthDictionary dictionaryTrainer = new BandwidthDictionary();

    private volatile boolean outboundEnabled;
    private volatile boolean negotiated;
    private volatile PacketCodecType outboundType = PacketCodecType.STORED;
    private volatile String peerLabel;
    private volatile int peerProtocolVersion = -1;
    private PacketCodec outboundCodec;
    private volatile boolean server;
    private volatile boolean dictionaryCapable;
    private volatile boolean released;
    private boolean clientHelloSent;
    private boolean serverSelectionSent;
    private PacketCodecType serverSelection = PacketCodecType.STORED;
    private volatile Snapshot outboundDictionary = BandwidthDictionary.EMPTY;
    private volatile Snapshot inboundDictionary = BandwidthDictionary.EMPTY;
    private final CopyOnWriteArrayList<Runnable> negotiationListeners = new CopyOnWriteArrayList<>();
    private long dictionaryTraffic;

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

    public boolean negotiated() {
        return negotiated;
    }

    public void whenNegotiated(Runnable listener) {
        if (listener == null) {
            return;
        }
        negotiationListeners.addIfAbsent(listener);
        if (negotiated) {
            notifyNegotiated(listener);
        }
    }

    public void removeNegotiationListener(Runnable listener) {
        negotiationListeners.remove(listener);
    }

    public PacketCodecType outboundType() {
        return outboundType;
    }

    public int outboundDictionaryVersion() {
        return outboundDictionary.version();
    }

    public int outboundDictionarySize() {
        return outboundDictionary.size();
    }

    public int inboundDictionaryVersion() {
        return inboundDictionary.version();
    }

    public int inboundDictionarySize() {
        return inboundDictionary.size();
    }

    public void configurePeer(boolean server, int capabilityMask) {
        this.server = server;
        this.dictionaryCapable = NebConfig.enableBandwidthDictionary
            && (capabilityMask & ModConstants.DICTIONARY_CAPABILITY) != 0;
    }

    public boolean markClientHelloSent() {
        if (clientHelloSent) {
            return false;
        }
        clientHelloSent = true;
        return true;
    }

    public boolean markServerSelection(PacketCodecType type) {
        if (serverSelectionSent || type == null) {
            return false;
        }
        serverSelectionSent = true;
        serverSelection = type;
        return true;
    }

    public boolean serverSelectionMatches(PacketCodecType type) {
        return serverSelectionSent && serverSelection == type;
    }

    public PacketCodecType serverSelection() {
        return serverSelection;
    }

    public void observeOutbound(ByteBuf payload) {
        if (server && dictionaryCapable && NebConfig.enableBandwidthDictionary && isZstd(outboundType)) {
            dictionaryTraffic = Math.min(Long.MAX_VALUE - payload.readableBytes(), dictionaryTraffic)
                + payload.readableBytes();
            if (dictionaryTrainer.snapshot() != outboundDictionary) {
                return;
            }
            dictionaryTrainer.observe(payload, outboundType);
        }
    }

    public Snapshot nextOutboundDictionary() {
        if (!server || !dictionaryCapable || !isZstd(outboundType)) {
            return null;
        }
        Snapshot latest = dictionaryTrainer.snapshot();
        if (latest.size() == 0 || latest == outboundDictionary
            || latest.version() == outboundDictionary.version()
            || dictionaryTraffic < BandwidthDictionary.minimumTrafficFor(latest.size())) {
            return null;
        }
        return latest;
    }

    public void installOutboundDictionary(Snapshot dictionary) {
        if (released || !server || !dictionaryCapable || !outboundEnabled || !isZstd(outboundType)) {
            throw new IllegalStateException("NEB: outbound dictionary is not allowed in the current session");
        }
        if (dictionary == null || dictionary.size() == 0) {
            throw new IllegalArgumentException("NEB: outbound dictionary must not be empty");
        }
        if (dictionary.version() <= outboundDictionary.version()) {
            throw new IllegalArgumentException("NEB: outbound dictionary version must increase");
        }
        closeOutboundCodec();
        outboundDictionary = dictionary;
        dictionaryTraffic = 0;
        NebLog.debug(
            "Installed outbound bandwidth dictionary {} ({} bytes) for {}.",
            dictionary.version(),
            dictionary.size(),
            peerLabel);
    }

    public void installInboundDictionary(Snapshot dictionary) {
        if (!NebConfig.enableBandwidthDictionary || !dictionaryCapable
            || server
            || !outboundEnabled
            || !isZstd(outboundType)
            || dictionary == null) {
            throw new DecoderException("NEB: unsolicited dictionary control frame");
        }
        if (dictionary.version() <= inboundDictionary.version()) {
            throw new DecoderException("NEB: stale dictionary version " + dictionary.version());
        }
        for (PacketCodecType type : PacketCodecType.values()) {
            if (isZstd(type)) {
                closeQuietly(inboundCodecs.remove(type));
            }
        }
        inboundDictionary = dictionary;
        NebLog.debug(
            "Installed inbound bandwidth dictionary {} ({} bytes) from {}.",
            dictionary.version(),
            dictionary.size(),
            peerLabel);
    }

    /**
     * Starts compressing what we send. Safe to call from any thread and more than once; the switch
     * itself happens on the event loop, strictly between two frames.
     */
    public void enableOutbound(PacketCodecType type, int peerProtocol) {
        if (type == null) {
            throw new IllegalArgumentException("NEB: outbound codec must not be null");
        }
        if (released || !channel.isActive()) {
            return;
        }
        this.peerProtocolVersion = peerProtocol;
        if (outboundEnabled) {
            if (outboundType != type) {
                NebLog.warn(
                    "Ignoring a late codec switch for {} from {} to keep the compression stream aligned.",
                    peerLabel,
                    outboundType.configName(),
                    type.configName());
            }
            return;
        }
        Runnable activation = () -> activateOutbound(type);
        if (channel.eventLoop()
            .inEventLoop()) {
            activation.run();
        } else {
            channel.eventLoop()
                .execute(activation);
        }
    }

    public PacketCodec outboundCodec() {
        if (outboundCodec == null || outboundCodec.type() != outboundType) {
            closeOutboundCodec();
            PacketCodec codec = PacketCodecRegistry.create(outboundType);
            loadDictionary(codec, outboundDictionary);
            outboundCodec = codec;
        }
        return outboundCodec;
    }

    public PacketCodec inboundCodec(PacketCodecType type) {
        PacketCodec codec = inboundCodecs.get(type);
        if (codec == null) {
            try {
                codec = PacketCodecRegistry.create(type);
                loadDictionary(codec, inboundDictionary);
                inboundCodecs.put(type, codec);
            } catch (Throwable failure) {
                closeQuietly(codec);
                if (failure instanceof DecoderException decoderFailure) {
                    throw decoderFailure;
                }
                throw new DecoderException("NEB: invalid inbound codec state", failure);
            }
        }
        return codec;
    }

    public void resetOutboundCodec() {
        closeOutboundCodec();
    }

    public void resetInboundCodec(PacketCodecType type) {
        if (type != null) {
            closeQuietly(inboundCodecs.remove(type));
        }
    }

    public void release() {
        released = true;
        dictionaryTrainer.reset();
        closeOutboundCodec();
        for (PacketCodec codec : inboundCodecs.values()) {
            closeQuietly(codec);
        }
        inboundCodecs.clear();
        outboundEnabled = false;
        negotiated = false;
        negotiationListeners.clear();
        clientHelloSent = false;
        serverSelectionSent = false;
        serverSelection = PacketCodecType.STORED;
        outboundDictionary = BandwidthDictionary.EMPTY;
        inboundDictionary = BandwidthDictionary.EMPTY;
    }

    private void closeOutboundCodec() {
        if (outboundCodec != null) {
            closeQuietly(outboundCodec);
            outboundCodec = null;
        }
    }

    private void activateOutbound(PacketCodecType type) {
        if (released || !channel.isActive() || (outboundEnabled && outboundType == type)) {
            return;
        }
        closeOutboundCodec();
        outboundType = type;
        outboundEnabled = true;
        negotiated = true;
        NebLog.info(
            "Compression enabled for {} using {} ({})",
            peerLabel,
            type.configName(),
            PacketCodecRegistry.describe(type));
        for (Runnable listener : negotiationListeners) {
            listener.run();
        }
    }

    private static void closeQuietly(PacketCodec codec) {
        if (codec == null) {
            return;
        }
        try {
            codec.close();
        } catch (Throwable t) {
            NebLog.warn("Failed to release a {} codec.", codec.type(), t);
        }
    }

    private static void loadDictionary(PacketCodec codec, Snapshot dictionary) {
        if (dictionary.size() != 0 && codec instanceof DictionaryCodec aware) {
            try {
                aware.loadDictionary(dictionary.copyBytes());
            } catch (Throwable failure) {
                closeQuietly(codec);
                throw failure;
            }
        }
    }

    private static boolean isZstd(PacketCodecType type) {
        return type == PacketCodecType.ZSTD_STREAM || type == PacketCodecType.ZSTD_BLOCK;
    }

    private void notifyNegotiated(Runnable listener) {
        channel.eventLoop()
            .execute(() -> {
                if (!released && negotiated && negotiationListeners.contains(listener)) {
                    listener.run();
                }
            });
    }
}
