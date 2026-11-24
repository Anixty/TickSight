package net.anixty.ticksight.client

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.anixty.ticksight.server.DataWithDiagnostics
import net.anixty.ticksight.server.ProfileOwner
import net.anixty.ticksight.server.ProfilingData
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object ProfileExporter {
    private val dir = File("ticksight_profiles")
    private val sdf = SimpleDateFormat("yyyy-MM-dd--HH.mm.ss")

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    private val json = Json {
        prettyPrint = true
    }

    fun export(data: ProfilingData, owner: ProfileOwner, diagnosticsJson: String? = null): Component {
        val file = File(dir, "${sdf.format(Date())}.json")
        val diagnostics = buildJsonObject {
            diagnosticsJson?.let { json.parseToJsonElement(it).jsonObject }?.forEach { (key, value) -> put(key, value) }
            put("owner", json.encodeToJsonElement(owner))
            put("exportedBy", "client")
        }
        file.printWriter().use {
            it.println(json.encodeToString(DataWithDiagnostics(data, diagnostics)))
        }

        val link = Component.literal(file.name).withStyle(ChatFormatting.UNDERLINE).withStyle {
            it.withClickEvent(
//? if >=26.1 {
/*
ClickEvent.OpenFile(dir.absolutePath)
*/
//?} else {
ClickEvent(ClickEvent.Action.OPEN_FILE, dir.absolutePath)
//?}
)
        }

        return link
    }
}
