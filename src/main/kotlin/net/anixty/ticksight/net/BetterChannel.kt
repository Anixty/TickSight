//? if <1.21 {
/*
package net.anixty.ticksight.net

import dev.architectury.networking.NetworkChannel
import dev.architectury.networking.NetworkManager
import kotlinx.serialization.*
import kotlinx.serialization.protobuf.ProtoBuf
import net.minecraft.network.FriendlyByteBuf
//? if >=26.1 {
/*
import net.minecraft.resources.Identifier as ResourceLocation
*/
//?} else {
import net.minecraft.resources.ResourceLocation
//?}
import net.minecraft.server.level.ServerPlayer
import org.apache.logging.log4j.LogManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.Exception
import java.util.*
import java.util.function.Supplier
import java.util.zip.*
import kotlin.collections.HashMap
import kotlin.reflect.KType
import kotlin.reflect.typeOf

class BetterChannel(id: ResourceLocation) {
    companion object {
        val LOGGER = LogManager.getLogger("TickSightNet")
    }

    var rawChannel = NetworkChannel.create(id)

    @Serializable data class PartialPacketBegin(val id: Long, val type: String)

    @Serializable
    class PartialPacketData(val id: Long, val data: ByteArray, val index: Int, val length: Int)

    class PartialPacketAssembler(val type: KType) {
        companion object {
            val MAP = HashMap<Long, PartialPacketAssembler>()
            val KNOWN_TYPES = HashMap<String, KType>()
            val ACTIONS = HashMap<KType, (Any, NetworkManager.PacketContext) -> Unit>()
            val PACKET_SIZE = 1000000

            @OptIn(ExperimentalStdlibApi::class)
            inline fun <reified T> register(
                noinline consumer: (T, NetworkManager.PacketContext) -> Unit
            ) {
                val type = typeOf<T>()
                KNOWN_TYPES[T::class.java.name] = type
                ACTIONS[type] = { obj, ctx ->
                    var data: T? = null
                    try {
                        data = obj as T
                    } catch (e: Exception) {
                        LOGGER.warn("Error casting partial packet data to type ${T::class}!")
                        e.printStackTrace()
                    }
                    data?.let { consumer(it, ctx) }
                }
            }

            fun enqueue(packet: PartialPacketData, supplier: NetworkManager.PacketContext) {
                MAP[packet.id]?.let {
                    it.packets[packet.index] = packet.data
                    if (packet.length == it.packets.size) {
                        LOGGER.info("Assembling packet ${it.type}")
                        it.assemble()?.let { pkt -> ACTIONS[it.type]?.invoke(pkt, supplier) }
                        MAP.remove(packet.id)
                    }
                }
            }
        }

        var packets = mutableMapOf<Int, ByteArray>()

        @OptIn(ExperimentalSerializationApi::class)
        fun assemble(): Any? {
            val bs = ByteArrayOutputStream()
            packets.toList().sortedBy { it.first }.forEach { bs.write(it.second) }
            bs.close()
            return try {
                ProtoBuf.decodeFromByteArray(serializer(type), bs.toByteArray())
            } catch (e: Exception) {
                LOGGER.warn("Error decoding packet of type $type!")
                e.printStackTrace()
                null
            }
        }
    }

    init {
        this.register { t: PartialPacketBegin, supplier ->
            LOGGER.info("Received starting packet ${t.id} for ${t.type}")
            val type = PartialPacketAssembler.KNOWN_TYPES[t.type]
            if (type != null) {
                PartialPacketAssembler.MAP[t.id] = PartialPacketAssembler(type)
            } else {
                LOGGER.warn("Could not find mapping for ${t.type}")
            }
        }
        this.register { t: PartialPacketData, supplier ->
            LOGGER.info("Received packet ${t.index + 1}/${t.length} of id ${t.id}")
            PartialPacketAssembler.enqueue(t, supplier)
        }
    }

