package net.anixty.ticksight.server

import kotlinx.serialization.Serializable

/** Server-thread observations at the end of a recorded tick, independent of object timings. */
@Serializable
data class ChunkTelemetry(
    val version: Int,
    val capturePoint: String,
    val scope: TelemetryScope,
    val dimensions: List<String>,
    val capabilities: TelemetryCapabilities,
    val states: List<TelemetryChunkState>,
    val ticketSources: List<TelemetryTicketSource>,
    val watcherSets: List<List<String>>,
    val directTicketSets: List<List<Int>>,
    val influences: List<TelemetryInfluence>,
    val influenceSets: List<List<Int>>,
    val frames: List<TelemetryFrame>,
    val completeThroughTick: Int,
    val truncated: TelemetryCutoff? = null,
    val actors: List<TelemetryActor> = emptyList()
)

/** XZ projection of the command scope. Bounds are block coordinates, max exclusive. */
@Serializable
data class TelemetryScope(
    val kind: String,
    val dimension: String? = null,
    val minX: Double? = null,
    val maxX: Double? = null,
    val minZ: Double? = null,
    val maxZ: Double? = null,
    val centerX: Double? = null,
    val centerZ: Double? = null,
    val radius: Double? = null
) {
    fun contains(dimension: String, x: Double, z: Double): Boolean {
        if (this.dimension != null && this.dimension != dimension) return false
        if (minX != null && maxX != null && minX >= maxX || minZ != null && maxZ != null && minZ >= maxZ) return false
        if (minX != null && x < minX || maxX != null && x >= maxX ||
            minZ != null && z < minZ || maxZ != null && z >= maxZ) return false
        if (radius != null && centerX != null && centerZ != null) {
            val dx = x - centerX; val dz = z - centerZ
            if (dx * dx + dz * dz > radius * radius) return false
        }
        return true
    }

    fun intersectsChunk(dimension: String, x: Int, z: Int, margin: Double = 0.0): Boolean {
        if (this.dimension != null && this.dimension != dimension) return false
        if (minX != null && maxX != null && minX >= maxX || minZ != null && maxZ != null && minZ >= maxZ) return false
        val startX = (x - margin) * 16; val startZ = (z - margin) * 16
        val endX = (x + margin + 1) * 16; val endZ = (z + margin + 1) * 16
        if (minX != null && endX <= minX || maxX != null && startX >= maxX ||
            minZ != null && endZ <= minZ || maxZ != null && startZ >= maxZ) return false
        if (radius != null && centerX != null && centerZ != null) {
            val dx = centerX - centerX.coerceIn(startX, endX)
            val dz = centerZ - centerZ.coerceIn(startZ, endZ)
            val distanceSquared = dx * dx + dz * dz
            if (distanceSquared > radius * radius || distanceSquared == radius * radius && (centerX >= endX || centerZ >= endZ)) return false
        }
        return true
    }
}

@Serializable
data class TelemetryCapabilities(
    val holders: String,
    val generationStatus: String,
    val tickets: String,
    val ticketOwners: String,
    val watchers: String,
    val clientRender: Boolean,
    val simulationLevel: Boolean,
    val pendingSends: String,
    val layers: RecordingLayers
)

@Serializable
data class TelemetryChunkKey(val dimension: Int, val x: Int, val z: Int)

@Serializable
data class TelemetryChunkUpdate(val dimension: Int, val x: Int, val z: Int, val state: Int)

@Serializable
data class TelemetryPlayer(val uuid: String, val name: String, val dimension: String, val x: Double, val y: Double, val z: Double)

/** Identity for a real watcher/ticket owner; no out-of-scope position or movement is disclosed. */
@Serializable
data class TelemetryActor(val uuid: String, val name: String)

@Serializable
data class TelemetryInfluence(val source: Int, val distance: Long, val computedLevel: Int, val relation: String, val channel: String)

@Serializable
data class TelemetryChunkState(
    val loaded: Boolean?,
    val fullStatus: String?,
    val generationStatus: String?,
    val targetGenerationStatus: String?,
    val ticketLevel: Int?,
    val simulationLevel: Int?,
    val fullReady: Boolean?,
    val blockTickingReady: Boolean?,
    val entityTickingReady: Boolean?,
    val visibleHolder: Boolean?,
    val watcherSet: Int?,
    val directTicketSet: Int?,
    val influenceSet: Int?,
    val blockTicking: Boolean?,
    val entityTicking: Boolean?,
    val forcedTicking: Boolean? = null,
    val managedSources: List<TelemetryManagedSource> = emptyList(),
    val pendingUnload: Boolean? = null,
    val inViewSet: Int? = null,
    val pendingSendSet: Int? = null
)

