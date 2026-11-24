package net.anixty.ticksight.forge;

import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.anixty.ticksight.TickSight;
import net.anixty.ticksight.client.TickSightClient;
import static net.anixty.ticksight.TickSight.init;
import net.anixty.ticksight.server.ModLoader;
import net.anixty.ticksight.server.Remapper;

import java.util.Objects;

@Mod(TickSight.MOD_ID)
public class TickSightForge {
    public TickSightForge() {
        Remapper.modLoader = ModLoader.FORGE;
        net.anixty.ticksight.server.ChunkTelemetryPlatform.ticketForceTicks =
            ticket -> ((net.minecraft.server.level.Ticket<?>) ticket).isForceTicks();
        net.anixty.ticksight.server.ChunkTelemetryPlatform.chunkForceTicks =
            (manager, position) -> manager.shouldForceTicks(position);
        // Submit our event bus to let architectury register our content on the right time
        EventBuses.registerModEventBus(TickSight.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientInit);

        init();
    }

    public void onClientInit(FMLClientSetupEvent ev) {
        TickSightClient.init();
        MinecraftForge.EVENT_BUS.register(ForgeClientHooks.INSTANCE);
    }
}
