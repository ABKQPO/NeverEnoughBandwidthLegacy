package cn.ussshenzhou.notenoughbandwidth.mixin;

import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.gtnewhorizon.gtnhmixins.builders.MixinBuilder;

import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;

/**
 * Mixin registry in the GTNH style: one enum constant per feature, each able to opt itself out.
 *
 * @author USS_Shenzhou
 */
public enum Mixins implements IMixins {

    NETWORK_PIPELINE(new MixinBuilder("Install the aggregating and compressing pipeline handlers")
        .addCommonMixins("MixinNetworkManager")
        .setPhase(Phase.EARLY)
        .setApplyIf(() -> NebConfig.enabled));

    private final MixinBuilder builder;

    Mixins(MixinBuilder builder) {
        this.builder = builder;
    }

    @Override
    public MixinBuilder getBuilder() {
        return builder;
    }
}