    /**
     * Provide a function that will attempt to call another function with packet data and fail
     * gracefully if not able.
     *
     * @param action The function to call
     */
    inline fun <reified T> attempt(
        crossinline action: (FriendlyByteBuf) -> T
    ): (FriendlyByteBuf) -> T? = {
        try {
            action(it)
        } catch (e: Exception) {
            LOGGER.warn("Error decoding packet!")
            e.printStackTrace()
            null
        }
    }

    inline fun <reified T> validate(
        noinline consumer: (T, NetworkManager.PacketContext) -> Unit
    ) = { t: T?, v: Supplier<NetworkManager.PacketContext> -> if (t != null) consumer(t, v.get()) }

    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T> register(
        noinline consumer: (T, NetworkManager.PacketContext) -> Unit
    ) {
        LOGGER.info("Registering ${T::class.java}")
        PartialPacketAssembler.register(consumer)
        rawChannel.register(
            T::class.java,
            { t, buf -> buf.writeByteArray(ProtoBuf.encodeToByteArray(t)) },
            attempt { ProtoBuf.decodeFromByteArray<T>(it.readByteArray()) },
            validate(consumer)
        )
        LOGGER.info("Registered ${T::class.java}")
    }

    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T> registerCompressed(
        noinline consumer: (T, NetworkManager.PacketContext) -> Unit
    ) {
        LOGGER.info("Registering ${T::class.java}")
        rawChannel.register(
            T::class.java,
            { t, buf ->
                val bs = ByteArrayOutputStream()
                val deflater = Deflater()
                deflater.setLevel(Deflater.BEST_COMPRESSION)
                val deflaterStream = DeflaterOutputStream(bs, deflater)
                deflaterStream.write(ProtoBuf.encodeToByteArray(t))
                deflaterStream.close()
                buf.writeByteArray(bs.toByteArray())
            },
            attempt {
                val istream = InflaterInputStream(ByteArrayInputStream(it.readByteArray()))
                ProtoBuf.decodeFromByteArray<T>(istream.readBytes())
            },
            validate(consumer)
        )
        LOGGER.info("Registered ${T::class.java}")
    }

    fun <T : Any> sendToPlayers(players: List<ServerPlayer>, msg: T) {
        val recipients = players.filter { rawChannel.canPlayerReceive(it, msg.javaClass) }
        if (recipients.isNotEmpty()) rawChannel.sendToPlayers(recipients, msg)
    }

    fun <T : Any> sendToPlayer(player: ServerPlayer, msg: T) = sendToPlayers(listOf(player), msg)

    @OptIn(ExperimentalStdlibApi::class)
    inline fun <reified T> sendToPlayersSplit(players: List<ServerPlayer>, msg: T): Boolean {
        val recipients = players.filter {
            rawChannel.canPlayerReceive(it, PartialPacketBegin::class.java) &&
                rawChannel.canPlayerReceive(it, PartialPacketData::class.java)
        }
        if (recipients.isEmpty()) return false
        val data = ProtoBuf.encodeToByteArray(msg)
        val bs = ByteArrayInputStream(data)
        val id = UUID.randomUUID().leastSignificantBits
        rawChannel.sendToPlayers(recipients, PartialPacketBegin(id, T::class.java.name))
        val size =
            bs.available() / PartialPacketAssembler.PACKET_SIZE +
                (bs.available() % PartialPacketAssembler.PACKET_SIZE).coerceAtMost(1)
        var idx = 0
        while (bs.available() > 0) {
            val arr = ByteArray(bs.available().coerceAtMost(PartialPacketAssembler.PACKET_SIZE))
            bs.read(arr)
            rawChannel.sendToPlayers(recipients, PartialPacketData(id, arr, idx, size))
            idx++
        }
        return true
    }

    fun <T> sendToServer(msg: T) = rawChannel.sendToServer(msg)
}
*/
//?} else {
package net.anixty.ticksight.net

