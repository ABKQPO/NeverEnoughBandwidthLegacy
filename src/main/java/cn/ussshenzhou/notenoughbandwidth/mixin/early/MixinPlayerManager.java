package cn.ussshenzhou.notenoughbandwidth.mixin.early;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.util.MathHelper;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import cn.ussshenzhou.notenoughbandwidth.chunk.ChunkCacheController;
import cn.ussshenzhou.notenoughbandwidth.chunk.ChunkWatch;
import cn.ussshenzhou.notenoughbandwidth.chunk.DelayedChunkCache;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;

@Mixin(PlayerManager.class)
public abstract class MixinPlayerManager implements ChunkCacheController {

    @Shadow
    private int playerViewRadius;

    @Shadow
    public abstract WorldServer getWorldServer();

    @Unique
    private final Map<EntityPlayerMP, DelayedChunkCache<ChunkWatch>> neb$chunkCaches = new IdentityHashMap<>();

    @Unique
    private EntityPlayerMP neb$movingPlayer;

    @Inject(method = "updatePlayerPertinentChunks", at = @At("HEAD"))
    private void neb$beginMovement(EntityPlayerMP player, CallbackInfo ci) {
        neb$movingPlayer = player;
    }

    @Inject(method = "updatePlayerPertinentChunks", at = @At("RETURN"))
    private void neb$endMovement(EntityPlayerMP player, CallbackInfo ci) {
        neb$movingPlayer = null;
    }

    @Override
    public boolean neb$retain(ChunkWatch watch, EntityPlayerMP player) {
        if (neb$movingPlayer != player || !NebConfig.delayedChunkCache
            || NebConfig.delayedChunkCacheDistance <= 0
            || NebConfig.delayedChunkCacheSize <= 0
            || !watch.neb$isSent(player)) {
            return false;
        }
        ChunkCoordIntPair pos = watch.neb$position();
        if (!getWorldServer().getChunkProvider()
            .chunkExists(pos.chunkXPos, pos.chunkZPos)) {
            return false;
        }
        DelayedChunkCache<ChunkWatch> cache = neb$chunkCaches
            .computeIfAbsent(player, ignored -> new DelayedChunkCache<>());
        return cache.retain(
            pos.chunkXPos,
            pos.chunkZPos,
            watch,
            MathHelper.floor_double(player.posX) >> 4,
            MathHelper.floor_double(player.posZ) >> 4,
            playerViewRadius + NebConfig.delayedChunkCacheDistance,
            NebConfig.delayedChunkCacheSize,
            System.nanoTime(),
            TimeUnit.SECONDS.toNanos(NebConfig.delayedChunkCacheSeconds),
            entry -> entry.neb$removePlayer(player));
    }

    @Override
    public boolean neb$restore(ChunkWatch watch, EntityPlayerMP player) {
        DelayedChunkCache<ChunkWatch> cache = neb$chunkCaches.get(player);
        ChunkCoordIntPair pos = watch.neb$position();
        if (neb$movingPlayer != player || cache == null || cache.restore(pos.chunkXPos, pos.chunkZPos) == null) {
            return false;
        }
        if (!getWorldServer().getChunkProvider()
            .chunkExists(pos.chunkXPos, pos.chunkZPos)) {
            return false;
        }
        if (!player.loadedChunks.contains(pos)) {
            player.loadedChunks.add(pos);
        }
        getWorldServer().getEntityTracker()
            .func_85172_a(player, getWorldServer().getChunkFromChunkCoords(pos.chunkXPos, pos.chunkZPos));
        return true;
    }

    @Override
    public void neb$forget(ChunkWatch watch, EntityPlayerMP player) {
        DelayedChunkCache<ChunkWatch> cache = neb$chunkCaches.get(player);
        if (cache != null) {
            ChunkCoordIntPair pos = watch.neb$position();
            cache.restore(pos.chunkXPos, pos.chunkZPos);
        }
    }

    @Inject(method = "isPlayerWatchingChunk", at = @At("HEAD"), cancellable = true)
    private void neb$excludeRetainedEntities(EntityPlayerMP player, int x, int z,
        CallbackInfoReturnable<Boolean> result) {
        DelayedChunkCache<ChunkWatch> cache = neb$chunkCaches.get(player);
        if (cache != null && cache.contains(x, z)) {
            result.setReturnValue(false);
        }
    }

    @Inject(method = "updatePlayerInstances", at = @At("HEAD"))
    private void neb$expireRetainedChunks(CallbackInfo ci) {
        neb$movingPlayer = null;
        long now = System.nanoTime();
        long timeout = TimeUnit.SECONDS.toNanos(NebConfig.delayedChunkCacheSeconds);
        Iterator<Map.Entry<EntityPlayerMP, DelayedChunkCache<ChunkWatch>>> iterator = neb$chunkCaches.entrySet()
            .iterator();
        while (iterator.hasNext()) {
            Map.Entry<EntityPlayerMP, DelayedChunkCache<ChunkWatch>> entry = iterator.next();
            EntityPlayerMP player = entry.getKey();
            DelayedChunkCache<ChunkWatch> cache = entry.getValue();
            cache.expire(
                MathHelper.floor_double(player.posX) >> 4,
                MathHelper.floor_double(player.posZ) >> 4,
                playerViewRadius + NebConfig.delayedChunkCacheDistance,
                NebConfig.delayedChunkCache ? NebConfig.delayedChunkCacheSize : 0,
                now,
                timeout,
                watch -> watch.neb$removePlayer(player));
            if (cache.isEmpty()) {
                iterator.remove();
            }
        }
    }

    @Inject(method = "removePlayer", at = @At("HEAD"))
    private void neb$clearLeavingPlayer(EntityPlayerMP player, CallbackInfo ci) {
        DelayedChunkCache<ChunkWatch> cache = neb$chunkCaches.remove(player);
        if (cache != null) {
            cache.clear(watch -> watch.neb$removePlayer(player));
        }
    }

    @Inject(method = "func_152622_a", at = @At("HEAD"))
    private void neb$clearChangingRadius(int radius, CallbackInfo ci) {
        if (radius != playerViewRadius && neb$chunkCaches != null) {
            for (Map.Entry<EntityPlayerMP, DelayedChunkCache<ChunkWatch>> entry : neb$chunkCaches.entrySet()) {
                EntityPlayerMP player = entry.getKey();
                entry.getValue()
                    .clear(watch -> watch.neb$removePlayer(player));
            }
            neb$chunkCaches.clear();
        }
    }
}
