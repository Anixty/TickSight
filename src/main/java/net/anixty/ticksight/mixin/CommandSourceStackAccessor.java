package net.anixty.ticksight.mixin;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CommandSourceStack.class)
public interface CommandSourceStackAccessor {
    @Accessor("source")
    CommandSource ticksight$getCommandSource();

    @Accessor("silent")
    boolean ticksight$isSilent();
}
