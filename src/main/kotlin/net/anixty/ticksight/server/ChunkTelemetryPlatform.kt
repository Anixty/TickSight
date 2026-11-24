package net.anixty.ticksight.server

import net.minecraft.server.level.DistanceManager
import net.minecraft.server.level.ServerLevel
import java.util.function.BiPredicate
import java.util.function.Function
import java.util.function.LongPredicate
import java.util.function.LongConsumer

/** Loader-owned APIs are installed at initialization; queried only during an active snapshot. */
object ChunkTelemetryPlatform {
    @JvmField var ticketForceTicks: Function<Any, Boolean>? = null
    @JvmField var chunkForceTicks: BiPredicate<DistanceManager, Long>? = null
    @JvmField var managedSources: ManagedSourceProvider? = null
}

fun interface ManagedSourceProvider {
    fun snapshot(level: ServerLevel, accepted: LongPredicate, allocated: LongConsumer): Map<Long, List<NativeManagedSource>>
}

/** Temporary server-thread values from a loader-owned active owner tracker, not report objects. */
data class NativeManagedSource(val mode: String, val modId: String, val key: Any)

