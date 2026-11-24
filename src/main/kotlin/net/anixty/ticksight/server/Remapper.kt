package net.anixty.ticksight.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.anixty.ticksight.TickSight
import java.net.URL

enum class ModLoader {
    FABRIC,
    FORGE
}

//? if <1.21 {
/*
val BASE_URL = "https://raw.githubusercontent.com/lucko/spark-mappings/master/dist/1_20"
*/
//?} else {
//? if >=1.21.4 {
/*
val BASE_URL = "https://raw.githubusercontent.com/lucko/spark-mappings/master/dist/1_21_4"
*/
//?} else {
val BASE_URL = "https://raw.githubusercontent.com/lucko/spark-mappings/master/dist/1_21_1"
//?}
//?}

val MAPPING_URLS =
    mapOf(
        ModLoader.FABRIC to "$BASE_URL/yarn.json",
        ModLoader.FORGE to "$BASE_URL/mcp.json"
    )

inline val JsonElement?.stringMap
    get() = this?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: mapOf()

object Remapper {
    data class RemappingData(val classes: Map<String, String>, val methods: Map<String, String>)

    lateinit var modLoader: ModLoader

    val remappingData by lazy {
        //? if >=26.1 {
        /*RemappingData(mapOf(), mapOf())*/
//?} else {
        try {
            val jsonData = Json.parseToJsonElement(URL(MAPPING_URLS[modLoader]).readText()).jsonObject
            RemappingData(
                if (modLoader == ModLoader.FABRIC) jsonData["classes"].stringMap else mapOf(),
                jsonData["methods"].stringMap
            )
        } catch (e: Exception) {
            TickSight.LOGGER.warn("Unable to get profiling data! ${e.message}")
            e.printStackTrace()
            TickSight.LOGGER.warn("Remapping data will be unavailable for the remainder of the session")
            RemappingData(mapOf(), mapOf())
        }
        //?}
    }

    fun transform(map: TraceMap) {
        remappingData.classes[map.className]?.let { map.className = it }
        remappingData.methods[map.methodName]?.let { map.methodName = it }
    }
}
