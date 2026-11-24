package net.anixty.ticksight.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.anixty.ticksight.mixin.CommandSourceStackAccessor
import net.minecraft.commands.CommandSource
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.rcon.RconConsoleSource
import net.minecraft.world.level.BaseCommandBlock
import java.util.UUID

/** Identity of the source that started the recording, captured on the server thread. */
@Serializable
data class ProfileOwner(val kind: Kind, val name: String, val uuid: String? = null) {
    internal val playerId: UUID? get() = uuid?.let(UUID::fromString)

    @Serializable
    enum class Kind {
        @SerialName("player") PLAYER,
        @SerialName("console") CONSOLE,
        @SerialName("command_block") COMMAND_BLOCK,
        @SerialName("automation") AUTOMATION
    }

    companion object {
        internal fun player(player: ServerPlayer): ProfileOwner {
            val profile = player.gameProfile
            return ProfileOwner(Kind.PLAYER, profile.name, profile.id.toString())
        }

        internal fun command(stack: CommandSourceStack): ProfileOwner {
            // execute as changes the effective entity while retaining this command source.
            val access = stack as CommandSourceStackAccessor
            val source = access.`ticksight$getCommandSource`()
            //? if >=26.1 {
            /*// The protected nested source is exposed to commands instead of the block itself.
            if (source.javaClass.enclosingClass == BaseCommandBlock::class.java) {
                return ProfileOwner(Kind.COMMAND_BLOCK, "Command block")
            }*/
            //?}
            return when (source) {
                is RconConsoleSource -> ProfileOwner(Kind.CONSOLE, "RCON console")
                is MinecraftServer -> if (access.`ticksight$isSilent`()) ProfileOwner(Kind.AUTOMATION, "Server automation")
                    else ProfileOwner(Kind.CONSOLE, "Server console")
                CommandSource.NULL -> ProfileOwner(Kind.AUTOMATION, "Server automation")
                //? if <26.1 {
                is BaseCommandBlock -> ProfileOwner(Kind.COMMAND_BLOCK,
                    source.name.string.takeUnless { it.isBlank() || it == "@" } ?: "Command block")
                //?}
                //? if >=1.21.4 {
                /*else -> stack.server.playerList.players.firstOrNull { it.commandSource() === source }
                    ?.let(::player) ?: ProfileOwner(Kind.AUTOMATION, "Automation")*/
                //?} else {
                is ServerPlayer -> player(source)
                else -> ProfileOwner(Kind.AUTOMATION, "Automation")
                //?}
            }
        }
    }
}