@Serializable
data class TelemetryManagedSource(
    val kind: String,
    val mode: String,
    val modId: String,
    val key: String?,
    val keyClass: String?,
    val owner: TelemetryTicketOwner?
)

@Serializable
data class TelemetryTicketOwner(val kind: String, val uuid: String? = null, val name: String? = null)

@Serializable
data class TelemetryTicketSource(
    val dimension: String,
    val x: Int,
    val z: Int,
    val type: String,
    val level: Int,
    val channel: String,
    val typeId: String? = null,
    val key: String? = null,
    val keyClass: String? = null,
    val owner: TelemetryTicketOwner? = null,
    val outsideScope: Boolean = false,
    val forceTicks: Boolean? = null,
    val modId: String? = null
)

@Serializable
data class TelemetryFrame(
    val tick: Int,
    val chunks: List<TelemetryChunkUpdate>,
    val removedChunks: List<TelemetryChunkKey>,
    val players: List<TelemetryPlayer>,
    val removedPlayers: List<String>,
    val baseline: Boolean = false,
    val scope: TelemetryScope? = null
)

@Serializable
data class TelemetryCutoff(val tick: Int, val reason: String)

/** Delta/dictionary encoder; never publishes a half-captured frame. No Minecraft references. */
internal class TelemetryTimeline(val scope: TelemetryScope, private val maxBytes: Long, ticketDataRecorded: Boolean = true, visibilityRecorded: Boolean = true) {
    private val states = LinkedHashMap<TelemetryChunkState, Int>()
    private val sources = if (ticketDataRecorded) LinkedHashMap<TelemetryTicketSource, Int>() else null
    private val watchers = if (visibilityRecorded) LinkedHashMap<List<String>, Int>() else null
    private val directTickets = if (ticketDataRecorded) LinkedHashMap<List<Int>, Int>() else null
    private val influences = if (ticketDataRecorded) LinkedHashMap<TelemetryInfluence, Int>() else null
    private val influenceSets = if (ticketDataRecorded) LinkedHashMap<List<Int>, Int>() else null
    private val frames = ArrayList<TelemetryFrame>()
    private var previousChunks: Map<TelemetryChunkKey, Int> = emptyMap()
    private var previousPlayers: Map<String, TelemetryPlayer> = emptyMap()
    private var previousScope = scope
    private var retained = 0L
    private var wire = 0L
    private var working = 0L
    private var temporary = 0L
    private var cached = 0L
    private var workingPeak = 0L
    var cutoff: TelemetryCutoff? = null
        private set

    fun stop(tick: Int, reason: String) { if (cutoff == null) cutoff = TelemetryCutoff(tick, reason) }
    private fun reserve(memoryBytes: Long, wireBytes: Long) {
        if (memoryBytes > maxBytes - retained - working - temporary || wireBytes > maxBytes - wire)
            throw TelemetryLimit("memory_or_report_size")
        retained += memoryBytes
        wire += wireBytes
    }

    /** Includes the old and next working maps, native indexes and temporary candidate arrays. */
    fun beginFrame() { working = cached; temporary = 0 }
    fun actor(value: TelemetryActor) {
        val bytes = 160L + textBytes(value.uuid) + 2 * textBytes(value.name)
        reserve(bytes, bytes)
    }
    fun work(bytes: Long) {
        if (bytes > maxBytes - retained - working - temporary) throw TelemetryLimit("memory_or_report_size")
        working += bytes
        workingPeak = maxOf(workingPeak, working)
    }
    fun temporary(bytes: Long) {
        if (bytes > maxBytes - retained - working) throw TelemetryLimit("memory_or_report_size")
        temporary = bytes
        workingPeak = maxOf(workingPeak, working + bytes)
    }

