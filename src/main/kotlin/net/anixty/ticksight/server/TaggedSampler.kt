package net.anixty.ticksight.server

import net.anixty.ticksight.Props
import java.util.concurrent.locks.LockSupport
import kotlin.math.roundToLong
import kotlin.random.Random

/** Records a server stack and attributes it to the active tick when available. */
class TaggedSampler(
    private val thread: Thread,
    private val serverTraces: TraceMap,
    private val scoped: Boolean = false,
    settings: ServerSettingsData = ServerSettings
) : Runnable {
    private val intervalNanos = (settings.traceInterval * NANOS_PER_MILLISECOND).roundToLong()
    private val deviationNanos = (settings.deviation * NANOS_PER_MILLISECOND).roundToLong()
        .coerceAtMost(intervalNanos - 1)

    override fun run() {
        val worker = Thread.currentThread()
        while (!worker.isInterrupted) {
            val target = Props.currentTarget.get()
            if (!scoped || target != null) {
                val stack = thread.stackTrace.asList()
                val sampledTarget = Props.currentTarget.get()
                if (target != null && target === sampledTarget) {
                    target.timing.sample(target.index, stack)
                }
                if (!scoped || target != null && target === sampledTarget) {
                    serverTraces.add(stack.asReversed().iterator())
                }
            }

            val jitter = if (deviationNanos == 0L) 0L else Random.nextLong(-deviationNanos, deviationNanos + 1)
            var remaining = intervalNanos + jitter
            val deadline = System.nanoTime() + remaining
            while (remaining > 0 && !worker.isInterrupted) {
                LockSupport.parkNanos(this, remaining)
                // A park may return early. Wait out the remaining time unless interrupted.
                remaining = deadline - System.nanoTime()
            }
        }
    }
}
