package net.anixty.ticksight.server

import com.mojang.brigadier.Command
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.context.CommandContextBuilder
import com.mojang.brigadier.context.StringRange
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestion
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.ArgumentCommandNode
import com.mojang.brigadier.tree.CommandNode
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.DimensionArgument
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.anixty.ticksight.TickSight
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture

/** The grammar, help topics and compatibility rules share this option catalogue. */
internal enum class RunOption(
    val keyword: String,
    val syntax: String,
    val scope: Boolean = false,
    val flag: Boolean = false,
    private val group: Group? = null,
    val disables: Set<RecordedLayer> = emptySet(),
    val layer: RecordedLayer? = null
) {
    SAMPLE("sample", "sample", flag = true, group = Group.COLLECTION),
    PEAK("peak", "peak", flag = true),
    CALLS("calls", "calls", flag = true, group = Group.COLLECTION),
    FULL("full", "full", flag = true),
    NO_TICKETS("no-tickets", "no-tickets", flag = true, disables = setOf(RecordedLayer.TICKET_LEVELS, RecordedLayer.TICKET_SOURCES)),
    NO_RUNTIME("no-runtime", "no-runtime", flag = true, disables = setOf(RecordedLayer.RUNTIME)),
    NO_GENERATION("no-generation", "no-generation", flag = true, disables = setOf(RecordedLayer.GENERATION)),
    NO_TICKET_LEVELS("no-ticket-levels", "no-ticket-levels", flag = true, disables = setOf(RecordedLayer.TICKET_LEVELS)),
    NO_TICKET_SOURCES("no-ticket-sources", "no-ticket-sources", flag = true, disables = setOf(RecordedLayer.TICKET_SOURCES)),
    NO_VISIBILITY("no-visibility", "no-visibility", flag = true, disables = setOf(RecordedLayer.VISIBILITY)),
    NO_PLAYERS("no-players", "no-players", flag = true, disables = setOf(RecordedLayer.PLAYERS)),
    NO_LAYERS("no-layers", "no-layers [runtime generation ticket-levels ticket-sources visibility players]", flag = true, disables = RecordedLayer.entries.toSet()),
    LAYER_RUNTIME("runtime", "runtime", flag = true, layer = RecordedLayer.RUNTIME),
    LAYER_GENERATION("generation", "generation", flag = true, layer = RecordedLayer.GENERATION),
    LAYER_TICKET_LEVELS("ticket-levels", "ticket-levels", flag = true, layer = RecordedLayer.TICKET_LEVELS),
    LAYER_TICKET_SOURCES("ticket-sources", "ticket-sources", flag = true, layer = RecordedLayer.TICKET_SOURCES),
    LAYER_VISIBILITY("visibility", "visibility", flag = true, layer = RecordedLayer.VISIBILITY),
    LAYER_PLAYERS("players", "players", flag = true, layer = RecordedLayer.PLAYERS),
    CIRCLE("circle", "circle", flag = true, group = Group.RADIUS_SHAPE),
    SPHERE("sphere", "sphere", flag = true, group = Group.RADIUS_SHAPE),
    IN("in", "in <dimension>"),
    RADIUS("radius", "radius <distance[b|c]> <circle|sphere> [at <x y z>]", true),
    BOX("box", "box <from x y z> <to x y z>", true),
    CHUNKS("chunks", "chunks <x z> [to <x z>]", true),
    ENTITY("entity", "entity <uuid|selector|numericId>", true),
    BLOCK("block", "block <x y z>", true),
    AT("at", "at <x y z>"), TO("to", "to <chunk x z>");

    private enum class Group { COLLECTION, RADIUS_SHAPE }

    val prerequisite: RunOption?
        get() = when (this) {
            FULL -> CALLS
            CIRCLE, SPHERE, AT -> RADIUS
            TO -> CHUNKS
            else -> null
        }

    fun conflictsWith(other: RunOption) = this != other && (scope && other.scope || group != null && group == other.group)

    fun canSuggest(seen: Set<RunOption>) =
        layer == null && this !in seen && seen.none(::conflictsWith) && (prerequisite?.let { it in seen } ?: true) &&
            (disables.isEmpty() || !seen.flatMap { it.disables }.containsAll(disables))

    fun missingPrerequisite() = when (this) {
        FULL -> serverText("text.ticksight.run_full_requires_calls")
        AT, TO -> serverText("text.ticksight.run_structural_order", keyword, checkNotNull(prerequisite).keyword)
        else -> serverText("text.ticksight.run_requires", keyword, checkNotNull(prerequisite).keyword)
    }

    fun conflictMessage(other: RunOption) = when (group) {
        Group.COLLECTION -> serverText("text.ticksight.run_modes_conflict")
        Group.RADIUS_SHAPE -> serverText("text.ticksight.run_radius_shape_conflict")
        null -> serverText("text.ticksight.run_scopes_conflict", "$keyword, ${other.keyword}")
    }

    fun description() = serverText("text.ticksight.run_option_$keyword")
}

