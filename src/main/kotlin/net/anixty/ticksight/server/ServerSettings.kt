package net.anixty.ticksight.server

import dev.architectury.platform.Platform
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

val configFile = Platform.getConfigFolder().resolve("ticksight.json")
private val settingsJson = Json { encodeDefaults = true }
var ServerSettings = loadSettings()

internal const val NANOS_PER_MILLISECOND = 1_000_000.0
internal const val MIN_SAMPLING_INTERVAL_MS = 1.0 / NANOS_PER_MILLISECOND
// Keep the converted delay within Double's exact integer range. A delay plus
// jitter then fits comfortably in the signed Long used for nanoTime differences.
internal const val MAX_SAMPLING_DELAY_MS = (1L shl 53) / NANOS_PER_MILLISECOND

@Serializable
data class ServerSettingsData(
    var traceInterval: Double = 3.0,
    var deviation: Double = 1.0,
    var allPlayersAllowed: Boolean = false,
    var allowedPlayers: MutableSet<String> = mutableSetOf(),
    var includeJvmArgs: Boolean = true,
    var uploadURL: String = "https://ticksight.anixty.net/v1/add",
    var callTraceTickLimitMs: Int = 1536,
    var callTraceMaxRepeats: Int = 128,
    var callTraceHardTickLimitMs: Int = 5000,
    var callTraceMemoryLimitMiB: Int = 128,
    var callTraceHeapReserveMiB: Int = 256
) {
    fun sync() {
        require(traceInterval.isFinite() && traceInterval in MIN_SAMPLING_INTERVAL_MS..MAX_SAMPLING_DELAY_MS) {
            "traceInterval must be between 1 nanosecond and $MAX_SAMPLING_DELAY_MS milliseconds."
        }
        require(deviation.isFinite() && deviation in 0.0..MAX_SAMPLING_DELAY_MS) {
            "deviation must be between 0 and $MAX_SAMPLING_DELAY_MS milliseconds."
        }
        val pending = Files.createTempFile(configFile.parent, "ticksight-", ".pending")
        try {
            pending.writeText(settingsJson.encodeToString(this))
            try {
                Files.move(pending, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending, configFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(pending)
        }
    }
}

fun loadSettings(): ServerSettingsData {
    if (!configFile.exists()) {
        val settings = ServerSettingsData()
        settings.sync()
        return settings
    }
    return settingsJson.decodeFromString<ServerSettingsData>(configFile.readText()).also { it.sync() }
}

fun resetSettings() {
    val defaults = ServerSettingsData()
    defaults.sync()
    ServerSettings = defaults
}
