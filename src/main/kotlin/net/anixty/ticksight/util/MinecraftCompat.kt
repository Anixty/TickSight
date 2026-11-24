package net.anixty.ticksight.util

import net.minecraft.resources.ResourceKey
//? if >=26.1 {
/*import net.minecraft.resources.Identifier as ResourceLocation*/
//?} else {
import net.minecraft.resources.ResourceLocation
//?}

fun ResourceKey<*>.locationId(): ResourceLocation =
    //? if >=26.1 {
    /*identifier()*/
    //?} else {
    location()
    //?}
