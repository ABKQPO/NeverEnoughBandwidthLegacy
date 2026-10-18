package cn.ussshenzhou.notenoughbandwidth.mixin;

import javax.annotation.Nonnull;

import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.gtnewhorizon.gtnhmixins.builders.MixinBuilder;

import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;

public enum Mixins implements IMixins {

    NETWORK_PIPELINE(new MixinBuilder("Install the aggregating and compressing pipeline handlers")
        .addCommonMixins("MixinNetworkManager", "MixinNetworkDispatcher")
        .setPhase(Phase.EARLY)
        .setApplyIf(() -> NebConfig.enabled)),
    CHUNK_API_COMPAT(new MixinBuilder("Read ChunkAPI bulk chunk packets without replacing packet writers")
        .addCommonMixins("MixinS26PacketMapChunkBulk")
        .setPhase(Phase.EARLY)
        .setApplyIf(() -> NebConfig.enabled)),
    DELAYED_CHUNK_CACHE(new MixinBuilder("Retain recently watched chunks without replacing vanilla chunk management")
        .addCommonMixins("MixinPlayerManager", "MixinPlayerInstance")
        .setPhase(Phase.EARLY)
        .setApplyIf(() -> NebConfig.enabled && NebConfig.delayedChunkCache));

    private final MixinBuilder builder;

    Mixins(MixinBuilder builder) {
        this.builder = builder;
    }

    @Nonnull
    @Override
    public MixinBuilder getBuilder() {
        return builder;
    }
}
