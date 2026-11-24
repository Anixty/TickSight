package net.anixty.ticksight.server

import dev.architectury.injectables.targets.ArchitecturyTarget
import dev.architectury.platform.Platform
import kotlinx.serialization.json.*
import net.minecraft.SystemReport
import net.minecraft.server.MinecraftServer
import net.anixty.ticksight.TickSight
import java.lang.management.ManagementFactory
import kotlin.math.roundToLong

/** Capture resolved recording parameters and server state once, on the server thread. */
internal fun captureRunDiagnostics(
    server: MinecraftServer, duration: ProfileDuration, sample: Boolean, peak: Boolean,
    calls: CallProfile?, area: ProfileArea?, target: ProfileTarget, started: Boolean = true, layers: RecordingLayers = RecordingLayers()
): JsonObject {
    val settings = ServerSettings
    val loader = ArchitecturyTarget.getCurrentTarget()
    val loaderId = if (loader == "fabric") "fabricloader" else loader
    return buildJsonObject {
        put("source", "server")
        put("content", "full")
        put("capturedAt", System.currentTimeMillis())
        put("server", buildJsonObject {
            put("type", if (server.isDedicatedServer) "dedicated" else "integrated")
            put("minecraftVersion", Platform.getMinecraftVersion())
            put("modLoader", loader)
            if (Platform.isModLoaded(loaderId)) put("loaderVersion", Platform.getMod(loaderId).version)
            put("tickSightVersion", Platform.getMod(TickSight.MOD_ID).version)
            put("players", server.playerList.playerCount)
            put("maxPlayers", server.playerList.maxPlayers)
            put("viewDistance", server.playerList.viewDistance)
            put("simulationDistance", server.playerList.simulationDistance)
            put("gameMode", server.defaultGameType.getName())
            put("runtime", buildJsonObject {
                put("javaVersion", System.getProperty("java.version"))
                put("javaVendor", System.getProperty("java.vendor"))
                put("javaVm", System.getProperty("java.vm.name"))
                put("os", System.getProperty("os.name"))
                put("osVersion", System.getProperty("os.version"))
                put("architecture", System.getProperty("os.arch"))
                put("processors", Runtime.getRuntime().availableProcessors())
                put("maxHeapBytes", Runtime.getRuntime().maxMemory())
            })
        })
        if (started) put("settings", measurementSettings(settings))
        put("recording", recordingParameters(duration, sample, peak, calls, target.label(),
            area?.label() ?: "All dimensions", started, settings, layers))
    }
}

internal fun recordingParameters(
    duration: ProfileDuration, sample: Boolean, peak: Boolean, calls: CallProfile?,
    target: String, scope: String, started: Boolean, settings: ServerSettingsData, layers: RecordingLayers = RecordingLayers()
): JsonObject = buildJsonObject {
    put("started", started)
    put("mode", if (calls != null) "calls" else if (sample) "sample" else "timings")
    put("layers", Json.encodeToJsonElement(layers))
    put("requestedDuration", buildJsonObject {
        put("amount", duration.amount)
        put("unit", if (duration.inTicks) "ticks" else "seconds")
    })
    put("initialView", if (peak) "peak" else "average")
    put("target", target)
    put("scope", scope)
    if (started && sample) {
        val interval = (settings.traceInterval * NANOS_PER_MILLISECOND).roundToLong()
        val deviation = (settings.deviation * NANOS_PER_MILLISECOND).roundToLong().coerceAtMost(interval - 1)
        put("samplingIntervalNanos", interval)
        put("samplingDeviationNanos", deviation)
    }
    if (calls != null) put("calls", buildJsonObject {
        put("tickLimitMs", calls.limitMs)
        put("maxRepeats", calls.maxRepeats)
        put("hardTickLimitMs", calls.hardLimitMs)
        put("memoryLimitMiB", calls.memoryLimitMiB)
        put("heapReserveMiB", calls.heapReserveMiB)
    })
}

/** Measurement settings only: access lists, endpoints and credentials stay in the config. */
internal fun measurementSettings(settings: ServerSettingsData): JsonObject = buildJsonObject {
    put("traceIntervalMs", settings.traceInterval)
    put("deviationMs", settings.deviation)
    put("includeJvmArgs", settings.includeJvmArgs)
    put("callTraceTickLimitMs", settings.callTraceTickLimitMs)
    put("callTraceMaxRepeats", settings.callTraceMaxRepeats)
    put("callTraceHardTickLimitMs", settings.callTraceHardTickLimitMs)
    put("callTraceMemoryLimitMiB", settings.callTraceMemoryLimitMiB)
    put("callTraceHeapReserveMiB", settings.callTraceHeapReserveMiB)
}

internal fun getDiagnostics(owner: ProfileOwner, startTime: Long, duration: Long, snapshot: JsonObject): JsonObject {
    val systemReport = SystemReport()
    val includeJvmArgs = snapshot["settings"]?.jsonObject?.get("includeJvmArgs")?.jsonPrimitive?.booleanOrNull == true
    // Free-form -D values and agent arguments can contain credentials or private endpoints.
    val flags = if (includeJvmArgs) safeJvmFlags(ManagementFactory.getRuntimeMXBean().inputArguments) else emptyList()
    systemReport.setDetail("JVM Flags", if (includeJvmArgs) flags.joinToString(" ").ifEmpty { "<none>" } + " (other arguments omitted)" else "<REDACTED>")

    return buildJsonObject {
        snapshot.forEach { (key, value) -> put(key, value) }
        put("jvmFlagsSanitized", true)
        put("owner", Json.encodeToJsonElement(owner))
        put("start", startTime)
        put("duration", duration)
        put("minecraftVersion", Platform.getMinecraftVersion())
        put("modLoader", ArchitecturyTarget.getCurrentTarget())
        put("tickSightVersion", Platform.getMod(TickSight.MOD_ID).version)
        put(
            "additionalDiagnostics",
            buildJsonObject {
                put("System Report", systemReport.toLineSeparatedString())
                put(
                    "Mods",
                    Platform.getMods().joinToString("\n") { mod ->
                        "'${mod.name}' (version: ${mod.version})"
                    }
                )
            }
        )
    }
}

internal fun safeJvmFlags(arguments: List<String>): List<String> {
    val safeFlag = Regex("-X(?:ms|mx|ss)[0-9]+[kKmMgG]?|-XX:[+-][A-Za-z0-9_]+|-XX:[A-Za-z0-9_]+=[0-9.]+[kKmMgG%]?")
    return arguments.filter(safeFlag::matches)
}
