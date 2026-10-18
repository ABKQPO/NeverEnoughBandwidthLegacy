package cn.ussshenzhou.notenoughbandwidth.mixin.early;

import net.minecraft.network.NetworkManager;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import cn.ussshenzhou.notenoughbandwidth.pipeline.NebPipeline;
import cpw.mods.fml.common.network.handshake.NetworkDispatcher;
import cpw.mods.fml.relauncher.Side;

@Mixin(value = NetworkDispatcher.class, remap = false)
public abstract class MixinNetworkDispatcher {

    @Shadow(remap = false)
    @Final
    public NetworkManager manager;

    @Inject(method = "completeHandshake", at = @At("HEAD"), require = 1, remap = false)
    private void neb$installAfterHandshake(Side target, CallbackInfo callback) {
        if (manager != null) {
            NebPipeline.install(manager.channel());
        }
    }
}
