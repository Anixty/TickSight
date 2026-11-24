//? if <1.21 {
/*
package net.anixty.ticksight.client

import net.minecraft.client.gui.components.Checkbox
import net.minecraft.network.chat.Component

class BetterCheckbox(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    component: Component,
    default: Boolean,
    var callback: ((Boolean) -> Unit)
) : Checkbox(x, y, width, height, component, default) {
    override fun onPress() {
        super.onPress()
        callback(this.selected())
    }
}
*/
//?} else {
package net.anixty.ticksight.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.network.chat.Component

fun BetterCheckbox(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    component: Component,
    default: Boolean,
    callback: ((Boolean) -> Unit)
) = Checkbox.builder(component, Minecraft.getInstance().font).pos(x, y).maxWidth(width).selected(default).onValueChange { _, bl -> callback(bl) }.build()
//?}
