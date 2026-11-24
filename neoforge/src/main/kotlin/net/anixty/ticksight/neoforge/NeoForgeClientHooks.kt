
//? if <26.1 {
package net.anixty.ticksight.neoforge

import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.anixty.ticksight.client.Overlay

object NeoForgeClientHooks {
    @SubscribeEvent
    fun onRender(ev: RenderLevelStageEvent) {
        if (ev.stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            Overlay.render(ev.poseStack, ev.partialTick.getGameTimeDeltaPartialTick(true), ev.projectionMatrix)
        }
    }
}

//?}
