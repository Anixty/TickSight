package net.anixty.ticksight.server

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.TickingBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import net.anixty.ticksight.TickSight
import net.anixty.ticksight.Props
import net.anixty.ticksight.net.S2CPacket
import net.anixty.ticksight.util.webLink
import java.util.*
import java.nio.file.Path
import kotlin.concurrent.schedule
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

inline val StackTraceElement.classMethod
    get() = "${this.className} + ${this.methodName}"

internal data class ProfileDuration(val amount: Int, val inTicks: Boolean = false) {
    val estimatedMillis: Long
        get() = amount.toLong() * if (inTicks) 50L else 1000L
}

class Profiler {
    class TimingData(
        var time: Long,
        var ticks: Int,
        var traces: TraceMap,
        var name: String = ""
    ) {
        var callTargetId: Int? = null
        var peak: Long = 0
            private set
        private var firstTick = 0
        private var lastTick = 0
        private var size = 0
        private var times = LongArray(1)
        private var sparseTicks: IntArray? = null
        private var callsThisTick = 0
        private var repeatedCalls: MutableMap<Int, Int>? = null
        private var sampledStacks: MutableMap<Int, TraceMap>? = null

        fun forTick(tick: Int): Int {
            if (size > 0 && tick == lastTick) return size - 1
            if (size == times.size) {
                times = times.copyOf(size * 2)
                sparseTicks = sparseTicks?.copyOf(times.size)
            }
            if (size == 0) {
                firstTick = tick
            } else if (sparseTicks == null && tick != lastTick + 1) {
                sparseTicks = IntArray(times.size) { firstTick + it }
            }
            sparseTicks?.set(size, tick)
            lastTick = tick
            callsThisTick = 0
            return size++
        }

        fun record(index: Int, duration: Long) {
            time += duration
            ticks++
            times[index] += duration
            callsThisTick++
            if (callsThisTick > 1) {
                val calls = repeatedCalls ?: HashMap<Int, Int>().also { repeatedCalls = it }
                calls[index] = callsThisTick
            }
            peak = maxOf(peak, times[index])
        }

        // The sampler owns these maps until it stops and the report reads them.
        fun sample(index: Int, stack: List<StackTraceElement>) {
            traces.add(stack)
            val stacks = sampledStacks ?: HashMap<Int, TraceMap>().also { sampledStacks = it }
            val trace = stacks[index] ?: TraceMap()
            trace.add(stack)
            if (trace.count > 0) stacks[index] = trace
        }

        fun tickHistory(): ProfilingData.TickHistory = ProfilingData.TickHistory(
            firstTick,
            times.copyOf(size),
            sparseTicks?.copyOf(size),
            repeatedCalls.orEmpty(),
            sampledStacks.orEmpty().mapValues { (_, trace) -> ProfilingData.SerializedTraceMap.create(trace) }
        )
    }

    data class BlockTarget(val position: BlockPos, val source: Any)

    interface TickScope
    class SampleTarget(val timing: TimingData, val index: Int) : TickScope
    private object CallScope : TickScope

    private enum class Phase { QUEUED, COLLECTING, EXPORTING }

    private class ProfileRun(
        val server: MinecraftServer,
        val owner: ProfileOwner,
        val duration: ProfileDuration,
        val sample: Boolean,
        val peakMode: Boolean,
        val calls: CallProfile?,
        val area: ProfileArea?,
        val target: ProfileTarget,
        val layers: RecordingLayers
    ) {
        val recipientId = owner.playerId
        var startingTick = server.tickCount
        var firstHistoryTick: Int? = null
        var startedAt: Long = System.currentTimeMillis()
        val entities = HashMap<Entity, TimingData>()
        val blocks = HashMap<ResourceKey<Level>, HashMap<BlockTarget, TimingData>>()
        var nextCallTargetId = 1
        val serverTraces = TraceMap()
        val tickStarts = TickStartRecorder()
        val chunkTelemetry = layers.read(layers.any) { ChunkTelemetryCollector(area, target, layers) }
        var timer: Timer? = null
        var sampler: Thread? = null
        var phase = Phase.QUEUED
        var finishRequested = false
        var finishReason: String? = null
        var savedReport: Path? = null
        var diagnostics: JsonObject? = null
    }

    // Changed only on the server thread. The report worker receives a stopped run.
    private var run: ProfileRun? = null
    internal val isBusy: Boolean get() = run != null

    private fun reportTick(current: ProfileRun): Int {
        val tick = current.server.tickCount
        // Preserve a partial starting tick if it contained any measured work.
        val first = current.firstHistoryTick ?: (
            if (tick == current.startingTick) tick else current.startingTick + 1
        ).also { current.firstHistoryTick = it }
        return (tick - first + 1).coerceAtLeast(1)
    }

