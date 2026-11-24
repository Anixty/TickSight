package net.anixty.ticksight.mixin;

//? if <26.1 {
import net.minecraft.server.level.Ticket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Ticket.class)
public interface TicketAccessor {
    @Accessor("key") Object ticksight$getKey();
}
//?} else {
/*public interface TicketAccessor {}*/
//?}
