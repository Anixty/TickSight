@file:UseSerializers(BlockPosSerializer::class, ResourceLocationSerializer::class)

package net.anixty.ticksight.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

class C2SPacket {
    @Serializable data class InitTPSProfile(
        val duration: Int,
        val sample: Boolean,
        val peak: Boolean = false
    )

    @Serializable object RequestAvailability
}
