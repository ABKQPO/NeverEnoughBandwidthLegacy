package cn.ussshenzhou.notenoughbandwidth.mixin.early;

import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import cn.ussshenzhou.notenoughbandwidth.pipeline.NebPipeline;
import io.netty.channel.ChannelHandlerContext;

@Mixin(NetworkManager.class)
public abstract class MixinNetworkManager {

    @Inject(method = "channelActive", at = @At("TAIL"), require = 1)
    private void neb$installPipeline(ChannelHandlerContext context, CallbackInfo callback) {
        NebPipeline.install(context.channel());
    }

    @Inject(method = "setConnectionState", at = @At("TAIL"), require = 0)
    private void neb$installAfterStateChange(EnumConnectionState state, CallbackInfo callback) {
        NetworkManager manager = (NetworkManager) (Object) this;
        if (manager.channel() != null) {
            NebPipeline.install(manager.channel());
        }
    }
}
