package net.anixty.ticksight.server

import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.anixty.ticksight.util.suggestCommand

private val helpTopics = listOf("run", "scopes", "calls", "settings", "commands")
private val examples = mapOf(
    "run" to listOf("/ticksight run 3", "/ticksight run 60t peak sample", "/ticksight run 5 no-layers players visibility", "/ticksight run 6t box ~-8 ~-8 ~-8 ~8 ~8 ~8 peak calls"),
    "scopes" to listOf("/ticksight run 3s radius 1c circle at ~ ~ ~ sample", "/ticksight run 3s radius 8b sphere", "/ticksight run 6t chunks -1 0 to 0 1 peak calls", "/ticksight run 3s in minecraft:the_nether", "/ticksight run 20t entity @e[type=minecraft:armor_stand,limit=1] sample", "/ticksight run 6t block ~ ~-1 ~ calls full"),
    "calls" to listOf("/ticksight run 6t calls peak", "/ticksight run 6t entity @e[type=minecraft:armor_stand,limit=1] calls full", "/ticksight run 6t in minecraft:overworld block ~ ~-1 ~ calls full"),
    "settings" to listOf("/ticksight configure", "/ticksight set traceInterval 0.1", "/ticksight set allowedPlayers \"[]\"", "/ticksight set uploadURL \"\""),
    "commands" to listOf("/ticksight stop", "/ticksight configure", "/ticksight set callTraceMaxRepeats 128", "/ticksight allow @s", "/ticksight deny @s", "/ticksight tp minecraft:overworld position ~ ~ ~", "/ticksight reset")
)

internal fun helpCommand() = Commands.literal("help").executes { ctx -> showHelp(ctx.source, null) }.also { root ->
    helpTopics.forEach { topic -> root.then(Commands.literal(topic).executes { ctx -> showHelp(ctx.source, topic) }) }
    RunOption.entries.filter { it.keyword !in helpTopics }.forEach { option -> root.then(Commands.literal(option.keyword).executes { ctx ->
        ctx.source.sendSuccess({ Component.literal(option.syntax + " — ").append(option.description()) }, false)
        1
    }) }
}

private fun showHelp(source: CommandSourceStack, topic: String?): Int {
    fun text(key: String) = source.sendSuccess({ serverText("text.ticksight.$key") }, false)
    source.sendSuccess({ Component.literal("TickSight / ${topic ?: "help"}").withStyle(ChatFormatting.GOLD) }, false)
    if (topic == null) {
        text("help_intro")
        helpTopics.forEach { name -> source.sendSuccess({ suggestCommand(
            Component.literal("/ticksight help $name — ").append(serverText("text.ticksight.help_topic_$name")),
            "/ticksight help $name", serverText("text.ticksight.help_click")) }, false) }
        text("help_commands_short")
    } else {
        text("help_$topic")
        if (topic == "run" || topic == "scopes" || topic == "calls") {
            val options = when (topic) {
                "scopes" -> RunOption.entries.filter { it.scope || it.prerequisite?.scope == true || it == RunOption.IN }
                "calls" -> listOf(RunOption.CALLS, RunOption.FULL, RunOption.PEAK)
                else -> RunOption.entries.filter { !it.scope && it.layer == null }
            }
            options.forEach { option -> source.sendSuccess({ Component.literal(option.syntax + " — ").append(option.description()) }, false) }
        }
        examples[topic]?.forEach { example -> source.sendSuccess({ suggestCommand(Component.literal(example).withStyle(ChatFormatting.GREEN),
            example, serverText("text.ticksight.help_click")) }, false) }
    }
    return 1
}
