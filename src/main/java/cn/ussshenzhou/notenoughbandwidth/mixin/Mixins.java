package cn.ussshenzhou.notenoughbandwidth.mixin;

import javax.annotation.Nonnull;

import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.gtnewhorizon.gtnhmixins.builders.MixinBuilder;

import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;

public enum Mixins implements IMixins {

    NETWORK_PIPELINE(new MixinBuilder("Install the aggregating and compressing pipeline handlers")
        .addCommonMixins("MixinNetworkManager")
        .setPhase(Phase.EARLY)
        .setApplyIf(() -> NebConfig.enabled));

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
