package net.anixty.ticksight.util

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

const val MOD_URL = "https://ticksight.anixty.net/"
val MOD_URL_COMPONENT: Component = webLink(MOD_URL)

fun suggestCommand(label: Component, command: String, hover: Component): Component = label.copy().withStyle {
    it.withClickEvent(
        //? if >=26.1 {
        /*ClickEvent.SuggestCommand(command)*/
        //?} else {
        ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command)
        //?}
    ).withHoverEvent(
        //? if >=26.1 {
        /*HoverEvent.ShowText(hover)*/
        //?} else {
        HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)
        //?}
    )
}

fun webLink(url: String): Component =
    Component.literal(url).withStyle(ChatFormatting.UNDERLINE).withStyle {
        it.withClickEvent(
            //? if >=26.1 {
            /*ClickEvent.OpenUrl(java.net.URI(url))*/
            //?} else {
            ClickEvent(ClickEvent.Action.OPEN_URL, url)
            //?}
        )
    }