private val durationSyntax = Regex("([0-9]+)([st]?)")
private val radiusSyntax = Regex("""([0-9]+(?:\.[0-9]*)?|\.[0-9]+)([bc]?)""")
private val optionsByKeyword = RunOption.entries.associateBy { it.keyword }

private data class RunStructure(
    val scope: RunOption? = null,
    val dimensionChosen: Boolean = false,
    val continuationChosen: Boolean = false,
    val layersChosen: Boolean = false
) {
    fun allows(option: RunOption): Boolean = when (option) {
        RunOption.IN -> !dimensionChosen
        RunOption.AT, RunOption.TO -> !continuationChosen && scope == option.prerequisite
        else -> option.scope && scope == null
    }

    fun after(option: RunOption) = when (option) {
        RunOption.IN -> copy(dimensionChosen = true)
        RunOption.AT, RunOption.TO -> copy(continuationChosen = true)
        else -> copy(scope = option)
    }

    // Allowed structural keywords have their own literal. The word node can
    // report a rejected keyword at its start, even when more arguments follow.
    fun reject(word: String): Component? {
        val option = optionsByKeyword[word] ?: return serverText("text.ticksight.run_unknown_option", word)
        if (option.layer != null) return serverText("text.ticksight.run_layer_position", word)
        if (option == RunOption.NO_LAYERS) return duplicateOption(option)
        if (option.flag) return null
        return when {
            scope != null && scope.conflictsWith(option) -> scope.conflictMessage(option)
            option.prerequisite != null && scope != option.prerequisite -> option.missingPrerequisite()
            else -> duplicateOption(option)
        }
    }
}

// Minecraft copies and serializes this node as the ordinary word argument.
// Only server parsing adds the structural error, with no extra argument type.
private class ModifierNode(
    name: String,
    command: Command<CommandSourceStack>,
    redirect: CommandNode<CommandSourceStack>?,
    suggestions: SuggestionProvider<CommandSourceStack>,
    private val structure: RunStructure
) : ArgumentCommandNode<CommandSourceStack, String>(
    name, StringArgumentType.word(), command, { true }, redirect, null, false, suggestions
) {
    override fun parse(reader: StringReader, context: CommandContextBuilder<CommandSourceStack>) {
        val start = reader.cursor
        super.parse(reader, context)
        val error = structure.reject(reader.string.substring(start, reader.cursor)) ?: return
        reader.cursor = start
        throw SimpleCommandExceptionType(error).createWithContext(reader)
    }
}

/** The native list stays open only through this node's own redirect. Other options leave it. */
private class LayerNode(
    command: Command<CommandSourceStack>, redirect: CommandNode<CommandSourceStack>, suggestions: SuggestionProvider<CommandSourceStack>
) : ArgumentCommandNode<CommandSourceStack, String>("layer", StringArgumentType.word(), command, { true }, redirect, null, false, suggestions) {
    override fun parse(reader: StringReader, context: CommandContextBuilder<CommandSourceStack>) {
        val start = reader.cursor
        super.parse(reader, context)
        val word = reader.string.substring(start, reader.cursor)
        if (optionsByKeyword[word]?.layer != null) return
        reader.cursor = start
        throw SimpleCommandExceptionType(serverText("text.ticksight.run_layer_expected")).createWithContext(reader)
    }
}

private class LayerGroupNode(command: Command<CommandSourceStack>, suggestions: SuggestionProvider<CommandSourceStack>) :
    ArgumentCommandNode<CommandSourceStack, String>("layers", StringArgumentType.word(), command, { true }, null, null, false, suggestions) {
    override fun parse(reader: StringReader, context: CommandContextBuilder<CommandSourceStack>) {
        val start = reader.cursor
        super.parse(reader, context)
        if (reader.string.substring(start, reader.cursor) == "no-layers") return
        reader.cursor = start
        throw SimpleCommandExceptionType(serverText("text.ticksight.run_layer_group_expected")).createWithContext(reader)
    }
}