    private fun textBytes(value: String?): Long {
        if (value == null) return 4
        var bytes = 2L
        for (c in value) bytes += when {
            c == '"' || c == '\\' -> 2
            c.code < 32 -> 6
            c.code < 128 -> 1
            c.code < 2048 -> 2
            else -> 3 // Also bounds the pair of UTF-16 surrogates used for a four-byte code point.
        }
        return bytes
    }

    fun watcherSet(value: List<String>): Int {
        val table = checkNotNull(watchers)
        return table[value] ?: run {
            reserve(80L + 96L * value.size, 4L + 42L * value.size)
            table.size.also { table[value.toList()] = it }
        }
    }

    fun directTicketSet(value: List<Int>): Int {
        val table = checkNotNull(directTickets)
        return table[value] ?: run {
            reserve(80L + 24L * value.size, 4L + 12L * value.size)
            table.size.also { table[value.toList()] = it }
        }
    }

    fun influenceSet(value: List<TelemetryInfluence>): Int {
        val links = checkNotNull(influences)
        val sets = checkNotNull(influenceSets)
        // Candidate link objects, the sorted list and the new ref list overlap with dictionary allocation.
        temporary(80L + value.size * 160L)
        try {
            val refs = value.map { link -> links[link] ?: run {
                reserve(112L, 144L)
                links.size.also { links[link] = it }
            } }
            return sets[refs] ?: run {
                reserve(80L + 24L * refs.size, 4L + 12L * refs.size)
                sets.size.also { sets[refs] = it }
            }
        } finally { temporary = 0 }
    }

    fun state(value: TelemetryChunkState): Int = states[value] ?: run {
        val strings = textBytes(value.fullStatus) + textBytes(value.generationStatus) + textBytes(value.targetGenerationStatus)
        val managed = value.managedSources.sumOf { 384L + 6L * (it.modId.length + (it.key?.length ?: 0) + (it.keyClass?.length ?: 0)) }
        reserve(256L + 2L * strings + managed, 448L + strings + managed)
        states.size.also { states[value] = it }
    }

    fun source(value: TelemetryTicketSource): Int {
        val table = checkNotNull(sources)
        return table[value] ?: run {
        // Conservative retained/wire bound including JSON escaping, rather than an unbounded string cache.
        val strings = textBytes(value.dimension) + textBytes(value.type) + textBytes(value.typeId) + textBytes(value.modId) +
            textBytes(value.key) + textBytes(value.keyClass) + textBytes(value.owner?.name)
        reserve(320L + 2L * strings, 320L + strings)
            table.size.also { table[value] = it }
        }
    }

    fun append(tick: Int, scope: TelemetryScope, chunks: Map<TelemetryChunkKey, Int>, players: List<TelemetryPlayer>, checkBudget: () -> Unit = {}) {
        check(cutoff == null)
        require(tick > (frames.lastOrNull()?.tick ?: 0))
        val updates = ArrayList<TelemetryChunkUpdate>()
        for ((key, state) in chunks) {
            checkBudget()
            if (previousChunks[key] != state) updates.add(TelemetryChunkUpdate(key.dimension, key.x, key.z, state))
        }
        val removed = previousChunks.keys.filter { it !in chunks }
        val nextPlayers = players.associateBy { it.uuid }
        val playerUpdates = players.filter { previousPlayers[it.uuid] != it }
        val removedPlayers = previousPlayers.keys.filter { it !in nextPlayers }
        val playerBytes = playerUpdates.sumOf { 384L + (it.name.length + it.dimension.length) * 6L } + removedPlayers.size * 80L
        reserve(256L + updates.size * 64L + removed.size * 48L + playerBytes,
            256L + updates.size * 80L + removed.size * 64L + playerBytes)
        checkBudget()
        frames.add(TelemetryFrame(tick, updates, removed, playerUpdates, removedPlayers, frames.isEmpty(),
            scope.takeUnless { it == previousScope }))
        previousChunks = chunks
        previousPlayers = nextPlayers
        previousScope = scope
        cached = chunks.size * 96L + nextPlayers.size * 160L
        working = cached
    }

