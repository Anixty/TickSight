package net.anixty.ticksight.mixin;

//? if >=26.1 {
/*import net.minecraft.resources.Identifier;*/
//?} else {
import net.minecraft.resources.ResourceLocation;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** NeoForge's actual forced-ticket owner, absent on Fabric. No vanilla owner inference. */
@Pseudo
@Mixin(targets = "net.neoforged.neoforge.common.world.chunk.ForcedChunkManager$TicketOwner", remap = false)
public interface NeoForgeTicketOwnerAccessor {
//? if >=26.1 {
/*    @Accessor(value = "id", remap = false) Identifier ticksight$getModId();*/
//?} else {
    @Accessor(value = "id", remap = false) ResourceLocation ticksight$getModId();
//?}
    @Accessor(value = "owner", remap = false) Comparable<?> ticksight$getOwner();
}
