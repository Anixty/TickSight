package net.anixty.ticksight.net

import kotlinx.serialization.Serializable
import net.anixty.ticksight.server.ProfileOwner
import net.anixty.ticksight.server.ProfilingData

sealed interface S2CPacket {
    @Serializable data class ProfilingStarted(val endMillis: Long) : S2CPacket

    @Serializable object ProfilingCompleted : S2CPacket

    @Serializable object ProfilerInactive : S2CPacket

    // JSON text keeps diagnostics compatible with the protobuf packet serializer.
    @Serializable data class ProfilingResult(
        val data: ProfilingData, val link: String?, val owner: ProfileOwner, val diagnosticsJson: String? = null
    ) : S2CPacket

    @Serializable
    enum class Availability : S2CPacket {
        Available,
        NoPermissions
    }

    @Serializable class ConsiderProfiling(val tps: Double)
}
