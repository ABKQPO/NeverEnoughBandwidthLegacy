package cn.ussshenzhou.notenoughbandwidth.mixin.early;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.world.ChunkCoordIntPair;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import cn.ussshenzhou.notenoughbandwidth.chunk.ChunkCacheController;
import cn.ussshenzhou.notenoughbandwidth.chunk.ChunkWatch;

@Mixin(targets = "net.minecraft.server.management.PlayerManager$PlayerInstance")
public abstract class MixinPlayerInstance implements ChunkWatch {

    @Shadow
    @Final
    private ChunkCoordIntPair chunkLocation;

    @Shadow
    @Final
    private List<EntityPlayerMP> playersWatchingChunk;

    @Shadow(remap = false)
    private boolean loaded;

    @Shadow
    public abstract void removePlayer(EntityPlayerMP player);

    @Unique
    private ChunkCacheController neb$controller;

    @Unique
    private boolean neb$evicting;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void neb$captureController(PlayerManager manager, int x, int z, CallbackInfo ci) {
        neb$controller = (ChunkCacheController) manager;
    }

    @Inject(method = "addPlayer", at = @At("HEAD"), cancellable = true)
    private void neb$restoreWatch(EntityPlayerMP player, CallbackInfo ci) {
        if (neb$controller != null && neb$controller.neb$restore(this, player)) {
            ci.cancel();
        }
    }

    @Inject(method = "removePlayer", at = @At("HEAD"), cancellable = true)
    private void neb$retainWatch(EntityPlayerMP player, CallbackInfo ci) {
        if (neb$controller == null) {
            return;
        }
        if (!neb$evicting && neb$controller.neb$retain(this, player)) {
            ci.cancel();
        } else {
            neb$controller.neb$forget(this, player);
        }
    }

    @Override
    public ChunkCoordIntPair neb$position() {
        return chunkLocation;
    }

    @Override
    public boolean neb$isSent(EntityPlayerMP player) {
        return loaded && playersWatchingChunk.contains(player) && player.loadedChunks.contains(chunkLocation);
    }

    @Override
    public void neb$removePlayer(EntityPlayerMP player) {
        neb$evicting = true;
        try {
            removePlayer(player);
        } finally {
            neb$evicting = false;
        }
    }
}
