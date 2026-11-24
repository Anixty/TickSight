package net.anixty.ticksight.server

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.anixty.ticksight.mixin.*
import net.anixty.ticksight.util.locationId
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ChunkHolder
import net.minecraft.server.level.ChunkLevel
import net.minecraft.server.level.ChunkMap
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.Ticket
//? if >1.20.1 {
import net.minecraft.server.level.ChunkTrackingView
//?}
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.ceil

/** Reads existing holders/futures/tickets, never a chunk request, task, future join or force-load. */
internal class ChunkTelemetryCollector(
    private val area: ProfileArea?, private val target: ProfileTarget, private val layers: RecordingLayers = RecordingLayers()
) {
    private val initialScope = currentScope()
    // Leave three quarters of the existing archive limit for object histories/call trees.
    // The other bound is the existing configured recording memory budget; neither is a type/count filter.
    private val timeline = TelemetryTimeline(initialScope, minOf(
        ServerSettings.callTraceMemoryLimitMiB * 1_048_576L, MAX_PROFILE_BYTES / 4), layers.ticketSources, layers.visibility)
    private val tickBudgetNanos = ServerSettings.callTraceTickLimitMs * 1_000_000L
    private val heapReserve = ServerSettings.callTraceHeapReserveMiB * 1_048_576L
    private val dimensions = LinkedHashSet<String>()
    private var deadline = 0L
    private var operations = 0
    private var lastTick = 0
    private val actors = LinkedHashMap<String, TelemetryActor>()
    private fun packed(x: Int, z: Int): Long = (x.toLong() and 0xffffffffL) or (z.toLong() shl 32)
    private fun unpack(value: Long) = ChunkPos(ChunkPos.getX(value), ChunkPos.getZ(value))

    private fun checkBudget() {
        if ((++operations and 63) != 0) return
        if (System.nanoTime() - deadline >= 0) throw TelemetryLimit("tick_time")
        val runtime = Runtime.getRuntime()
        if (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory() < heapReserve) throw TelemetryLimit("heap")
    }

    fun capture(server: MinecraftServer, tick: Int) {
        // All-off is a real collection fast path: do not inspect levels, holders or online players.
        if (!layers.any) return
        if (timeline.cutoff != null || tick <= lastTick) return
        check(server.isSameThread) { "Chunk telemetry must be captured on the server thread" }
        deadline = System.nanoTime() + tickBudgetNanos
        operations = 63
        try {
            timeline.beginFrame()
            checkBudget()
            val scope = currentScope()
            val online = layers.read(layers.players || layers.visibility || layers.ticketSources) { server.playerList.players }.orEmpty()
            val players = layers.read(layers.players) {
                online.filter { scope.contains(it.level().dimension().locationId().toString(), it.x, it.z) }
            }.orEmpty()
            val knownPlayers = layers.read(layers.ticketSources) { online.associateBy { it.uuid } }.orEmpty()
            timeline.work(256L * online.size)
            val chunks = HashMap<TelemetryChunkKey, Int>()
            for (level in server.allLevels) {
                checkBudget()
                val dimension = level.dimension().locationId().toString()
                if (scope.dimension != null && scope.dimension != dimension) continue
                dimensions.add(dimension)
                if (!layers.chunks) continue
                val dimensionId = dimensions.indexOf(dimension)
                val map = level.chunkSource.chunkMap
                val access = map as ChunkMapAccessor
                val updating = access.`ticksight$getUpdatingChunks`()
                val visible = access.`ticksight$getVisibleChunks`()
                val pendingUnloads = access.`ticksight$getPendingUnloads`()
                val watchers = layers.read(layers.visibility) { watchers(map, online.filter { it.level() === level }, dimension, scope) }
                val ticketIndex = layers.read(layers.ticketSources) { tickets(level, dimension, scope, knownPlayers) }
                val managed = layers.read(layers.ticketSources) {
                    ChunkTelemetryPlatform.managedSources?.snapshot(level, { packed ->
                        checkBudget()
                        val pos = unpack(packed)
                        scope.intersectsChunk(dimension, pos.x, pos.z)
                    }, { bytes ->
                        checkBudget()
                        timeline.work(bytes)
                    }).orEmpty()
                }
                val seen = HashSet<Long>()
                for (holders in listOf(updating, visible, pendingUnloads)) for (entry in holders.long2ObjectEntrySet()) {
                    checkBudget()
                    val packed = entry.longKey
                    if (!seen.add(packed)) continue
                    val holder = entry.value
                    val pos = holder.pos
                    if (!scope.intersectsChunk(dimension, pos.x, pos.z)) continue
                    // Sources may use the effective graph internally to find propagated candidates;
                    // their own native levels remain part of each source, not the disabled map layer.
                    val loadingLevel = layers.read(layers.ticketLevels || layers.ticketSources) { holder.ticketLevel }
                    val simulationLevel = layers.read(layers.ticketLevels || layers.ticketSources) { simulationLevel(level, pos) }
                    val nativeChunk = layers.read(layers.runtime || layers.generation) { latestChunk(holder) }
                    val generation = layers.read(layers.generation) {
                    val completed = latestStatus(holder)
                    // A disk-loaded chunk can already have reached FULL before this holder's generation
                    // pipeline has completed its later futures. Both values are observed, never requested.
                    //? if <=1.20.1 {
                    /*val persisted = nativeChunk?.status*/
                    //?} else {
                    val persisted = nativeChunk?.persistedStatus
                    //?}
                    val achieved = if (completed == null || persisted != null && persisted.index > completed.index) persisted else completed
                    achieved?.let { BuiltInRegistries.CHUNK_STATUS.getKey(it)?.toString() }
                    }
                    val fullReady = layers.read(layers.runtime) { ready(holder.fullChunkFuture) }
                    val blocksReady = layers.read(layers.runtime) { ready(holder.tickingChunkFuture) }
                    val entitiesReady = layers.read(layers.runtime) { ready(holder.entityTickingChunkFuture) }
                    val forced = layers.read(layers.runtime) { ChunkTelemetryPlatform.chunkForceTicks?.test(map.distanceManager, packed) }
                    timeline.work(96)
                    chunks[TelemetryChunkKey(dimensionId, pos.x, pos.z)] = timeline.state(TelemetryChunkState(
                        layers.read(layers.runtime) { fullReady == true || nativeChunk is LevelChunk },
                        layers.read(layers.runtime) { holder.fullStatus.name }, generation,
                        layers.read(layers.generation && layers.ticketLevels) {
                            loadingLevel?.let { ChunkLevel.generationStatus(it) }?.let { BuiltInRegistries.CHUNK_STATUS.getKey(it)?.toString() }
                        },
                        loadingLevel.takeIf { layers.ticketLevels }, simulationLevel.takeIf { layers.ticketLevels },
                        fullReady, blocksReady, entitiesReady, layers.read(layers.visibility) { visible.containsKey(packed) },
                        watchers?.let { timeline.watcherSet(it.tracked[packed]?.sorted().orEmpty()) },
                        ticketIndex?.let { timeline.directTicketSet(it.direct[packed]?.sorted().orEmpty()) },
                        ticketIndex?.let { timeline.influenceSet(it.influences(pos.x, pos.z, checkNotNull(loadingLevel), checkNotNull(simulationLevel))) },
                        layers.read(layers.runtime) { blocksReady == true && (forced == true || map.distanceManager.inBlockTickingRange(packed)) },
                        layers.read(layers.runtime) { entitiesReady == true && (forced == true || map.distanceManager.inEntityTickingRange(packed)) }, forced,
                        if (managed == null) emptyList() else managed[packed].orEmpty().map { value ->
                            checkBudget()
                            val uuid = value.key as? UUID
                            val player = uuid?.let(knownPlayers::get)?.also(::rememberActor)
                            val entity = uuid?.let(level::getEntity)
                            val owner = player?.let { TelemetryTicketOwner("player", it.uuid.toString(), it.gameProfile.name) }
                                ?: entity?.let { TelemetryTicketOwner("entity", it.uuid.toString(), value.modId) }
                                ?: TelemetryTicketOwner("mod", name = value.modId)
                            TelemetryManagedSource("neoforge_forced", value.mode, value.modId,
                                value.key.toString().takeUnless { uuid != null && entity == null && player == null },
                                value.key.javaClass.name, owner)
                        }.sortedWith(compareBy({ it.modId }, { it.mode }, { it.key })), layers.read(layers.runtime) { pendingUnloads.containsKey(packed) },
                        watchers?.let { timeline.watcherSet(it.inView[packed]?.sorted().orEmpty()) },
                        //? if <=1.20.1 {
                        /*null*/
                        //?} else {
                        watchers?.let { timeline.watcherSet(it.pending[packed]?.sorted().orEmpty()) }
                        //?}
                    ))
                }
            }
            checkBudget()
            val playerStates = players.map {
                TelemetryPlayer(it.uuid.toString(), it.displayName?.string ?: it.gameProfile.name,
                    it.level().dimension().locationId().toString(), it.x, it.y, it.z)
            }
            players.forEach(::rememberActor)
            timeline.append(tick, scope, chunks, playerStates, ::checkBudget)
            lastTick = tick
        } catch (limit: TelemetryLimit) {
            timeline.stop(tick, limit.reason)
        } catch (error: Exception) {
            net.anixty.ticksight.TickSight.LOGGER.warn("Chunk telemetry stopped at tick $tick", error)
            timeline.stop(tick, "capture_error")
        }
    }

    fun snapshot(): ChunkTelemetry = timeline.snapshot(dimensions.toList(), TelemetryCapabilities(
        if (layers.chunks) "updating_visible_and_pending_unload" else "not_recorded",
        if (layers.generation) "last_completed_or_persisted" else "not_recorded",
        if (layers.ticketSources) "direct_and_effective_candidates" else "not_recorded",
        //? if >=26.1 {
        /*if (!layers.ticketSources) "not_recorded" else if (ChunkTelemetryPlatform.managedSources != null) "managed_groups_only" else "not_exposed",*/
        //?} else {
        if (layers.ticketSources) "native_key_only" else "not_recorded",
        //?}
        //? if <=1.20.1 {
        /*if (layers.visibility) "server_view_range" else "not_recorded", false, layers.ticketLevels, if (layers.visibility) "unavailable" else "not_recorded", layers*/
        //?} else {
        if (layers.visibility) "server_tracking_excluding_pending_sends" else "not_recorded", false, layers.ticketLevels,
        if (layers.visibility) "observed" else "not_recorded", layers
        //?}
    ), actors.values.toList())

    private fun rememberActor(player: ServerPlayer) {
        val uuid = player.uuid.toString()
        if (uuid !in actors) {
            val actor = TelemetryActor(uuid, player.gameProfile.name)
            timeline.actor(actor)
            actors[uuid] = actor
        }
    }

    private fun currentScope(): TelemetryScope {
        if (target is ProfileTarget.Block) return TelemetryScope("block", target.dimension.locationId().toString(),
            target.pos.x.toDouble(), target.pos.x + 1.0, target.pos.z.toDouble(), target.pos.z + 1.0)
        if (target is ProfileTarget.EntityTarget) {
            val entity = target.entity
            if (area != null && area.dimension != entity.level().dimension()) return TelemetryScope("entity",
                area.dimension.locationId().toString(), 0.0, 0.0, 0.0, 0.0)
            return TelemetryScope("entity", entity.level().dimension().locationId().toString(),
                floor(entity.x), floor(entity.x) + 1, floor(entity.z), floor(entity.z) + 1)
        }
        return when (area) {
            null -> TelemetryScope("all")
            is ProfileArea.Dimension -> TelemetryScope("dimension", area.dimension.locationId().toString())
            is ProfileArea.Box -> TelemetryScope("box", area.dimension.locationId().toString(),
                minOf(area.first.x, area.second.x).toDouble(), maxOf(area.first.x, area.second.x) + 1.0,
                minOf(area.first.z, area.second.z).toDouble(), maxOf(area.first.z, area.second.z) + 1.0)
            is ProfileArea.Chunks -> TelemetryScope("chunks", area.dimension.locationId().toString(),
                minOf(area.firstX, area.secondX) * 16.0, (maxOf(area.firstX, area.secondX) + 1.0) * 16,
                minOf(area.firstZ, area.secondZ) * 16.0, (maxOf(area.firstZ, area.secondZ) + 1.0) * 16)
            is ProfileArea.Radius -> TelemetryScope("radius", area.dimension.locationId().toString(),
                centerX = area.center.x, centerZ = area.center.z, radius = area.radius)
        }
    }

    private class Watchers {
        val tracked = Long2ObjectOpenHashMap<MutableList<String>>()
        val inView = Long2ObjectOpenHashMap<MutableList<String>>()
        val pending = Long2ObjectOpenHashMap<MutableList<String>>()
    }

    private fun watchers(map: ChunkMap, players: List<ServerPlayer>, dimension: String, scope: TelemetryScope): Watchers {
        val result = Watchers()
        val access = map as ChunkMapAccessor
        for (player in players) {
            val uuid = player.uuid.toString()
            fun add(index: Long2ObjectOpenHashMap<MutableList<String>>, x: Int, z: Int) {
                checkBudget()
                if (!scope.intersectsChunk(dimension, x, z)) return
                rememberActor(player)
                val key = packed(x, z)
                val values = index[key] ?: ArrayList<String>().also { timeline.work(80); index[key] = it }
                timeline.work(8)
                values.add(uuid)
            }
            fun observe(x: Int, z: Int, tracked: Boolean?) {
                add(result.inView, x, z)
                if (tracked != false) add(result.tracked, x, z)
                else add(result.pending, x, z)
            }
            fun visitRange(cx: Int, cz: Int, distance: Int, inView: (Int, Int) -> Boolean, tracked: ((Int, Int) -> Boolean)? = null) {
                // Clip the tracking rectangle before enumerating it, including players outside the selected area.
                val lowX = scope.minX?.div(16) ?: scope.radius?.let { (scope.centerX!! - it) / 16 }
                val highX = scope.maxX?.let { ceil(it / 16) - 1 } ?: scope.radius?.let { floor((scope.centerX!! + it) / 16) }
                val lowZ = scope.minZ?.div(16) ?: scope.radius?.let { (scope.centerZ!! - it) / 16 }
                val highZ = scope.maxZ?.let { ceil(it / 16) - 1 } ?: scope.radius?.let { floor((scope.centerZ!! + it) / 16) }
                val minX = maxOf(cx.toDouble() - distance, lowX?.let(::floor) ?: Double.NEGATIVE_INFINITY).toInt()
                val maxX = minOf(cx.toDouble() + distance, highX ?: Double.POSITIVE_INFINITY).toInt()
                val minZ = maxOf(cz.toDouble() - distance, lowZ?.let(::floor) ?: Double.NEGATIVE_INFINITY).toInt()
                val maxZ = minOf(cz.toDouble() + distance, highZ ?: Double.POSITIVE_INFINITY).toInt()
                for (z in minZ..maxZ) for (x in minX..maxX) {
                    checkBudget()
                    if (scope.intersectsChunk(dimension, x, z) && inView(x, z)) observe(x, z, tracked?.invoke(x, z))
                }
            }
            // Iterate actual per-player tracking areas, not holders x every online player.
            //? if <=1.20.1 {
            /*val center = player.lastSectionPos
            val distance = access.`ticksight$getViewDistance`()
            visitRange(center.x(), center.z(), distance + 1, { x, z -> ChunkMap.isChunkInRange(x, z, center.x(), center.z(), distance) })*/
            //?} else {
            val view = player.chunkTrackingView
            if (view is ChunkTrackingView.Positioned) visitRange(view.center.x, view.center.z, view.viewDistance + 1,
                { x, z -> view.contains(x, z) }, { x, z -> access.`ticksight$isChunkTracked`(player, x, z) })
            else view.forEach { pos ->
                if (scope.intersectsChunk(dimension, pos.x, pos.z)) observe(pos.x, pos.z, access.`ticksight$isChunkTracked`(player, pos.x, pos.z))
            }
            //?}
        }
        return result
    }

    private inner class TicketIndex {
        val direct = Long2ObjectOpenHashMap<MutableList<Int>>()
        // Separate level buckets avoid the chunks x every ticket Cartesian product.
        val buckets = HashMap<Pair<String, Int>, Long2ObjectOpenHashMap<MutableList<Pair<ChunkPos, Int>>>>()
        fun add(pos: ChunkPos, id: Int, channel: String, level: Int) {
            timeline.work(128)
            direct.getOrPut(packed(pos.x, pos.z)) { ArrayList() }.add(id)
            val bucket = packed(Math.floorDiv(pos.x, 4), Math.floorDiv(pos.z, 4))
            for (graph in if (channel == "both") listOf("loading", "simulation") else listOf(channel)) {
                if (graph == "none") continue
                buckets.getOrPut(graph to level) { Long2ObjectOpenHashMap() }
                    .getOrPut(bucket) { ArrayList() }.add(pos to id)
            }
        }
        fun influences(x: Int, z: Int, loadingLevel: Int, simulationLevel: Int): List<TelemetryInfluence> {
            val result = ArrayList<TelemetryInfluence>()
            for ((kind, index) in buckets) {
                val (channel, level) = kind
                val effective = if (channel == "simulation") simulationLevel else loadingLevel
                if (effective > ChunkLevel.MAX_LEVEL) continue
                val radius = effective.toLong() - level.toLong()
                if (radius <= 0) continue // radius zero is direct-only, already captured above.
                val minX = Math.floorDiv(x.toLong() - radius, 4).toInt()
                val maxX = Math.floorDiv(x.toLong() + radius, 4).toInt()
                val minZ = Math.floorDiv(z.toLong() - radius, 4).toInt()
                val maxZ = Math.floorDiv(z.toLong() + radius, 4).toInt()
                fun visit(values: List<Pair<ChunkPos, Int>>) {
                    for ((pos, source) in values) {
                        checkBudget()
                        val distance = max(abs(pos.x.toLong() - x), abs(pos.z.toLong() - z))
                        if (distance == 0L || distance > radius) continue
                        val computed = (level + distance).toInt()
                        timeline.temporary((result.size + 1) * 128L)
                        result.add(TelemetryInfluence(source, distance, computed,
                            if (computed == effective) "equal" else "candidate", channel))
                    }
                }
                // Huge/negative custom ticket levels must not turn an empty search into billions of buckets.
                if ((maxX.toLong() - minX + 1) * (maxZ.toLong() - minZ + 1) > index.size) {
                    for (bucket in index.long2ObjectEntrySet()) {
                        checkBudget()
                        val pos = unpack(bucket.longKey)
                        if (pos.x in minX..maxX && pos.z in minZ..maxZ) visit(bucket.value)
                    }
                } else for (bz in minZ..maxZ) for (bx in minX..maxX) {
                    checkBudget()
                    visit(index[packed(bx, bz)].orEmpty())
                }
            }
            return result.sortedWith(compareBy({ it.source }, { it.computedLevel }))
        }
    }

    private fun tickets(level: ServerLevel, dimension: String, scope: TelemetryScope, players: Map<UUID, ServerPlayer>): TicketIndex {
        val result = TicketIndex()
        val access = level.chunkSource.chunkMap.distanceManager as DistanceManagerAccessor
        //? if >=26.1 {
        /*val storage = access.`ticksight$getTicketStorage`() as TicketStorageAccessor
        for (entry in storage.`ticksight$getTickets`().long2ObjectEntrySet()) for (ticket in entry.value) {
            checkBudget()
            val pos = unpack(entry.longKey)
            if (!scope.intersectsChunk(dimension, pos.x, pos.z,
                    (ChunkLevel.MAX_LEVEL.toDouble() - ticket.ticketLevel).coerceAtLeast(0.0))) continue
            val type = BuiltInRegistries.TICKET_TYPE.getKey(ticket.type)?.toString() ?: ticket.type.toString()
            val channel = if (ticket.type.doesLoad() && ticket.type.doesSimulate()) "both"
                else if (ticket.type.doesSimulate()) "simulation" else if (ticket.type.doesLoad()) "loading" else "none"
            val id = timeline.source(TelemetryTicketSource(dimension, pos.x, pos.z, type, ticket.ticketLevel,
                channel, typeId = BuiltInRegistries.TICKET_TYPE.getKey(ticket.type)?.toString(),
                outsideScope = !scope.intersectsChunk(dimension, pos.x, pos.z)))
            result.add(pos, id, channel, ticket.ticketLevel)
        }*/
        //?} else {
        val loading = access.`ticksight$getLoadingTickets`()
        val simulation = (access.`ticksight$getTickingTracker`() as TickingTrackerAccessor).`ticksight$getTickets`()
        for ((channel, entries) in listOf("loading" to loading, "simulation" to simulation)) {
            for (entry in entries.long2ObjectEntrySet()) for (ticket in entry.value) {
                checkBudget()
                val pos = unpack(entry.longKey)
                if (!scope.intersectsChunk(dimension, pos.x, pos.z,
                        (ChunkLevel.MAX_LEVEL.toDouble() - ticket.ticketLevel).coerceAtLeast(0.0))) continue
                val rawKey = (ticket as TicketAccessor).`ticksight$getKey`()
                // These are the loader's real private owner records, accessed through typed mixins.
                var modId: String? = null
                val key = when (rawKey) {
                    is NeoForgeTicketOwnerAccessor -> {
                        modId = rawKey.`ticksight$getModId`().toString()
                        rawKey.`ticksight$getOwner`()
                    }
                    //? if <=1.20.1 {
                    /*is ForgeTicketOwnerAccessor -> {
                        modId = rawKey.`ticksight$getModId`()
                        rawKey.`ticksight$getOwner`()
                    }*/
                    //?}
                    else -> rawKey
                }
                val uuid = key as? UUID
                val scopedPlayer = uuid?.let(players::get)?.also(::rememberActor)
                val entityOwner = uuid?.let(level::getEntity)
                // A UUID key is observed provenance, never nearest-player attribution.
                // Referenced outside watchers/owners may disclose identity, never their position/track.
                val outsidePlayer = uuid != null && scopedPlayer == null && entityOwner == null
                val owner = scopedPlayer?.let { TelemetryTicketOwner("player", it.uuid.toString(), it.gameProfile.name) }
                    ?: entityOwner?.let { TelemetryTicketOwner("entity", it.uuid.toString(), modId) }
                    ?: modId?.let { TelemetryTicketOwner("mod", name = it) }
                val id = timeline.source(TelemetryTicketSource(dimension, pos.x, pos.z, ticket.type.toString(),
                    ticket.ticketLevel, channel, key = if (outsidePlayer) null else key?.toString(),
                    keyClass = rawKey?.javaClass?.name, owner = owner,
                    outsideScope = !scope.intersectsChunk(dimension, pos.x, pos.z),
                    forceTicks = ChunkTelemetryPlatform.ticketForceTicks?.apply(ticket), modId = modId))
                result.add(pos, id, channel, ticket.ticketLevel)
            }
        }
        //?}
        return result
    }

    private fun simulationLevel(level: ServerLevel, pos: ChunkPos): Int {
        //? if >=26.1 {
        /*return level.chunkSource.chunkMap.distanceManager.getChunkLevel(packed(pos.x, pos.z), true)*/
        //?} else {
        return (level.chunkSource.chunkMap.distanceManager as DistanceManagerAccessor).`ticksight$getTickingTracker`().getLevel(pos)
        //?}
    }

    // These getters inspect completed futures/volatile state only; they do not schedule generation.
    //? if <=1.20.1 {
    /*private fun latestChunk(holder: ChunkHolder) = holder.lastAvailable
    private fun latestStatus(holder: ChunkHolder) = holder.lastAvailableStatus
    private fun ready(future: java.util.concurrent.CompletableFuture<com.mojang.datafixers.util.Either<net.minecraft.world.level.chunk.LevelChunk, ChunkHolder.ChunkLoadingFailure>>): Boolean =
        !future.isCompletedExceptionally && future.getNow(null)?.left()?.isPresent == true*/
    //?} else {
    private fun latestChunk(holder: ChunkHolder) = holder.latestChunk
    private fun latestStatus(holder: ChunkHolder) = holder.latestStatus
    private fun ready(future: java.util.concurrent.CompletableFuture<net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.LevelChunk>>): Boolean =
        !future.isCompletedExceptionally && future.getNow(null)?.isSuccess == true
    //?}
}
