package net.anixty.ticksight

import dev.architectury.event.events.common.CommandRegistrationEvent
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.utils.GameInstance
//? if >=26.1 {
/*
import net.minecraft.resources.Identifier as ResourceLocation
*/
//?} else {
import net.minecraft.resources.ResourceLocation
//?}
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.anixty.ticksight.net.BetterChannel
import net.anixty.ticksight.net.C2SPacket
import net.anixty.ticksight.net.S2CPacket
import net.anixty.ticksight.server.TICKSIGHT_COMMAND
import net.anixty.ticksight.server.Profiler
import net.anixty.ticksight.server.ProfilingGate
import net.anixty.ticksight.server.ServerSettings
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

object TickSight {
    const val MOD_ID = "ticksight"

    val CHANNEL = BetterChannel(

//? if <1.21 {
        /*ResourceLocation("ticksight", "channel")*/
//?} else {
        ResourceLocation.fromNamespaceAndPath("ticksight", "channel")
        //?}
    )
    val LOGGER: Logger = LogManager.getLogger("TickSight")
    private val profiler = lazy(::Profiler)
    val PROFILER: Profiler by profiler
    internal var handleClientPacket: (S2CPacket) -> Unit = {}

    fun hasPermission(player: Player): Boolean {
        if (ServerSettings.allPlayersAllowed) return true
        if (ServerSettings.allowedPlayers.contains(player.gameProfile.id.toString())) return true
        val server = GameInstance.getServer() ?: return false
        return isOperator(server, player) || server.isSingleplayer
    }

    internal fun isOperator(server: MinecraftServer, player: Player): Boolean =
        server.playerList.isOp(
            //? if >=26.1 {
            /*net.minecraft.server.players.NameAndId(player.gameProfile)*/
            //?} else {
            player.gameProfile
            //?}
        )

    @JvmStatic
    fun init() {
        // Load/persist missing defaults at startup, independently of command/UI usage.
        ServerSettings.sync()
        CHANNEL.register { t: C2SPacket.InitTPSProfile, ctx ->
            ctx.queue {
                val player = ctx.player as? ServerPlayer ?: return@queue
                val server = GameInstance.getServer() ?: return@queue
                if (!hasPermission(player)) {
                    LOGGER.info("${player.name.contents} lacks permissions to start profiling")
                    return@queue
                }
                if (PROFILER.runWithDuration(server, player, t.duration, t.sample, t.peak)) {
                    LOGGER.info("${player.gameProfile.name} started profiler for ${t.duration} s")
                }
            }
        }

        CHANNEL.register { _: C2SPacket.RequestAvailability, ctx ->
            (ctx.player as? ServerPlayer)?.let {
                CHANNEL.sendToPlayer(
                    it,
                    if (hasPermission(it)) {
                        S2CPacket.Availability.Available
                    } else {
                        S2CPacket.Availability.NoPermissions
                    }
                )
            }
        }

        // Both ends register packet types. Only the client installs UI handlers.
        CHANNEL.register { packet: S2CPacket.ProfilingStarted, _ -> handleClientPacket(packet) }
        CHANNEL.register { packet: S2CPacket.ProfilingCompleted, _ -> handleClientPacket(packet) }
        CHANNEL.register { packet: S2CPacket.ProfilerInactive, _ -> handleClientPacket(packet) }
        CHANNEL.register { packet: S2CPacket.ProfilingResult, _ -> handleClientPacket(packet) }
        CHANNEL.register { packet: S2CPacket.Availability, _ -> handleClientPacket(packet) }

        TickEvent.SERVER_PRE.register { server ->
            if (ProfilingGate.isRunning()) PROFILER.beforeTick(server)
        }

        TickEvent.SERVER_POST.register { server ->
            if (profiler.isInitialized()) PROFILER.afterTick(server)
        }

        LifecycleEvent.SERVER_STOPPING.register {
            if (profiler.isInitialized()) PROFILER.cancel()
        }

        CommandRegistrationEvent.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(TICKSIGHT_COMMAND)
        }
    }

}
