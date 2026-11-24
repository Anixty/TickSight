package net.anixty.ticksight.mixin;

//? if <26.1 {
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TickingTracker;
import net.minecraft.util.SortedArraySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TickingTracker.class)
public interface TickingTrackerAccessor {
    @Accessor("tickets") Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> ticksight$getTickets();
}
//?} else {
/*public interface TickingTrackerAccessor {}*/
//?}
