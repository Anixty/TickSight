package net.anixty.ticksight.server

import kotlinx.serialization.Serializable

/** Index zero is report tick 1. A negative offset marks a tick whose start was not observed. */
@Serializable
data class TickTimeline(val epochMillis: Long, val offsetsMillis: LongArray)

/** Owned by one recording; the server thread records only actual server tick start hooks. */
internal class TickStartRecorder {
    private var originNanos = 0L
    private var epochMillis = 0L
    private var hasOrigin = false
    private var offsets = LongArray(32) { -1L }
    private var pending = false
    private var pendingWallMillis = 0L
    private var pendingNanos = 0L
    private var frozen = false

    val hasPendingStart: Boolean get() = pending

    fun beginTick(wallMillis: Long, monotonicNanos: Long) {
        if (frozen) return
        pendingWallMillis = wallMillis
        pendingNanos = monotonicNanos
        pending = true
    }

    fun bind(tick: Int) {
        if (!pending || frozen) return
        record(tick, pendingWallMillis, pendingNanos)
        pending = false
    }

    fun freeze() {
        pending = false
        frozen = true
    }

    fun record(tick: Int, wallMillis: Long, monotonicNanos: Long) {
        if (frozen || tick <= 0) return
        if (!hasOrigin) {
            epochMillis = wallMillis
            originNanos = monotonicNanos
            hasOrigin = true
        }
        val index = tick - 1
        if (index >= offsets.size) {
            val oldSize = offsets.size
            offsets = offsets.copyOf(maxOf(tick, oldSize * 2))
            offsets.fill(-1L, oldSize)
        }
        // Preserve the first hook for a tick; elapsed time is independent of wall-clock adjustments.
        if (offsets[index] < 0) offsets[index] = ((monotonicNanos - originNanos) / 1_000_000L).coerceAtLeast(0L)
    }

    fun snapshot(ticks: Int): TickTimeline? {
        if (!hasOrigin) return null
        return TickTimeline(epochMillis, LongArray(ticks.coerceAtLeast(0)) { index ->
            if (index < offsets.size) offsets[index] else -1L
        })
    }
}
