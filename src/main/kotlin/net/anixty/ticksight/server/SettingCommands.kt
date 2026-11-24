package net.anixty.ticksight.server

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.context.CommandContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.anixty.ticksight.util.suggestCommand
import net.anixty.ticksight.TickSight
import net.anixty.ticksight.net.S2CPacket
import dev.architectury.utils.GameInstance
import java.math.BigDecimal
import java.net.URI
import java.util.UUID
import kotlin.reflect.KMutableProperty1

private val defaultSettings = ServerSettingsData()

/** Each editable setting has exactly one typed declaration; no reflective field mutation. */
internal class SettingSpec<T : Any>(
    val property: KMutableProperty1<ServerSettingsData, T>,
    val argument: ArgumentType<*>,
    val valueClass: Class<*>,
    val section: String,
    val parse: (Any) -> T,
    val display: (T) -> String = { it.toString() },
    val commandValue: (T) -> String = display
) {
    val name = property.name
    fun defaultCommand() = "/ticksight set $name ${commandValue(property.get(defaultSettings))}"
    fun current() = display(property.get(ServerSettings))
    fun defaultValue() = display(property.get(defaultSettings))
    fun node() = Commands.literal(name).then(Commands.argument("value", argument)
        .suggests { _, builder -> SharedSuggestionProvider.suggest(listOf(commandValue(property.get(defaultSettings))), builder) }
        .executes { ctx -> save(ctx) })

    private fun save(ctx: CommandContext<CommandSourceStack>): Int = try {
        val value = parse(ctx.getArgument("value", valueClass))
        val candidate = ServerSettings.copy(allowedPlayers = ServerSettings.allowedPlayers.toMutableSet())
        property.set(candidate, value)
        // Persist before publishing: a failed write leaves the running settings unchanged.
        candidate.sync()
        ServerSettings = candidate
        if (name == "allPlayersAllowed" || name == "allowedPlayers") refreshAvailability()
        ctx.source.sendSuccess({ serverText("text.ticksight.setting_saved", name, current()) }, false)
        1
    } catch (error: IllegalArgumentException) {
        ctx.source.sendFailure(serverText("text.ticksight.setting_invalid", name, error.message ?: "Invalid value"))
        0
    } catch (error: java.io.IOException) {
        ctx.source.sendFailure(serverText("text.ticksight.setting_write_failed", name, error.message ?: "I/O error"))
        0
    }
}

private fun integer(property: KMutableProperty1<ServerSettingsData, Int>, min: Int, section: String) =
    SettingSpec(property, IntegerArgumentType.integer(min), Int::class.javaObjectType, section, { it as Int })

private fun samplingDelay(property: KMutableProperty1<ServerSettingsData, Double>, min: Double) =
    SettingSpec(property, DoubleArgumentType.doubleArg(min, MAX_SAMPLING_DELAY_MS), Double::class.javaObjectType, "sampling",
        { it as Double }, { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() })

private fun boolean(property: KMutableProperty1<ServerSettingsData, Boolean>, section: String) =
    SettingSpec(property, BoolArgumentType.bool(), Boolean::class.javaObjectType, section, { it as Boolean })

internal val settingSpecs = listOf(
    samplingDelay(ServerSettingsData::traceInterval, MIN_SAMPLING_INTERVAL_MS),
    samplingDelay(ServerSettingsData::deviation, 0.0),
    boolean(ServerSettingsData::allPlayersAllowed, "access"),
    SettingSpec(ServerSettingsData::allowedPlayers, StringArgumentType.string(), String::class.java, "access", { raw ->
        val values = Json.decodeFromString<List<String>>(raw as String)
        values.map { value ->
            require(value.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))) { "Use a JSON array of player UUIDs." }
            UUID.fromString(value).toString()
        }.toMutableSet()
    }, { Json.encodeToString(it.toList().sorted()) }, { Json.encodeToString(Json.encodeToString(it.toList().sorted())) }),
    boolean(ServerSettingsData::includeJvmArgs, "uploads"),
    SettingSpec(ServerSettingsData::uploadURL, StringArgumentType.string(), String::class.java, "uploads", { raw ->
        val value = raw as String
        if (value.isNotEmpty()) {
            val uri = URI.create(value)
            require(uri.isAbsolute && uri.scheme.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.fragment == null) {
                "Use an absolute http(s) URL, or an empty quoted string to disable uploads."
            }
        }
        value
    }, { Json.encodeToString(it) }, { Json.encodeToString(it) }),
    integer(ServerSettingsData::callTraceTickLimitMs, 1, "calls"),
    integer(ServerSettingsData::callTraceMaxRepeats, -1, "calls"),
    integer(ServerSettingsData::callTraceHardTickLimitMs, 1, "calls"),
    integer(ServerSettingsData::callTraceMemoryLimitMiB, 1, "calls"),
    integer(ServerSettingsData::callTraceHeapReserveMiB, 0, "calls")
)

internal fun settingsCommand() = Commands.literal("set").also { root -> settingSpecs.forEach { root.then(it.node()) } }

internal fun refreshAvailability() {
    GameInstance.getServer()?.playerList?.players?.forEach { player ->
        TickSight.CHANNEL.sendToPlayer(player, if (TickSight.hasPermission(player))
            S2CPacket.Availability.Available else S2CPacket.Availability.NoPermissions)
    }
}

internal fun configureCommand() = Commands.literal("configure").executes { ctx ->
    ctx.source.sendSuccess({ serverText("text.ticksight.configure_title").copy().withStyle(ChatFormatting.GOLD) }, false)
    for (section in listOf("sampling", "access", "uploads", "calls")) {
        ctx.source.sendSuccess({ serverText("text.ticksight.settings_section_$section").copy().withStyle(ChatFormatting.AQUA) }, false)
        for (setting in settingSpecs.filter { it.section == section }) {
            val hover = serverText("text.ticksight.setting_${setting.name}").copy()
                .append(Component.literal("\n"))
                .append(serverText("text.ticksight.setting_default", setting.defaultValue()))
                .append(Component.literal("\n"))
                .append(serverText("text.ticksight.configure_hint"))
            val row = Component.literal("${setting.name} = ${setting.current()} ")
                .append(serverText("text.ticksight.setting_unit_${setting.name}"))
            ctx.source.sendSuccess({ suggestCommand(row, setting.defaultCommand(), hover) }, false)
        }
    }
    1
}
