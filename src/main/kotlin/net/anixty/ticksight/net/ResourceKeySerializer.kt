package net.anixty.ticksight.net

import net.anixty.ticksight.util.locationId

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
//? if >=26.1 {
/*
import net.minecraft.resources.Identifier as ResourceLocation
*/
//?} else {
import net.minecraft.resources.ResourceLocation
//?}
import net.minecraft.world.level.Level

open class ResourceKeySerializer<T : Any>(val registryKey: ResourceKey<out Registry<T>>) :
    KSerializer<ResourceKey<T>> {
    private val delegate = String.serializer()
    override val descriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): ResourceKey<T> =
        ResourceKey.create(registryKey,

//? if <1.21 {
            /*ResourceLocation(delegate.deserialize(decoder))*/
//?} else {
            ResourceLocation.parse(delegate.deserialize(decoder))
            //?}
        )

    override fun serialize(encoder: Encoder, value: ResourceKey<T>) =
        delegate.serialize(encoder, value.locationId().toString())

    class Dimension : ResourceKeySerializer<Level>(Registries.DIMENSION)
}
