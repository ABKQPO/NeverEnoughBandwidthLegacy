package cn.ussshenzhou.notenoughbandwidth.chunk;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.ChunkCoordIntPair;

public interface ChunkWatch {

    ChunkCoordIntPair neb$position();

    boolean neb$isSent(EntityPlayerMP player);

    void neb$removePlayer(EntityPlayerMP player);
}
