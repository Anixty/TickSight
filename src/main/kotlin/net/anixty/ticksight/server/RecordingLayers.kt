package net.anixty.ticksight.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

internal enum class RecordedLayer { RUNTIME, GENERATION, TICKET_LEVELS, TICKET_SOURCES, VISIBILITY, PLAYERS }

/** Per-recording collection choices. Every entry point records all map layers unless explicitly disabled. */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class RecordingLayers(
    @EncodeDefault val runtime: Boolean = true,
    @EncodeDefault val generation: Boolean = true,
    @EncodeDefault val ticketLevels: Boolean = true,
    @EncodeDefault val ticketSources: Boolean = true,
    @EncodeDefault val visibility: Boolean = true,
    @EncodeDefault val players: Boolean = true
) {
    internal val chunks: Boolean get() = runtime || generation || ticketLevels || ticketSources || visibility
    internal val any: Boolean get() = chunks || players
    internal inline fun <T> read(enabled: Boolean, provider: () -> T): T? = if (enabled) provider() else null

    companion object {
        internal fun without(disabled: Set<RecordedLayer>) = RecordingLayers(
            RecordedLayer.RUNTIME !in disabled, RecordedLayer.GENERATION !in disabled,
            RecordedLayer.TICKET_LEVELS !in disabled, RecordedLayer.TICKET_SOURCES !in disabled,
            RecordedLayer.VISIBILITY !in disabled, RecordedLayer.PLAYERS !in disabled
        )
    }
}
