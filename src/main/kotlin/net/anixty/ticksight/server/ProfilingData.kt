@file:UseSerializers(
    EntitySerializer::class,
    ResourceLocationSerializer::class,
    BlockEntitySerializer::class,
    BlockPosSerializer::class
)

package net.anixty.ticksight.server

import net.anixty.ticksight.util.locationId

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.*
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
//? if >=26.1 {
/*
import net.minecraft.resources.Identifier as ResourceLocation
*/
//?} else {
import net.minecraft.resources.ResourceLocation
//?}
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.anixty.ticksight.net.*

fun getPosition(obj: Any?): BlockPos =
    when (obj) {
        is Entity -> obj.blockPosition()
        is BlockPos -> obj
        else -> BlockPos.ZERO
    }

@Serializable
data class ProfilingData(
    val entities: Map<ResourceLocation, List<Entry>>,
    val blocks: Map<ResourceLocation, List<Entry>>,
    val traces: SerializedTraceMap?,
    val ticks: Int,
    val peakMode: Boolean = false,
    val timelineTicks: Int,
    val callTrace: CallTraceReport? = null,
    val tickStarts: TickTimeline? = null,
    val chunkTelemetry: ChunkTelemetry? = null
) {
    internal fun forOverlay(): ProfilingData = copy(
        entities = entities.mapValues { (_, entries) -> entries.map {
            it.copy(tickHistory = null, callTargetId = null)
        } },
        blocks = blocks.mapValues { (_, entries) -> entries.map { it.copy(tickHistory = null, callTargetId = null) } },
        callTrace = null,
        chunkTelemetry = null
    )

    companion object {
        fun create(
            entities: Map<Entity, Profiler.TimingData>,
            blocks: Map<ResourceKey<Level>, Map<Profiler.BlockTarget, Profiler.TimingData>>,
            ticks: Int,
            traceMap: TraceMap? = null,
            peakMode: Boolean = false,
            timelineTicks: Int,
            callTrace: CallTraceReport? = null,
            tickStarts: TickTimeline? = null,
            chunkTelemetry: ChunkTelemetry? = null
        ): ProfilingData {
            val entityEntries =
                entities
                    .asIterable()
                    .groupBy { it.key.level().dimension().locationId() }
                    .mapValues { (_, entries) ->
                        entries.map { (entity, data) ->
                            Entry(entity, BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString(), data)
                        }
                    }

            val blockEntries =
                blocks
                    .map { (level, posMap) ->
                        level.locationId() to posMap.map { (target, data) -> Entry(target.position, data.name, data) }
                    }
                    .toMap()

            return ProfilingData(
                entityEntries,
                blockEntries,
                traceMap?.let { SerializedTraceMap.create(it) },
                ticks,
                peakMode,
                timelineTicks,
                callTrace,
                tickStarts,
                chunkTelemetry
            )
        }
    }

    @Serializable
    data class Entry(
        val entityId: Int? = null,
        val position: BlockPos,
        val type: String,
        val rate: Double,
        val ticks: Int,
        val traces: SerializedTraceMap,
        val peak: Long,
        val tickHistory: TickHistory? = null,
        val callTargetId: Int? = null,
        val entityUuid: String? = null
    ) {
        constructor(
            obj: Any,
            type: String,
            data: Profiler.TimingData
        ) : this(
            (obj as? Entity)?.id,
            getPosition(obj),
            type,
            data.time.toDouble() / data.ticks.toDouble(),
            data.ticks,
            SerializedTraceMap.create(data.traces),
            data.peak,
            data.tickHistory(),
            data.callTargetId,
            (obj as? Entity)?.uuid?.toString()
        )
    }

    @Serializable
    data class TickHistory(
        val firstTick: Int,
        val times: LongArray,
        // Null for consecutive ticks. Calls and traces use the same array index as times.
        val ticks: IntArray? = null,
        val calls: Map<Int, Int> = emptyMap(),
        val traces: Map<Int, SerializedTraceMap> = emptyMap()
    )

    @Serializable
    data class SerializedTraceMap(
        val className: String,
        val methodName: String,
        val children: List<SerializedTraceMap>,
        val count: Int
    ) {
        companion object {
            fun create(traceMap: TraceMap): SerializedTraceMap {
                // A timing-only report has no sampled methods to remap or mappings to download.
                if (traceMap.count > 0) Remapper.transform(traceMap)

                return SerializedTraceMap(
                    traceMap.className,
                    traceMap.methodName,
                    traceMap.children
                        .map { (_, map) ->
                            SerializedTraceMap.create(map)
                        }
                        .sortedByDescending { it.count },
                    traceMap.count
                )
            }
        }

        val classMethod
            get() = "$className.$methodName"
    }
}

@Serializable data class DataWithDiagnostics(val data: ProfilingData, val diagnostics: JsonObject)