    fun forTick(data: TimingData): Int {
        val current = checkNotNull(run)
        val tick = reportTick(current)
        current.tickStarts.bind(tick)
        return data.forTick(tick)
    }

    fun beginTick(data: TimingData, index: Int): TickScope? {
        val current = checkNotNull(run)
        val calls = current.calls
        if (calls != null) {
            val targetId = data.callTargetId ?: (current.nextCallTargetId++).also { data.callTargetId = it }
            return if (calls.beginTarget(reportTick(current), targetId)) CallScope else null
        }
        if (current.sampler == null) return null
        return Props.currentTarget.getAndSet(SampleTarget(data, index))
    }

    fun endTick(scope: TickScope?) {
        val current = checkNotNull(run)
        if (scope === CallScope) {
            checkNotNull(current.calls).endTarget()
        } else if (current.sampler != null) {
            // A fresh identity lets the sampler detect intervening nested calls.
            val previous = scope as SampleTarget?
            Props.currentTarget.set(previous?.let { SampleTarget(it.timing, it.index) })
        }
    }

    fun includes(entity: Entity): Boolean {
        val current = checkNotNull(run)
        val area = current.area
        if (area != null && (area.dimension != entity.level().dimension() ||
                !area.contains(entity.x, entity.y, entity.z))) return false
        if (current.target !== ProfileTarget.AllObjects &&
            (current.target as? ProfileTarget.EntityTarget)?.entity !== entity) return false
        return current.calls?.canRecord() ?: true
    }

    fun includes(level: Level, pos: BlockPos): Boolean {
        val current = checkNotNull(run)
        val area = current.area
        if (area != null && (area.dimension != level.dimension() ||
                !area.contains(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5))) return false
        if (current.target !== ProfileTarget.AllObjects) {
            val target = current.target as? ProfileTarget.Block ?: return false
            if (target.dimension != level.dimension() || target.pos != pos) return false
        }
        return current.calls?.canRecord() ?: true
    }

    fun process(entity: Entity) =
        checkNotNull(run).entities.getOrPut(entity) { TimingData(0, 0, TraceMap(entity::class)) }

    fun processBlockEntity(blockEntity: TickingBlockEntity, level: Level) =
        checkNotNull(run).blocks
            .getOrPut(level.dimension()) { HashMap() }
            .getOrPut(BlockTarget(blockEntity.pos.immutable(), blockEntity)) {
                TimingData(
                    0,
                    0,
                    TraceMap(blockEntity::class),
                    blockEntity.type
                )
            }

    fun processBlock(blockState: BlockState, pos: BlockPos, level: Level) =
        checkNotNull(run).blocks
            .getOrPut(level.dimension()) { HashMap() }
            .getOrPut(BlockTarget(pos.immutable(), blockState.block)) {
                TimingData(
                    0,
                    0,
                    TraceMap(blockState::class),
                    BuiltInRegistries.BLOCK.getKey(blockState.block).toString()
                )
            }

    fun processFluid(fluidState: FluidState, pos: BlockPos, level: Level) =
        checkNotNull(run).blocks
            .getOrPut(level.dimension()) { HashMap() }
            .getOrPut(BlockTarget(pos.immutable(), fluidState.type)) {
                TimingData(
                    0,
                    0,
                    TraceMap(fluidState::class),
                    BuiltInRegistries.FLUID.getKey(fluidState.type).toString()
                )
            }

    @JvmOverloads
    fun runWithDuration(
        server: MinecraftServer,
        player: ServerPlayer,
        duration: Int,
        sample: Boolean,
        peak: Boolean = false
    ): Boolean = runWithDuration(server, ProfileOwner.player(player), ProfileDuration(duration), sample, peak)

    internal fun runWithDuration(
        server: MinecraftServer,
        owner: ProfileOwner,
        duration: ProfileDuration,
        sample: Boolean,
        peak: Boolean,
        calls: CallProfile? = null,
        area: ProfileArea? = null,
        target: ProfileTarget = ProfileTarget.AllObjects,
        layers: RecordingLayers = RecordingLayers()
    ): Boolean {
        if (run != null || duration.amount <= 0) return false
        val current = ProfileRun(server, owner, duration, sample, peak, calls, area, target, layers)
        run = current
        // Start between ticks when we need the entire tick or enforce its budget.
        if (!duration.inTicks && calls == null) beginCollection(current)
        return true
    }

