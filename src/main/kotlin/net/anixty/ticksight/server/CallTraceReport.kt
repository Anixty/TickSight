package net.anixty.ticksight.server

import kotlinx.serialization.Serializable

@Serializable
data class CallTraceReport(
    val target: String,
    val limitMs: Int,
    val methods: List<Method>,
    val objectTicks: Map<Int, Map<Int, List<Call>>>,
    val cutoff: Cutoff? = null,
    val skippedClasses: Int = 0,
    val skippedMethods: Int = 0,
    val maxRepeats: Int = -1,
    val hardLimitMs: Int? = null,
    val memoryLimitMiB: Int? = null,
    val heapReserveMiB: Int? = null,
    val reservedBytes: Long? = null
) {
    @Serializable
    data class Method(val className: String, val name: String, val descriptor: String)

    // Parent and method are indices. Both are -1 on the selected object's root.
    @Serializable
    data class Call(
        val parent: Int,
        val method: Int,
        val count: Long,
        val elapsedNanos: Long? = null,
        val untracedCalls: Long = 0,
        val untracedNanos: Long = 0
    )

    @Serializable
    data class Cutoff(
        val tick: Int,
        val elapsedNanos: Long,
        val reason: String = "tick_time",
        val recordingElapsedNanos: Long? = null,
        val limit: Long? = null,
        val reservedBytes: Long? = null,
        val heapAvailableBytes: Long? = null
    )
}
