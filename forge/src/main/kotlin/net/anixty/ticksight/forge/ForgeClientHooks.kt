package net.anixty.ticksight.forge

import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.anixty.ticksight.client.Overlay

object ForgeClientHooks {
    @SubscribeEvent
    fun onRender(ev: RenderLevelStageEvent) {
        if (ev.stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            Overlay.render(ev.poseStack, ev.partialTick, ev.projectionMatrix)
        }
    }
}