    fun snapshot(dimensions: List<String>, capabilities: TelemetryCapabilities, actors: List<TelemetryActor> = emptyList()): ChunkTelemetry {
        // A budget failure may intern a value before the frame can commit. Publish only referenced facts.
        // This runs on the existing report worker, after all Minecraft access has stopped.
        val allStates = states.keys.toList()
        val allSources = sources?.keys?.toList().orEmpty()
        val allWatchers = watchers?.keys?.toList().orEmpty()
        val allDirect = directTickets?.keys?.toList().orEmpty()
        val allInfluences = influences?.keys?.toList().orEmpty()
        val allInfluenceSets = influenceSets?.keys?.toList().orEmpty()
        val usedStates = BooleanArray(allStates.size)
        frames.forEach { frame -> frame.chunks.forEach { usedStates[it.state] = true } }
        val usedWatchers = BooleanArray(allWatchers.size)
        val usedDirect = BooleanArray(allDirect.size)
        val usedInfluenceSets = BooleanArray(allInfluenceSets.size)
        allStates.forEachIndexed { index, state -> if (usedStates[index]) {
            state.watcherSet?.let { usedWatchers[it] = true }
            state.inViewSet?.let { usedWatchers[it] = true }
            state.pendingSendSet?.let { usedWatchers[it] = true }
            state.directTicketSet?.let { usedDirect[it] = true }
            state.influenceSet?.let { usedInfluenceSets[it] = true }
        } }
        val usedSources = BooleanArray(allSources.size)
        val usedInfluences = BooleanArray(allInfluences.size)
        allDirect.forEachIndexed { index, values -> if (usedDirect[index]) values.forEach { usedSources[it] = true } }
        allInfluenceSets.forEachIndexed { index, values -> if (usedInfluenceSets[index]) values.forEach { usedInfluences[it] = true } }
        allInfluences.forEachIndexed { index, value -> if (usedInfluences[index]) usedSources[value.source] = true }
        fun ids(used: BooleanArray): IntArray {
            var next = 0
            return IntArray(used.size) { index -> if (used[index]) next++ else -1 }
        }
        val stateIds = ids(usedStates); val sourceIds = ids(usedSources)
        val watcherIds = ids(usedWatchers); val directIds = ids(usedDirect)
        val influenceIds = ids(usedInfluences); val influenceSetIds = ids(usedInfluenceSets)
        val keptWatchers = allWatchers.filterIndexed { index, _ -> usedWatchers[index] }
        val keptDirect = allDirect.filterIndexed { index, _ -> usedDirect[index] }.map { values -> values.map { sourceIds[it] } }
        val keptInfluences = allInfluences.filterIndexed { index, _ -> usedInfluences[index] }.map { it.copy(source = sourceIds[it.source]) }
        val keptInfluenceSets = allInfluenceSets.filterIndexed { index, _ -> usedInfluenceSets[index] }.map { values -> values.map { influenceIds[it] } }
        val keptStates = allStates.filterIndexed { index, _ -> usedStates[index] }.map { state -> state.copy(
            watcherSet = state.watcherSet?.let { watcherIds[it] }, directTicketSet = state.directTicketSet?.let { directIds[it] }, influenceSet = state.influenceSet?.let { influenceSetIds[it] },
            inViewSet = state.inViewSet?.let { watcherIds[it] }, pendingSendSet = state.pendingSendSet?.let { watcherIds[it] }
        ) }
        val keptSources = allSources.filterIndexed { index, _ -> usedSources[index] }
        val referencedActors = HashSet<String>()
        keptStates.forEach { state ->
            state.watcherSet?.let { referencedActors.addAll(keptWatchers[it]) }
            state.inViewSet?.let { referencedActors.addAll(keptWatchers[it]) }
            state.pendingSendSet?.let { referencedActors.addAll(keptWatchers[it]) }
            state.managedSources.forEach { it.owner?.uuid?.let(referencedActors::add) }
        }
        keptSources.forEach { it.owner?.uuid?.let(referencedActors::add) }
        frames.forEach { frame -> frame.players.forEach { referencedActors.add(it.uuid) } }
        return ChunkTelemetry(2, "server_tick_end", scope, dimensions, capabilities, keptStates, keptSources,
            keptWatchers, keptDirect, keptInfluences, keptInfluenceSets,
            frames.map { frame -> frame.copy(chunks = frame.chunks.map { it.copy(state = stateIds[it.state]) }) },
            frames.lastOrNull()?.tick ?: 0, cutoff, actors.filter { it.uuid in referencedActors })
    }
}

internal class TelemetryLimit(val reason: String) : RuntimeException(null, null, false, false)
