package cn.ussshenzhou.notenoughbandwidth.mixin.early;

import net.minecraft.network.NetworkManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import cn.ussshenzhou.notenoughbandwidth.pipeline.NebPipeline;
import io.netty.channel.ChannelHandlerContext;

/**
 * The mod's only touch point with Minecraft code: once a connection is live, add the two NEB
 * handlers to its pipeline.
 * <p>
 * {@code channelActive} is used because it is the single place both the client and the server end
 * up in, it runs on the connection's event loop with a fully built pipeline, and it is purely
 * additive, so it cannot conflict with the several 1.7.10 mods that also mix into this class.
 *
 * @author USS_Shenzhou
 */
@Mixin(NetworkManager.class)
public abstract class MixinNetworkManager {

    @Inject(method = "channelActive", at = @At("TAIL"), require = 1)
    private void neb$installPipeline(ChannelHandlerContext context, CallbackInfo callback) {
        NebPipeline.install(context.channel());
    }
}