import dev.architectury.networking.NetworkManager
import dev.architectury.networking.NetworkManager.Side
import dev.architectury.networking.NetworkManager.canPlayerReceive
import dev.architectury.networking.transformers.SplitPacketTransformer
import dev.architectury.platform.Platform
import dev.architectury.utils.Env
import kotlinx.serialization.*
import kotlinx.serialization.protobuf.ProtoBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
//? if >=26.1 {
/*
import net.minecraft.resources.Identifier as ResourceLocation
*/
//?} else {
import net.minecraft.resources.ResourceLocation
//?}
import net.minecraft.server.level.ServerPlayer
import org.apache.logging.log4j.LogManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@OptIn(ExperimentalSerializationApi::class)
class BetterChannel(val id: ResourceLocation) {
    companion object {
        val LOGGER = LogManager.getLogger("TickSightNet")
    }

    val s2cLocation: ResourceLocation = id.withSuffix("-s2c")
    val c2sLocation: ResourceLocation = id.withSuffix("-c2s")

    class SerializedPayload(val className: String, val data: ByteArray, val location: ResourceLocation) : CustomPacketPayload {
        override fun type() = CustomPacketPayload.Type<CustomPacketPayload>(location)
    }
    inline fun <reified T> createPayload(data: T, side: Side) = SerializedPayload(T::class.java.name, ProtoBuf.encodeToByteArray(data), if (side == Side.S2C) s2cLocation else c2sLocation)

    val handlers = mutableMapOf<String, (ByteArray, NetworkManager.PacketContext) -> Unit>()

    init {
        val codec = object : StreamCodec<RegistryFriendlyByteBuf, SerializedPayload> {
            override fun decode(buf: RegistryFriendlyByteBuf): SerializedPayload {
                val name = buf.readUtf()
                val bytes = GZIPInputStream(ByteArrayInputStream(buf.readByteArray())).buffered().use { it.readAllBytes() }
                return SerializedPayload(name, bytes, id)
            }

            override fun encode(buf: RegistryFriendlyByteBuf, payload: SerializedPayload) {
                buf.writeUtf(payload.className)
                val bos = ByteArrayOutputStream()
                GZIPOutputStream(bos).buffered().use { it.write(payload.data) }
                buf.writeByteArray(bos.toByteArray())
            }
        }
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(CustomPacketPayload.Type(s2cLocation), codec, listOf(SplitPacketTransformer()))
        } else {
            NetworkManager.registerReceiver(Side.S2C, CustomPacketPayload.Type(s2cLocation), codec, listOf(SplitPacketTransformer())) { value, ctx ->
                handlers[value.className]?.invoke(value.data, ctx)
            }
        }
        NetworkManager.registerReceiver(Side.C2S, CustomPacketPayload.Type(c2sLocation), codec) { value, ctx ->
            ctx.player
            handlers[value.className]?.invoke(value.data, ctx)
        }
    }

    inline fun <reified T> register(
        noinline consumer: (T, NetworkManager.PacketContext) -> Unit
    ) {
        handlers[T::class.java.name] = { buf, ctx -> consumer(ProtoBuf.decodeFromByteArray(buf), ctx) }
        LOGGER.info("Registered ${T::class.java}")
    }

    inline fun <reified T> sendToPlayers(players: Iterable<ServerPlayer>, msg: T): Boolean {
        val recipients = players.filter { canPlayerReceive(it, s2cLocation) }
        if (recipients.isEmpty()) return false
        NetworkManager.sendToPlayers(recipients, createPayload(msg, Side.S2C))
        return true
    }
    inline fun <reified T> sendToPlayer(player: ServerPlayer, msg: T) = sendToPlayers(listOf(player), msg)
    inline fun <reified T> sendToPlayersSplit(players: Iterable<ServerPlayer>, msg: T) = sendToPlayers(players, msg)
    inline fun <reified T> sendToServer(msg: T) = NetworkManager.sendToServer(createPayload(msg, Side.C2S))
}
//?}
