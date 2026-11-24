package net.anixty.ticksight.server

import net.anixty.ticksight.agent.CallRecorder

internal class CallProfile(
    val targetLabel: String,
    val limitMs: Int,
    val maxRepeats: Int,
    val hardLimitMs: Int,
    val memoryLimitMiB: Int,
    val heapReserveMiB: Int
) {
    private val recorder = CallRecorder(
        limitMs * 1_000_000L, maxRepeats, hardLimitMs * 1_000_000L,
        memoryLimitMiB * 1_048_576L, heapReserveMiB * 1_048_576L
    )
    private val label = targetLabel
    var cutoff: CallTraceReport.Cutoff? = null
        private set

    fun start() = recorder.start()
    fun stop() = recorder.stop()
    fun beginServerTick() = recorder.beginServerTick()
    fun beginTarget(tick: Int, targetId: Int): Boolean = recorder.beginTarget(targetId, tick)
    fun endTarget() = recorder.endTarget()
    fun canRecord(): Boolean = !recorder.hasExceededLimit()

    fun endServerTick(tick: Int): Boolean {
        val elapsed = recorder.endServerTick()
        val stop = recorder.stopInformation() ?: return false
        cutoff = CallTraceReport.Cutoff(
            tick, elapsed, stop.reason(), stop.elapsedNanos(), stop.limit(),
            stop.reservedBytes(), stop.heapAvailableBytes()
        )
        return true
    }

    fun report(): CallTraceReport {
        val result = recorder.result()
        val targets = result.targets().mapValues { (_, ticks) ->
            ticks.associate { tick ->
                tick.number() to object : AbstractList<CallTraceReport.Call>() {
                    override val size: Int get() = tick.calls().size
                    override fun get(index: Int): CallTraceReport.Call {
                        val call = tick.calls()[index]
                        return CallTraceReport.Call(
                            call.parent(), call.method(), call.count(), call.elapsedNanos(),
                            call.untracedCalls(), call.untracedNanos()
                        )
                    }
                }
            }
        }
        return CallTraceReport(
            target = label,
            limitMs = limitMs,
            methods = result.methods().map { CallTraceReport.Method(it.className(), it.name(), it.descriptor()) },
            cutoff = cutoff,
            skippedClasses = result.skippedClasses(),
            skippedMethods = result.skippedMethods(),
            maxRepeats = maxRepeats,
            objectTicks = targets,
            hardLimitMs = hardLimitMs,
            memoryLimitMiB = memoryLimitMiB,
            heapReserveMiB = heapReserveMiB,
            reservedBytes = recorder.reservedBytes()
        )
    }

    companion object {
        fun isAvailable(): Boolean = try {
            CallRecorder.isInstalled() && CallRecorder.apiVersion() == 4
        } catch (_: LinkageError) {
            false
        }
    }
}
