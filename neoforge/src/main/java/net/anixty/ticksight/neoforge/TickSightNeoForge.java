package net.anixty.ticksight.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.anixty.ticksight.TickSight;
import net.anixty.ticksight.client.TickSightClient;
import static net.anixty.ticksight.TickSight.init;
import net.anixty.ticksight.server.ModLoader;
import net.anixty.ticksight.server.Remapper;

@Mod(TickSight.MOD_ID)
public class TickSightNeoForge {
    public TickSightNeoForge() {
        Remapper.modLoader = ModLoader.FORGE;
        NeoForgeTelemetry.init();

        init();

        if (

//? if >=26.1 {
            /*FMLEnvironment.getDist() == Dist.CLIENT*/
//?} else {
            FMLEnvironment.dist == Dist.CLIENT
            //?}
        ) {
            onClientInit();
        }
    }

    public void onClientInit() {
        TickSightClient.init();

//? if <26.1 {
        NeoForge.EVENT_BUS.register(NeoForgeClientHooks.INSTANCE);
//?}

    }
}
