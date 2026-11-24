package net.anixty.ticksight.neoforge;

import net.anixty.ticksight.server.ChunkTelemetryPlatform;
//? if >=26.1 {
/*import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.anixty.ticksight.mixin.DistanceManagerAccessor;
import net.anixty.ticksight.mixin.NeoForgeTicketOwnerAccessor;
import net.anixty.ticksight.mixin.NeoForgeTicketTrackerAccessor;
import net.anixty.ticksight.server.NativeManagedSource;
import net.minecraft.world.level.TicketStorage;
import java.util.*;
import java.util.function.LongPredicate;*/
//?}

final class NeoForgeTelemetry {
    static void init() {
//? if >=26.1 {
/*        ChunkTelemetryPlatform.managedSources = (level, accepted, allocated) -> {
            TicketStorage storage = ((DistanceManagerAccessor) level.getChunkSource().chunkMap.getDistanceManager())
                .ticksight$getTicketStorage();
            Map<Long, List<NativeManagedSource>> result = new HashMap<>();
            for (Object tracker : List.of(storage.getBlockForcedChunks(), storage.getEntityForcedChunks())) {
                NeoForgeTicketTrackerAccessor access = (NeoForgeTicketTrackerAccessor) tracker;
                append(result, access.ticksight$getLoadingSources(), "loading", accepted, allocated);
                append(result, access.ticksight$getNaturalSpawningSources(), "loading_natural_spawning", accepted, allocated);
            }
            return result;
        };*/
//?} else {
        ChunkTelemetryPlatform.ticketForceTicks = ticket -> ((net.minecraft.server.level.Ticket<?>) ticket).isForceTicks();
        ChunkTelemetryPlatform.chunkForceTicks = (manager, position) -> manager.shouldForceTicks(position);
//?}
    }
//? if >=26.1 {
/*    private static void append(Map<Long, List<NativeManagedSource>> result, Long2ObjectMap<Set<?>> sources,
                               String mode, LongPredicate accepted, java.util.function.LongConsumer allocated) {
        // Only active maps. Deactivated/persisted owners do not imply a current ticket.
        for (Long2ObjectMap.Entry<Set<?>> entry : sources.long2ObjectEntrySet()) {
            if (!accepted.test(entry.getLongKey())) continue;
            for (Object value : entry.getValue()) {
                NeoForgeTicketOwnerAccessor owner = (NeoForgeTicketOwnerAccessor) value;
                String modId = owner.ticksight$getModId().toString();
                // Conservative estimate for the raw record and its map/list slots before allocation.
                allocated.accept(192L + 2L * modId.length());
                result.computeIfAbsent(entry.getLongKey(), key -> new ArrayList<>())
                    .add(new NativeManagedSource(mode, modId, owner.ticksight$getOwner()));
            }
        }
    }*/
//?}
}