    private fun beginCollection(current: ProfileRun) {
        val server = current.server
        current.startingTick = server.tickCount
        current.startedAt = System.currentTimeMillis()
        current.diagnostics = captureRunDiagnostics(server, current.duration, current.sample, current.peakMode,
            current.calls, current.area, current.target, layers = current.layers)
        if (current.duration.inTicks || current.calls != null) current.firstHistoryTick = current.startingTick + 1
        current.phase = Phase.COLLECTING
        current.calls?.start()
        ProfilingGate.setRunning(true)

        if (current.sample) {
            val sampler = TaggedSampler(Thread.currentThread(), current.serverTraces,
                current.area != null || current.target !== ProfileTarget.AllObjects)
            current.sampler = thread(isDaemon = true, name = "TickSight sampler", block = sampler::run)
        }

        val durMs = current.duration.estimatedMillis
        if (!current.duration.inTicks) {
            current.timer = Timer("TickSight profiler", true).apply {
                schedule(durMs) { server.execute { requestFinish(current, "duration") } }
            }
        }

        // The client countdown uses the nominal tick rate for tick-based runs.
        TickSight.CHANNEL.sendToPlayers(
            current.recipients(),
            S2CPacket.ProfilingStarted(current.startedAt + durMs)
        )
    }

    internal fun stop(): Boolean = run?.let { requestFinish(it, "stopped") } ?: false

    private fun requestFinish(current: ProfileRun, reason: String): Boolean {
        if (run !== current || current.phase == Phase.EXPORTING || current.finishRequested) return false
        current.finishRequested = true
        current.finishReason = reason
        current.timer?.cancel()
        return true
    }

    internal fun beforeTick(server: MinecraftServer) {
        val current = run ?: return
        if (current.server === server && current.phase == Phase.COLLECTING) {
            // Forge PRE can precede tickCount++, while Fabric/NeoForge PRE follows it.
            // Capture the clock here; an object hook or POST binds the final game tick number.
            current.tickStarts.beginTick(System.currentTimeMillis(), System.nanoTime())
            // Calls always start between ticks, so each PRE begins the next recording window.
            current.calls?.beginServerTick()
        }
    }

    internal fun afterTick(server: MinecraftServer) {
        val current = run ?: return
        if (current.server !== server || current.phase == Phase.EXPORTING) return
        if (current.phase == Phase.COLLECTING && current.tickStarts.hasPendingStart) {
            // Preserve starts even when no selected object ticked in this server tick.
            current.tickStarts.bind(reportTick(current))
        }
        if (current.phase == Phase.COLLECTING) current.chunkTelemetry?.capture(server, reportTick(current))
        if (current.phase == Phase.COLLECTING && current.calls?.endServerTick(reportTick(current)) == true) {
            requestFinish(current, "limit")
        }
        val tickLimitReached = current.phase == Phase.COLLECTING && current.duration.inTicks &&
            server.tickCount - current.startingTick >= current.duration.amount
        // Wait for tick hooks to finish recording before the report worker reads the data.
        if (current.finishRequested || tickLimitReached) {
            if (tickLimitReached && current.finishReason == null) current.finishReason = "duration"
            finishCollection(current)
        } else if (current.phase == Phase.QUEUED) {
            beginCollection(current)
        }
    }

    fun cancel() {
        val current = run ?: return
        stopCollection(current)
        run = null
    }

