package net.anixty.ticksight.fabric

import net.fabricmc.api.ModInitializer
import net.anixty.ticksight.TickSight
import net.anixty.ticksight.server.ModLoader
import net.anixty.ticksight.server.Remapper

class TickSightFabric : ModInitializer {
    override fun onInitialize() {
        Remapper.modLoader = ModLoader.FABRIC
        TickSight.init()
    }
}
