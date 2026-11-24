package net.anixty.ticksight.mixin;

//? if <=1.20.1 {
/*import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "net.minecraftforge.common.world.ForgeChunkManager$TicketOwner", remap = false)
public interface ForgeTicketOwnerAccessor {
    @Accessor(value = "modId", remap = false) String ticksight$getModId();
    @Accessor(value = "owner", remap = false) Comparable<?> ticksight$getOwner();
}*/
//?} else {
public interface ForgeTicketOwnerAccessor {}
//?}
