package net.anixty.ticksight.server

import net.anixty.ticksight.TickSight
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Streams reports to disk; serialization and HTTP never buffer the whole JSON. */
internal const val MAX_PROFILE_BYTES = 128L * 1024 * 1024

internal object ProfileArchive {
    private val directory = Path.of("ticksight", "profiles")
    private val profileName = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json")

    @OptIn(ExperimentalSerializationApi::class)
    fun write(data: ProfilingData, diagnostics: JsonObject): Path {
        Files.createDirectories(directory)
        val file = directory.resolve("${UUID.randomUUID()}.json")
        val pending = file.resolveSibling("${file.fileName}.pending")
        try {
            BoundedOutput(Files.newOutputStream(pending)).use {
                Json.encodeToStream(DataWithDiagnostics.serializer(), DataWithDiagnostics(data, diagnostics), it)
            }
            try {
                Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending, file)
            }
            return file
        } finally {
            Files.deleteIfExists(pending)
        }
    }

    fun remove(file: Path): Boolean = try {
        Files.deleteIfExists(file)
        true
    } catch (error: IOException) {
        TickSight.LOGGER.warn("Could not remove the uploaded profile $file", error)
        false
    }

    fun prune() {
        if (!Files.isDirectory(directory)) return
        try {
            Files.list(directory).use { stream ->
                stream.filter { Files.isRegularFile(it) && profileName.matches(it.fileName.toString()) }
                    .sorted(compareByDescending<Path> { Files.getLastModifiedTime(it) })
                    .skip(32).forEach { remove(it) }
            }
        } catch (error: IOException) {
            TickSight.LOGGER.warn("Could not prune saved profiles", error)
        }
    }

    private class BoundedOutput(output: OutputStream) : FilterOutputStream(output) {
        private var bytes = 0L
        override fun write(value: Int) {
            checkCapacity(1)
            out.write(value)
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            checkCapacity(length)
            out.write(buffer, offset, length)
        }
        private fun checkCapacity(length: Int) {
            if (length.toLong() > MAX_PROFILE_BYTES - bytes) throw IOException("Profile JSON exceeds 128 MiB")
            bytes += length
        }
    }
}
