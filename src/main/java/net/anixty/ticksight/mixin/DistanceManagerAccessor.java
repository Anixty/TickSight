package net.anixty.ticksight.mixin;

import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
//? if >=26.1 {
/*import net.minecraft.world.level.TicketStorage;*/
//?} else {
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.SortedArraySet;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TickingTracker;
//?}

@Mixin(DistanceManager.class)
public interface DistanceManagerAccessor {
//? if >=26.1 {
/*    @Accessor("ticketStorage") TicketStorage ticksight$getTicketStorage();*/
//?} else {
    @Accessor("tickets") Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> ticksight$getLoadingTickets();
    @Accessor("tickingTicketsTracker") TickingTracker ticksight$getTickingTracker();
//?}
}