/** Structural options advance through native argument nodes. Flags return to the current state. */
internal class RunCommand {
    private val structuralOptions = RunOption.entries.filterNot { it.flag }
    private val completedOptions = IdentityHashMap<CommandNode<CommandSourceStack>, RunOption>()
    private val duration: CommandNode<CommandSourceStack> = Commands.argument("duration", StringArgumentType.word())
        .suggests { _, builder -> suggestDuration(builder) }.executes(::start).build()

    fun build(): ArgumentBuilder<CommandSourceStack, *> {
        addContinuations(duration, RunStructure())
        return Commands.literal("run").then(duration)
    }

    private fun addContinuations(anchor: CommandNode<CommandSourceStack>, structure: RunStructure) {
        // Each node owns a distinct native word argument, which participates in
        // packet node equality even though the redirect itself does not.
        anchor.addChild(ModifierNode("modifier", ::start, anchor, ::suggestModifiers, structure))
        for (option in structuralOptions) {
            if (structure.allows(option)) anchor.addChild(optionNode(option, structure.after(option)).build())
        }
        if (!structure.layersChosen) {
            val closedStructure = structure.copy(layersChosen = true)
            // A native word keeps server-owned availability suggestions; a literal would be
            // proposed locally even when preceding negative flags already disabled all layers.
            val group = LayerGroupNode(::start, ::suggestNoLayers)
            completedOptions[group] = RunOption.NO_LAYERS
            // The first ordinary flag closes the list; its continuations never offer positive layers.
            val closed = ModifierNode("modifier", ::start, null, ::suggestModifiers, closedStructure)
            addContinuations(closed, closedStructure)
            group.addChild(closed)
            // Structural options also leave the list, sharing the same native argument subtrees.
            closed.children.filter { it !is ModifierNode }.forEach(group::addChild)
            group.addChild(LayerNode(::start, group, ::suggestLayers))
            anchor.addChild(group)
        }
    }

    private fun optionNode(option: RunOption, next: RunStructure): ArgumentBuilder<CommandSourceStack, *> {
        fun finish(argument: ArgumentBuilder<CommandSourceStack, *>): CommandNode<CommandSourceStack> =
            argument.executes(::start).build().also {
                completedOptions[it] = option
                addContinuations(it, next)
            }
        val literal = Commands.literal(option.keyword)
        return when (option) {
            RunOption.IN -> literal.then(finish(Commands.argument("dimension", DimensionArgument.dimension())))
            RunOption.ENTITY -> literal
                .then(finish(Commands.argument("id", IntegerArgumentType.integer(0))))
                .then(finish(Commands.argument("target", EntityArgument.entity())))
            RunOption.BLOCK -> literal.then(finish(Commands.argument("pos", BlockPosArgument.blockPos()).suggests(::suggestBlockPosition)))
            RunOption.BOX -> literal.then(Commands.argument("from", BlockPosArgument.blockPos())
                .then(finish(Commands.argument("to", BlockPosArgument.blockPos()))))
            RunOption.RADIUS -> literal.then(finish(Commands.argument("radius", StringArgumentType.word())
                .suggests { _, builder -> suggestRadius(builder) }))
            RunOption.CHUNKS -> literal.then(Commands.argument("chunkX", IntegerArgumentType.integer())
                .then(finish(Commands.argument("chunkZ", IntegerArgumentType.integer()))))
            RunOption.AT -> literal.then(finish(Commands.argument("center", Vec3Argument.vec3(false))))
            RunOption.TO -> literal.then(Commands.argument("toChunkX", IntegerArgumentType.integer())
                .then(finish(Commands.argument("toChunkZ", IntegerArgumentType.integer()))))
            else -> error("Flags use the native modifier argument")
        }
    }

    private fun suggestModifiers(ctx: CommandContext<CommandSourceStack>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val completed = options(contexts(ctx, input = ctx.input.substring(0, builder.start))).map { it.first }.toSet()
        val flags = RunOption.entries.filter { it.flag && it != RunOption.NO_LAYERS && it.canSuggest(completed) }
        return suggestWithTooltips(builder, flags.map { it.keyword to it.description() })
    }