    private fun uploadProfile(file: Path): String? {
        if (ServerSettings.uploadURL.isEmpty()) {
            TickSight.LOGGER.info("uploadURL not set, skipping upload")
            return null
        }

        TickSight.LOGGER.info("Attempting to upload profile")
        return try {
            val profileURL = ProfileUpload.upload(ServerSettings.uploadURL, file)
            TickSight.LOGGER.info("Profile uploaded to $profileURL")

            profileURL
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun stopCollection(current: ProfileRun) {
        current.tickStarts.freeze()
        if (current.phase == Phase.COLLECTING) {
            ProfilingGate.setRunning(false)
            current.calls?.stop()
            Props.currentTarget.set(null)
            current.sampler?.interrupt()
        }
        current.timer?.cancel()
        current.phase = Phase.EXPORTING
    }

    private fun finishCollection(current: ProfileRun) {
        val server = current.server
        val collected = current.phase == Phase.COLLECTING
        val ticks = if (collected) server.tickCount - current.startingTick else 0
        val timelineTicks = when {
            current.duration.inTicks || current.calls != null -> ticks
            current.firstHistoryTick == current.startingTick -> ticks + 1
            else -> ticks
        }
        val duration = if (collected) System.currentTimeMillis() - current.startedAt else 0L
        val initialSnapshot = current.diagnostics ?: captureRunDiagnostics(server, current.duration, current.sample,
            current.peakMode, current.calls, current.area, current.target, started = false, layers = current.layers)
        val snapshot = buildJsonObject {
            initialSnapshot.forEach { (key, value) -> put(key, value) }
            put("recording", buildJsonObject {
                initialSnapshot["recording"]?.let { (it as JsonObject).forEach { (key, value) -> put(key, value) } }
                put("stopReason", current.calls?.cutoff?.reason ?: current.finishReason ?: "stopped")
                put("completed", collected && current.finishReason == "duration" && current.calls?.cutoff == null)
            })
        }
        stopCollection(current)
        TickSight.CHANNEL.sendToPlayers(current.recipients(), S2CPacket.ProfilingCompleted)
        val calls = current.calls
        calls?.cutoff?.let { cutoff ->
            val limitMessage = when (cutoff.reason) {
                "hard_tick_time" -> serverText("text.ticksight.call_trace_hard_limit", cutoff.tick, calls.hardLimitMs)
                "memory" -> serverText("text.ticksight.call_trace_memory_limit", cutoff.tick, calls.memoryLimitMiB)
                "heap" -> serverText("text.ticksight.call_trace_heap_limit", cutoff.tick, calls.heapReserveMiB)
                "report_size" -> serverText("text.ticksight.call_trace_report_limit", cutoff.tick)
                else -> serverText(
                    "text.ticksight.call_trace_limit", cutoff.tick,
                    "%.3f".format(Locale.ROOT, cutoff.elapsedNanos / 1_000_000.0), calls.limitMs
                )
            }
            TickSight.LOGGER.error(limitMessage.string)
            val error = limitMessage.copy().withStyle(ChatFormatting.RED)
            current.recipients().forEach { it.sendSystemMessage(error) }
        }
        thread(isDaemon = true, name = "TickSight report") {
            var result: S2CPacket.ProfilingResult? = null
            try {
                current.sampler?.join()
                val data = ProfilingData.create(
                    current.entities, current.blocks, ticks, current.serverTraces, current.peakMode, timelineTicks,
                    calls?.report(), current.tickStarts.snapshot(timelineTicks), current.chunkTelemetry?.snapshot()
                )
                current.entities.clear()
                current.blocks.clear()
                current.serverTraces.children.clear()
                val diagnostics = getDiagnostics(current.owner, current.startedAt, duration, snapshot)
                TickSight.LOGGER.info("Profiler ran for $ticks ticks, sending data")
                val file = ProfileArchive.write(data, diagnostics)
                current.savedReport = file
                val link = uploadProfile(file)
                if (link != null && calls?.cutoff == null) {
                    if (ProfileArchive.remove(file)) current.savedReport = null
                    else ProfileArchive.prune()
                } else {
                    ProfileArchive.prune()
                    TickSight.LOGGER.info("Profile saved locally to $file")
                }
                val clientData = if (calls != null || current.recipientId != null && link != null) data.forOverlay() else data
                val clientDiagnostics = buildJsonObject {
                    diagnostics.forEach { (key, value) -> put(key, value) }
                    put("content", if (clientData === data) "full" else "overlay")
                }
                result = S2CPacket.ProfilingResult(clientData, link, current.owner, clientDiagnostics.toString())
            } catch (error: Exception) {
                TickSight.LOGGER.error("Could not prepare the TickSight report", error)
            } finally {
                server.execute {
                    if (run !== current) return@execute
                    run = null
                    sendReport(current, result)
                    if (result != null) TickSight.LOGGER.info("Data transfer complete!")
                }
            }
        }
    }

    private fun ProfileRun.recipients() = server.playerList.players.filter {
        it.uuid == recipientId || TickSight.isOperator(server, it)
    }

    private fun sendReport(current: ProfileRun, report: S2CPacket.ProfilingResult?) {
        val message = report?.link?.let { serverText("text.ticksight.profile_uploaded", webLink(it)) }
            ?: current.savedReport?.let { serverText("text.ticksight.profile_saved", it.toString()) }
            ?: serverText("text.ticksight.profile_no_link")
        val recipients = current.recipients()
        for (recipient in recipients) {
            val isInitiator = recipient.uuid == current.recipientId
            val sentToClient = isInitiator && report != null &&
                TickSight.CHANNEL.sendToPlayersSplit(listOf(recipient), report)
            if (!sentToClient || report?.link == null && current.savedReport != null) recipient.sendSystemMessage(message)
        }
        TickSight.CHANNEL.sendToPlayers(recipients, S2CPacket.ProfilerInactive)
    }
}
