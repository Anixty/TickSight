package net.anixty.ticksight.server

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.architectury.utils.GameInstance
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.DimensionArgument
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.world.phys.Vec3
import net.anixty.ticksight.TickSight
import net.anixty.ticksight.net.S2CPacket
import net.anixty.ticksight.util.MOD_URL_COMPONENT

val TICKSIGHT_COMMAND
    get() =
        Commands.literal("ticksight")
            .requires {
                //? if >=26.1 {
                /*it.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_OWNER)*/
                //?} else {
                it.hasPermission(4)
                //?}
            }
            .executes { ctx ->
                ctx.source.sendSuccess({ serverText("text.ticksight.cmd", MOD_URL_COMPONENT) }, false)
                1
            }
            .then(RunCommand().build())
            .then(configureCommand())
            .then(helpCommand())
            .then(
                Commands.literal("stop").executes { ctx ->
                    if (TickSight.PROFILER.stop()) {
                        ctx.source.sendSuccess({ serverText("text.ticksight.profile_stopping") }, false)
                        1
                    } else {
                        ctx.source.sendFailure(serverText("text.ticksight.profile_not_running"))
                        0
                    }
                }
            )
            .then(
                Commands.literal("allow")
                    .then(
                        Commands.argument("player", GameProfileArgument.gameProfile()).executes { ctx ->
                            GameProfileArgument.getGameProfiles(ctx, "player").forEach { player ->
                                ServerSettings.allowedPlayers.add(player.id.toString())
                                GameInstance.getServer()?.playerList?.getPlayer(player.id)?.let {
                                    TickSight.CHANNEL.sendToPlayer(it, S2CPacket.Availability.Available)
                                }
                            }
                            ServerSettings.sync()
                            1
                        }
                    )
            )
            .then(
                Commands.literal("deny")
                    .then(
                        Commands.argument("player", GameProfileArgument.gameProfile()).executes { ctx ->
                            GameProfileArgument.getGameProfiles(ctx, "player").forEach { player ->
                                ServerSettings.allowedPlayers.remove(player.id.toString())
                                GameInstance.getServer()?.playerList?.getPlayer(player.id)?.let {
                                    TickSight.CHANNEL.sendToPlayer(it, S2CPacket.Availability.NoPermissions)
                                }
                            }
                            ServerSettings.sync()
                            1
                        }
                    )
            )
            .then(settingsCommand())
            .then(
                Commands.literal("tp")
                    .then(
                        Commands.argument("dim", DimensionArgument.dimension())
                            .then(
                                Commands.literal("entity")
                                    .then(
                                        Commands.argument("id", IntegerArgumentType.integer()).executes { ctx ->
                                            val level = DimensionArgument.getDimension(ctx, "dim")
                                            val id = IntegerArgumentType.getInteger(ctx, "id")
                                            val pos =
                                                level.getEntity(id)?.position()
                                                    ?: run {
                                                        ctx.source.sendFailure(serverText("text.ticksight.entity_not_found"))
                                                        return@executes 0
                                                    }
                                            teleport(ctx, pos)
                                            1
                                        }
                                    )
                            )
                            .then(
                                Commands.literal("position")
                                    .then(
                                        Commands.argument("pos", Vec3Argument.vec3()).executes { ctx ->
                                            teleport(ctx, Vec3Argument.getVec3(ctx, "pos"))
                                            1
                                        }
                                    )
                            )
                    )
            )
            .then(
                Commands.literal("reset").executes { ctx ->
                    resetSettings()
                    refreshAvailability()
                    ctx.source.sendSuccess({ serverText("text.ticksight.settings_reset") }, false)
                    1
                }
            )

fun teleport(ctx: CommandContext<CommandSourceStack>, pos: Vec3) {
    val player = ctx.source.playerOrException
    val level = DimensionArgument.getDimension(ctx, "dim")

//? if >=1.21.4 {
/*
    player.teleportTo(level, pos.x, pos.y, pos.z, setOf(), 0F, 0F, true)
*/
//?} else {
    player.teleportTo(pos.x, pos.y, pos.z)
    if (level == player.level()) {
        player.connection.teleport(pos.x, pos.y, pos.z, 0F, 0F, setOf())
    } else {
        player.teleportTo(level, pos.x, pos.y, pos.z, 0F, 0F)
    }
//?}
    TickSight.LOGGER.info("Moved ${player.gameProfile.name} to (${pos.x}, ${pos.y}, ${pos.z}) in $level")
}