    private fun suggestLayers(ctx: CommandContext<CommandSourceStack>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val completed = options(contexts(ctx, input = ctx.input.substring(0, builder.start))).map { it.first }.toSet()
        val disabled = completed.filter { it != RunOption.NO_LAYERS }.flatMap { it.disables }.toSet()
        val choices = RunOption.entries.filter { it.layer != null && it !in completed && it.layer !in disabled }
        return suggestWithTooltips(builder, choices.map { it.keyword to it.description() })
    }

    private fun suggestNoLayers(ctx: CommandContext<CommandSourceStack>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val completed = options(contexts(ctx, input = ctx.input.substring(0, builder.start))).map { it.first }.toSet()
        val option = RunOption.NO_LAYERS
        return suggestWithTooltips(builder, if (option.canSuggest(completed)) listOf(option.keyword to option.description()) else emptyList())
    }

    /**
     * Brigadier execution only receives the final redirect context. Reparse (never execute)
     * the original input and recover our exact node by identity, including inside execute.
     * This retains native Coordinates/EntitySelector values and quoted argument boundaries.
     */
    private fun contexts(ctx: CommandContext<CommandSourceStack>, complete: Boolean = false, input: String = ctx.input): List<CommandContext<CommandSourceStack>> {
        val result = mutableListOf<CommandContext<CommandSourceStack>>()
        val reader = StringReader(input)
        // Completion packets retain '/', while executed command input normally omits it.
        if (reader.canRead() && reader.peek() == '/') reader.skip()
        // A function is authorized when Minecraft compiles it. Its runtime source may have
        // fewer permissions. Use the command's permission for this structural parse alone.
        val parseSource = if (complete) {
            //? if >=26.1 {
            /*ctx.source.withMaximumPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER)*/
            //?} else {
            ctx.source.withMaximumPermission(4)
            //?}
        } else ctx.source
        val parsed = ctx.source.server.commands.dispatcher.parse(reader, parseSource)
        if (complete && (parsed.reader.canRead() || parsed.exceptions.isNotEmpty())) {
            TickSight.LOGGER.warn("Could not reparse TickSight command at {}: {}; errors: {}", parsed.reader.cursor, ctx.input, parsed.exceptions.values)
            return emptyList()
        }
        var next = parsed.context
        var found = false
        while (true) {
            if (next.nodes.any { it.node === duration }) found = true
            if (found) result.add(next.build(input).copyFor(ctx.source))
            next = next.child ?: break
        }
        if (complete && !found) TickSight.LOGGER.warn("TickSight duration node was absent from the parsed command: {}", ctx.input)
        return result
    }

    private fun options(parts: List<CommandContext<CommandSourceStack>>) = parts.flatMap { part ->
        part.nodes.mapNotNull { parsed ->
            val node = parsed.node
            val option = if (node is ModifierNode || node is LayerNode) optionsByKeyword.getValue(StringArgumentType.getString(part, node.name))
                else completedOptions[node]
            option?.let { it to part }
        }
    }

    private fun has(ctx: CommandContext<CommandSourceStack>, name: String) = ctx.nodes.any { it.node.name == name }

    private fun selectedLevel(options: List<Pair<RunOption, CommandContext<CommandSourceStack>>>, source: CommandSourceStack): ServerLevel {
        val dimension = options.firstOrNull { it.first == RunOption.IN }?.second ?: return source.level
        return DimensionArgument.getDimension(dimension, "dimension")
    }

