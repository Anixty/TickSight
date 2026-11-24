package net.anixty.ticksight.client

import com.mojang.blaze3d.platform.InputConstants
import dev.architectury.event.events.client.ClientLifecycleEvent
import dev.architectury.event.events.client.ClientPlayerEvent
import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.registry.client.keymappings.KeyMappingRegistry
import net.anixty.ticksight.TickSight
import net.anixty.ticksight.net.S2CPacket
import net.anixty.ticksight.server.ProfilingData
import net.anixty.ticksight.util.MOD_URL_COMPONENT
import net.anixty.ticksight.util.Marker
import net.anixty.ticksight.util.webLink
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
//? if >=26.1 {
/*import net.minecraft.resources.Identifier*/
//?}
import org.lwjgl.glfw.GLFW

object TickSightClient {
    var results: ProfilingData? = null
        private set

    private val profileScreen by lazy { ProfileScreen() }
    private val profileKey by lazy {
        KeyMapping(
            "key.ticksight.profile",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            //? if >=26.1 {
            /*KeyMapping.Category.register(Identifier.fromNamespaceAndPath(TickSight.MOD_ID, "keybinds"))*/
            //?} else {
            "category.ticksight.keybinds"
            //?}
        )
    }

    @JvmStatic
    fun init() {
        TickSight.handleClientPacket = ::receive
        KeyMappingRegistry.register(profileKey)
        ClientTickEvent.CLIENT_POST.register {
            if (profileKey.consumeClick()) it.setScreen(profileScreen)
        }
        ClientLifecycleEvent.CLIENT_LEVEL_LOAD.register { level ->
            Overlay.loadSync(level)
            Marker("ticksight_announce").mark {
                chat(Component.translatable("text.ticksight.announce", MOD_URL_COMPONENT))
            }
        }
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register {
            results = null
            profileScreen.action = ProfileScreen.Action.UNAVAILABLE
        }
    }

    private fun receive(packet: S2CPacket) {
        when (packet) {
            is S2CPacket.ProfilingStarted -> {
                profileScreen.action = ProfileScreen.Action.TPSProfilerRunning(packet.endMillis)
                profileScreen.startBtn?.active = false
            }
            S2CPacket.ProfilingCompleted -> profileScreen.action = ProfileScreen.Action.TPSProfilerCompleted
            S2CPacket.ProfilerInactive, S2CPacket.Availability.Available -> resetProfileScreen()
            S2CPacket.Availability.NoPermissions -> {
                profileScreen.action = ProfileScreen.Action.NO_PERMISSIONS
                profileScreen.startBtn?.active = false
            }
            is S2CPacket.ProfilingResult -> {
                results = packet.data
                resetProfileScreen()
                Overlay.loadSync()
                val url = packet.link
                if (url == null) {
                    chat(Component.translatable("text.ticksight.upload_failed"))
                    chat(Component.translatable("text.ticksight.profile_saved", ProfileExporter.export(packet.data, packet.owner, packet.diagnosticsJson)))
                    chat(Component.translatable("text.ticksight.after_save", MOD_URL_COMPONENT))
                } else {
                    chat(Component.translatable("text.ticksight.profile_uploaded", webLink(url)))
                }
            }
        }
    }

    private fun resetProfileScreen() {
        profileScreen.action = ProfileScreen.Action.DEFAULT
        profileScreen.startBtn?.active = true
    }

    private fun chat(message: Component) {
        val chat = Minecraft.getInstance().gui.chat
        //? if >=26.1 {
        /*chat.addClientSystemMessage(message)*/
        //?} else {
        chat.addMessage(message)
        //?}
    }
}
