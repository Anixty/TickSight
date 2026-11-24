package net.anixty.ticksight.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Accessor("updatingChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> ticksight$getUpdatingChunks();
    @Accessor("visibleChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> ticksight$getVisibleChunks();
    @Accessor("pendingUnloads")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> ticksight$getPendingUnloads();
//? if <=1.20.1 {
/*    @Accessor("viewDistance") int ticksight$getViewDistance();*/
//?} else {
    @Invoker("isChunkTracked") boolean ticksight$isChunkTracked(ServerPlayer player, int x, int z);
//?}
}