    private fun start(ctx: CommandContext<CommandSourceStack>): Int {
        val parts = contexts(ctx, complete = true)
        if (parts.isEmpty()) return fail(ctx, "run_parse_failed")
        val rawDuration = StringArgumentType.getString(parts.first(), "duration")
        val (number, suffix) = durationSyntax.matchEntire(rawDuration)?.destructured ?: return fail(ctx, "duration_invalid")
        val amount = number.toIntOrNull()?.takeIf { it > 0 } ?: return fail(ctx, "duration_invalid")
        val durationValue = ProfileDuration(amount, suffix == "t")
        val options = options(parts)
        val seen = mutableSetOf<RunOption>()
        for ((option, _) in options) {
            if (option in seen) return fail(ctx, duplicateOption(option))
            val conflict = seen.firstOrNull(option::conflictsWith)
            if (conflict != null) return fail(ctx, conflict.conflictMessage(option))
            seen.add(option)
        }
        for (option in seen) if (option.prerequisite?.let { it !in seen } == true) return fail(ctx, option.missingPrerequisite())
        if (TickSight.PROFILER.isBusy) return fail(ctx, "profile_busy")

        val level = selectedLevel(options, ctx.source)
        // Use Minecraft's execute-in coordinate origin for every scope, including radius.
        val selectedSource = ctx.source.withLevel(level)
        var area: ProfileArea? = if (RunOption.IN in seen) ProfileArea.Dimension(level.dimension()) else null
        var target: ProfileTarget = ProfileTarget.AllObjects
        val scope = options.firstOrNull { it.first.scope }
        if (scope != null) {
            val (kind, original) = scope
            val part = original.copyFor(selectedSource)
            when (kind) {
                RunOption.RADIUS -> {
                    val radius = parseRadius(StringArgumentType.getString(part, "radius")) ?: return fail(ctx, "run_radius_invalid")
                    val shape = when {
                        RunOption.CIRCLE in seen -> ProfileArea.Radius.Shape.CIRCLE
                        RunOption.SPHERE in seen -> ProfileArea.Radius.Shape.SPHERE
                        else -> return fail(ctx, "run_radius_shape_required")
                    }
                    val center = options.firstOrNull { it.first == RunOption.AT }?.second?.let {
                        Vec3Argument.getVec3(it.copyFor(selectedSource), "center")
                    } ?: selectedSource.position
                    area = ProfileArea.Radius(level.dimension(), center, radius, shape, level.dimensionType().minY())
                }
                RunOption.BOX -> area = ProfileArea.Box(level.dimension(), BlockPosArgument.getBlockPos(part, "from"), BlockPosArgument.getBlockPos(part, "to"))
                RunOption.CHUNKS -> {
                    val x = IntegerArgumentType.getInteger(part, "chunkX")
                    val z = IntegerArgumentType.getInteger(part, "chunkZ")
                    val to = options.firstOrNull { it.first == RunOption.TO }?.second
                    area = ProfileArea.Chunks(level.dimension(), x, z,
                        if (to != null) IntegerArgumentType.getInteger(to, "toChunkX") else x,
                        if (to != null) IntegerArgumentType.getInteger(to, "toChunkZ") else z)
                }
                RunOption.ENTITY -> {
                    val entity = if (has(part, "id")) level.getEntity(IntegerArgumentType.getInteger(part, "id"))
                        ?: return fail(ctx, "entity_not_found") else EntityArgument.getEntity(part, "target")
                    if (RunOption.IN in seen && entity.level() !== level) return fail(ctx, "run_entity_dimension")
                    target = ProfileTarget.EntityTarget(entity)
                }
                RunOption.BLOCK -> target = ProfileTarget.Block(level.dimension(), BlockPosArgument.getBlockPos(part, "pos"))
                else -> error("Not a scope")
            }
        }
        val peak = RunOption.PEAK in seen
        val sample = RunOption.SAMPLE in seen
        var calls: CallProfile? = null
        if (RunOption.CALLS in seen) {
            if (!CallProfile.isAvailable()) return fail(ctx, "call_trace_agent_required")
            if (ServerSettings.callTraceTickLimitMs < 1) return fail(ctx, "call_trace_bad_limit")
            val repeats = if (RunOption.FULL in seen) -1 else ServerSettings.callTraceMaxRepeats
            if (repeats < -1) return fail(ctx, "call_trace_bad_repeats")
            if (ServerSettings.callTraceHardTickLimitMs < 1 || ServerSettings.callTraceMemoryLimitMiB < 1 || ServerSettings.callTraceHeapReserveMiB < 0)
                return fail(ctx, "call_trace_bad_budget")
            calls = CallProfile(if (target === ProfileTarget.AllObjects && area != null) area.label() else target.label(),
                ServerSettings.callTraceTickLimitMs, repeats, ServerSettings.callTraceHardTickLimitMs,
                ServerSettings.callTraceMemoryLimitMiB, ServerSettings.callTraceHeapReserveMiB)
        }
        val owner = ProfileOwner.command(ctx.source)
        if (!TickSight.PROFILER.runWithDuration(ctx.source.server, owner, durationValue, sample, peak, calls, area, target,
                layers = recordingLayers(seen)))
            return fail(ctx, "profile_busy")
        val message = if (calls != null) "call_trace_started" else if (peak) "profile_peak_started" else "profile_started"
        val key = "text.ticksight." + message + if (durationValue.inTicks) "_ticks" else ""
        ctx.source.sendSuccess({ if (calls != null) serverText(key, calls.targetLabel, amount, calls.limitMs) else serverText(key, amount) }, false)
        return 1
    }

