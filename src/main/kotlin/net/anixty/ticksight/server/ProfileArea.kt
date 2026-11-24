package net.anixty.ticksight.server

import net.anixty.ticksight.util.locationId
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

internal sealed interface ProfileArea {
    val dimension: ResourceKey<Level>
    fun contains(x: Double, y: Double, z: Double): Boolean
    fun label(): String

    data class Dimension(override val dimension: ResourceKey<Level>) : ProfileArea {
        override fun contains(x: Double, y: Double, z: Double): Boolean = true
        override fun label(): String = dimension.locationId().toString()
    }

    data class Radius(
        override val dimension: ResourceKey<Level>,
        val center: Vec3,
        val radius: Double,
        val shape: Shape,
        val worldFloor: Int
    ) : ProfileArea {
        enum class Shape { CIRCLE, SPHERE }

        private val radiusSquared = radius * radius

        override fun contains(x: Double, y: Double, z: Double): Boolean {
            val dx = x - center.x
            val dz = z - center.z
            val horizontalSquared = dx * dx + dz * dz
            if (shape == Shape.CIRCLE) return y >= worldFloor && y <= center.y && horizontalSquared <= radiusSquared
            val dy = y - center.y
            return horizontalSquared + dy * dy <= radiusSquared
        }

        override fun label(): String =
            "${dimension.locationId()} · ${shape.name.lowercase()} radius $radius at (${center.x}, ${center.y}, ${center.z})"
    }

    data class Box(
        override val dimension: ResourceKey<Level>,
        val first: BlockPos,
        val second: BlockPos
    ) : ProfileArea {
        private val minX = minOf(first.x, second.x)
        private val minY = minOf(first.y, second.y)
        private val minZ = minOf(first.z, second.z)
        private val maxX = maxOf(first.x, second.x)
        private val maxY = maxOf(first.y, second.y)
        private val maxZ = maxOf(first.z, second.z)

        override fun contains(x: Double, y: Double, z: Double): Boolean =
            x >= minX && x < maxX.toDouble() + 1 &&
                y >= minY && y < maxY.toDouble() + 1 &&
                z >= minZ && z < maxZ.toDouble() + 1

        override fun label(): String =
            "${dimension.locationId()} · box ($minX, $minY, $minZ) to ($maxX, $maxY, $maxZ)"
    }

    data class Chunks(
        override val dimension: ResourceKey<Level>,
        val firstX: Int, val firstZ: Int,
        val secondX: Int, val secondZ: Int
    ) : ProfileArea {
        private val minX = minOf(firstX, secondX).toLong() * 16
        private val minZ = minOf(firstZ, secondZ).toLong() * 16
        private val endX = (maxOf(firstX, secondX).toLong() + 1) * 16
        private val endZ = (maxOf(firstZ, secondZ).toLong() + 1) * 16

        override fun contains(x: Double, y: Double, z: Double): Boolean =
            x >= minX && x < endX && z >= minZ && z < endZ

        override fun label(): String =
            "${dimension.locationId()} · chunks (${minOf(firstX, secondX)}, ${minOf(firstZ, secondZ)}) " +
                "to (${maxOf(firstX, secondX)}, ${maxOf(firstZ, secondZ)})"
    }
}
