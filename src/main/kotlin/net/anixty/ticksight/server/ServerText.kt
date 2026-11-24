package net.anixty.ticksight.server

import kotlinx.serialization.json.Json
import net.minecraft.network.chat.Component
import net.anixty.ticksight.TickSight

private val englishMessages: Map<String, String> by lazy {
    val path = "/assets/${TickSight.MOD_ID}/lang/en_us.json"
    checkNotNull(TickSight::class.java.getResourceAsStream(path)).bufferedReader().use {
        Json.decodeFromString(it.readText())
    }
}

internal fun serverText(key: String, vararg args: Any): Component =
    Component.translatableWithFallback(key, englishMessages.getValue(key), *args)
