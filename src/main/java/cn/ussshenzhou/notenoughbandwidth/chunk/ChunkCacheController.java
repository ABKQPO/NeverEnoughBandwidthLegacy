package cn.ussshenzhou.notenoughbandwidth.chunk;

import net.minecraft.entity.player.EntityPlayerMP;

public interface ChunkCacheController {

    boolean neb$retain(ChunkWatch watch, EntityPlayerMP player);

    boolean neb$restore(ChunkWatch watch, EntityPlayerMP player);

    void neb$forget(ChunkWatch watch, EntityPlayerMP player);
}
