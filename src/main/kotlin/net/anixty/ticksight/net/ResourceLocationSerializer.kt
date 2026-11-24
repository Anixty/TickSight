package net.anixty.ticksight.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
//? if >=26.1 {
/*
import net.minecraft.resources.Identifier as ResourceLocation
*/
//?} else {
import net.minecraft.resources.ResourceLocation
//?}

class ResourceLocationSerializer : KSerializer<ResourceLocation> {
    private val delegate = String.serializer()
    override val descriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder) =

//? if <1.21 {
        /*ResourceLocation(delegate.deserialize(decoder))*/
//?} else {
        ResourceLocation.parse(delegate.deserialize(decoder))
        //?}

    override fun serialize(encoder: Encoder, value: ResourceLocation) =
        delegate.serialize(encoder, value.toString())
}