    private fun suggestBlockPosition(ctx: CommandContext<CommandSourceStack>, builder: SuggestionsBuilder) =
        BlockPosArgument.blockPos().let { argument ->
            val player = ctx.source.player
            val level = selectedLevel(options(contexts(ctx)), ctx.source)
            if (player == null || player.level() !== level || builder.remaining.startsWith("^")) return@let argument.listSuggestions(ctx, builder)
            //? if <1.21 {
            /*val range = if (player.abilities.instabuild) 5.0 else 4.5*/
            //?} else {
            val range = player.blockInteractionRange()
            //?}
            val hit = player.pick(range, 1.0F, false)
            if (hit !is BlockHitResult || hit.type == HitResult.Type.MISS) return@let argument.listSuggestions(ctx, builder)
            val pos = hit.blockPos
            SharedSuggestionProvider.suggestCoordinates(builder.remaining,
                listOf(SharedSuggestionProvider.TextCoordinates(pos.x.toString(), pos.y.toString(), pos.z.toString()), SharedSuggestionProvider.TextCoordinates.DEFAULT_GLOBAL),
                builder, Commands.createValidator(argument::parse))
        }
}

internal fun recordingLayers(options: Set<RunOption>): RecordingLayers {
    val disabled = options.filter { it != RunOption.NO_LAYERS }.flatMap { it.disables }.toMutableSet()
    if (RunOption.NO_LAYERS in options) disabled.addAll(RecordedLayer.entries - options.mapNotNull { it.layer }.toSet())
    return RecordingLayers.without(disabled)
}

private fun parseRadius(value: String): Double? {
    val (number, suffix) = radiusSyntax.matchEntire(value)?.destructured ?: return null
    val distance = number.toDoubleOrNull() ?: return null
    val blocks = if (suffix == "c") distance * 16.0 else distance
    return blocks.takeIf { it.isFinite() && (it * it).isFinite() }
}

private fun suggestRadius(builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
    val distances = if (builder.remaining.isEmpty()) listOf("8b", "16b", "1c", "2c") else {
        val number = radiusSyntax.matchEntire(builder.remaining)?.groupValues?.get(1) ?: return builder.buildFuture()
        listOf("${number}b", "${number}c")
    }
    return suggestWithTooltips(builder, distances.filter { parseRadius(it) != null }.map {
        it to serverText(if (it.endsWith('c')) "text.ticksight.radius_chunks" else "text.ticksight.radius_blocks")
    })
}

private fun suggestWithTooltips(builder: SuggestionsBuilder, choices: List<Pair<String, Component>>): CompletableFuture<Suggestions> {
    val range = StringRange.between(builder.start, builder.input.length)
    val suggestions = choices.filter { it.first.startsWith(builder.remainingLowerCase) }.map { (keyword, tooltip) ->
        Suggestion(range, keyword, tooltip)
    }
    // Keep the current token's tooltip even if its text already matches.
    return CompletableFuture.completedFuture(Suggestions.create(builder.input, suggestions))
}

private fun duplicateOption(option: RunOption) = serverText("text.ticksight.run_duplicate", option.keyword)

private fun fail(ctx: CommandContext<CommandSourceStack>, key: String, vararg args: Any): Int =
    fail(ctx, serverText("text.ticksight.$key", *args))

private fun fail(ctx: CommandContext<CommandSourceStack>, message: Component): Int {
    ctx.source.sendFailure(message)
    return 0
}

private fun suggestDuration(builder: SuggestionsBuilder) = builder.apply {
    val input = remaining
    if (input.isEmpty()) {
        suggest("3", serverText("text.ticksight.duration_seconds"))
        suggest("3s", serverText("text.ticksight.duration_seconds"))
        suggest("60t", serverText("text.ticksight.duration_ticks"))
    } else if (input.all { it in '0'..'9' } && (input.toIntOrNull() ?: 0) > 0) {
        suggest("${input}s", serverText("text.ticksight.duration_seconds"))
        suggest("${input}t", serverText("text.ticksight.duration_ticks"))
    }
}.buildFuture()
