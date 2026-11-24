
//? if >=26.1 {
/*package net.anixty.ticksight.fabric
import net.fabricmc.api.ClientModInitializer
import net.anixty.ticksight.client.TickSightClient
class Client : ClientModInitializer {
    override fun onInitializeClient() { TickSightClient.init() }
}*/
//?} else {
//? if <1.21 {
/*
package net.anixty.ticksight.fabric

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.anixty.ticksight.client.TickSightClient
import net.anixty.ticksight.client.Overlay

class Client : ClientModInitializer {
    override fun onInitializeClient() {
        TickSightClient.init()

        WorldRenderEvents.END.register {
            Overlay.render(it.matrixStack(), it.tickDelta(), it.projectionMatrix())
        }
    }
}
*/
//?} else {
package net.anixty.ticksight.fabric

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.anixty.ticksight.client.TickSightClient
import net.anixty.ticksight.client.Overlay

class Client : ClientModInitializer {
    override fun onInitializeClient() {
        TickSightClient.init()

        WorldRenderEvents.LAST.register {
            val stack = it.matrixStack() ?: return@register
            val projection = it.projectionMatrix()
            Overlay.render(stack, it.tickCounter().getGameTimeDeltaPartialTick(true), projection)
        }
    }
}
//?}
//?}
