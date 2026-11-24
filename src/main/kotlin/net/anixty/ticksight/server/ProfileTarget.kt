package net.anixty.ticksight.server

import net.anixty.ticksight.util.locationId
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level

/** Selects ticking objects independently of how their work is recorded. */
internal sealed interface ProfileTarget {
    fun label(): String

    data object AllObjects : ProfileTarget {
        override fun label(): String = "All ticking objects"
    }

    data class EntityTarget(val entity: Entity) : ProfileTarget {
        override fun label(): String =
            "${BuiltInRegistries.ENTITY_TYPE.getKey(entity.type)} (entity ${entity.id}) in ${entity.level().dimension().locationId()}"
    }

    data class Block(val dimension: ResourceKey<Level>, val pos: BlockPos) : ProfileTarget {
        override fun label(): String = "${dimension.locationId()} · (${pos.x}, ${pos.y}, ${pos.z})"
    }
}
