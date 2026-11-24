package net.anixty.ticksight.mixin;

//? if >=26.1 {
/*import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Set;

@Pseudo
@Mixin(targets = "net.neoforged.neoforge.common.world.chunk.ForcedChunkManager$TicketTracker", remap = false)
public interface NeoForgeTicketTrackerAccessor {
    @Accessor(value = "sourcesLoading", remap = false) Long2ObjectMap<Set<?>> ticksight$getLoadingSources();
    @Accessor(value = "sourcesLoadingNaturalSpawning", remap = false) Long2ObjectMap<Set<?>> ticksight$getNaturalSpawningSources();
}*/
//?} else {
public interface NeoForgeTicketTrackerAccessor {}
//?}
