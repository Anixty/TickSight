package net.anixty.ticksight.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.anixty.ticksight.TickSight;
import net.anixty.ticksight.server.Profiler;
import net.anixty.ticksight.server.ProfilingGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Consumer;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
//? if >=1.21.4 {
/*
    @Redirect(method = "tickFluid", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/material/FluidState;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"))
    private void ticksight$onTickLiquid(FluidState state, ServerLevel level, BlockPos pos, BlockState blockState) {
        if (ProfilingGate.isRunning()) {
            ticksight$profileFluid(state, level, pos, blockState);
        } else {
            state.tick(level, pos, blockState);
        }
    }

    @Unique
    private void ticksight$profileFluid(FluidState state, ServerLevel level, BlockPos pos, BlockState blockState) {
        Profiler profiler = TickSight.INSTANCE.getPROFILER();
        if (!profiler.includes(level, pos)) {
            state.tick(level, pos, blockState);
            return;
        }
        Profiler.TimingData data = profiler.processFluid(state, pos, level);
        int tick = profiler.forTick(data);
        Profiler.TickScope scope = profiler.beginTick(data, tick);
        long start = System.nanoTime();
        try {
            state.tick(level, pos, blockState);
        } finally {
            long duration = System.nanoTime() - start;
            profiler.endTick(scope);
            data.record(tick, duration);
        }
    }
*/
//?} else {
    @Redirect(method = "tickFluid", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/material/FluidState;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private void ticksight$onTickLiquid(FluidState state, Level level, BlockPos pos) {
        if (ProfilingGate.isRunning()) {
            ticksight$profileFluid(state, level, pos);
        } else {
            state.tick(level, pos);
        }
    }

    @Unique
    private void ticksight$profileFluid(FluidState state, Level level, BlockPos pos) {
        Profiler profiler = TickSight.INSTANCE.getPROFILER();
        if (!profiler.includes(level, pos)) {
            state.tick(level, pos);
            return;
        }
        Profiler.TimingData data = profiler.processFluid(state, pos, level);
        int tick = profiler.forTick(data);
        Profiler.TickScope scope = profiler.beginTick(data, tick);
        long start = System.nanoTime();
        try {
            state.tick(level, pos);
        } finally {
            long duration = System.nanoTime() - start;
            profiler.endTick(scope);
            data.record(tick, duration);
        }
    }
//?}
    @Redirect(method = "tickBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void ticksight$onTickBlock(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (ProfilingGate.isRunning()) {
            ticksight$profileBlock(state, level, pos, random);
        } else {
            state.tick(level, pos, random);
        }
    }

    @Unique
    private void ticksight$profileBlock(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        Profiler profiler = TickSight.INSTANCE.getPROFILER();
        if (!profiler.includes(level, pos)) {
            state.tick(level, pos, random);
            return;
        }
        Profiler.TimingData data = profiler.processBlock(state, pos, level);
        int tick = profiler.forTick(data);
        Profiler.TickScope scope = profiler.beginTick(data, tick);
        long start = System.nanoTime();
        try {
            state.tick(level, pos, random);
        } finally {
            long duration = System.nanoTime() - start;
            profiler.endTick(scope);
            data.record(tick, duration);
        }
    }

    @Unique
    private void ticksight$profileEntity(Entity entity, Consumer<Entity> consumer) {
        Profiler profiler = TickSight.INSTANCE.getPROFILER();
        if (!profiler.includes(entity)) {
            consumer.accept(entity);
            return;
        }
        Profiler.TimingData data = profiler.process(entity);
        int tick = profiler.forTick(data);
        Profiler.TickScope scope = profiler.beginTick(data, tick);
        long start = System.nanoTime();
        try {
            consumer.accept(entity);
        } finally {
            long duration = System.nanoTime() - start;
            profiler.endTick(scope);
            data.record(tick, duration);
        }
    }

    @Redirect(method = "tickNonPassenger", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;tick()V"))
    private void ticksight$onTickNonPassenger(Entity entity) {
        if (ProfilingGate.isRunning()) {
            ticksight$profileEntity(entity, Entity::tick);
        } else {
            entity.tick();
        }
    }

    @Redirect(method = "tickPassenger", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;rideTick()V"))
    private void ticksight$onTickPassenger(Entity entity) {
        if (ProfilingGate.isRunning()) {
            ticksight$profileEntity(entity, Entity::rideTick);
        } else {
            entity.rideTick();
        }
    }
}
