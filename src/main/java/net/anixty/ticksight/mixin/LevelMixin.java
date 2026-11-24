package net.anixty.ticksight.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.anixty.ticksight.TickSight;
import net.anixty.ticksight.server.Profiler;
import net.anixty.ticksight.server.ProfilingGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Level.class)
public abstract class LevelMixin {
    @Redirect(method = "tickBlockEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void ticksight$redirectTick(TickingBlockEntity blockEntity) {
        if (ProfilingGate.isRunning() && (Object) this instanceof ServerLevel level) {
            ticksight$profileBlockEntity(blockEntity, level);
        } else {
            blockEntity.tick();
        }
    }

    @Unique
    private void ticksight$profileBlockEntity(TickingBlockEntity blockEntity, ServerLevel level) {
        Profiler profiler = TickSight.INSTANCE.getPROFILER();
        if (!profiler.includes(level, blockEntity.getPos())) {
            blockEntity.tick();
            return;
        }
        Profiler.TimingData data = profiler.processBlockEntity(blockEntity, level);
        int tick = profiler.forTick(data);
        Profiler.TickScope scope = profiler.beginTick(data, tick);
        long start = System.nanoTime();
        try {
            blockEntity.tick();
        } finally {
            long duration = System.nanoTime() - start;
            profiler.endTick(scope);
            data.record(tick, duration);
        }
    }
}
