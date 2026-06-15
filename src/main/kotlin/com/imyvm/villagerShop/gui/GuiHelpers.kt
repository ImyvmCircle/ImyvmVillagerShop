package com.imyvm.villagerShop.gui

import eu.pb4.sgui.api.elements.GuiElementBuilder
import eu.pb4.sgui.api.gui.SimpleGui
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Items

// ─── Shared GUI layout helpers used by ShopCreateGui and ShopManageGui ───────

internal fun glassPane(color: Int = 7): GuiElementBuilder {
    val item = when (color) {
        0  -> Items.STAINED_GLASS_PANE.white
        1  -> Items.STAINED_GLASS_PANE.orange
        2  -> Items.STAINED_GLASS_PANE.magenta
        3  -> Items.STAINED_GLASS_PANE.lightBlue
        4  -> Items.STAINED_GLASS_PANE.yellow
        5  -> Items.STAINED_GLASS_PANE.lime
        6  -> Items.STAINED_GLASS_PANE.pink
        7  -> Items.STAINED_GLASS_PANE.gray
        8  -> Items.STAINED_GLASS_PANE.lightGray
        9  -> Items.STAINED_GLASS_PANE.cyan
        10 -> Items.STAINED_GLASS_PANE.purple
        11 -> Items.STAINED_GLASS_PANE.blue
        12 -> Items.STAINED_GLASS_PANE.brown
        13 -> Items.STAINED_GLASS_PANE.green
        14 -> Items.STAINED_GLASS_PANE.red
        15 -> Items.STAINED_GLASS_PANE.black
        else -> Items.STAINED_GLASS_PANE.gray
    }
    return GuiElementBuilder(item).setName(Component.literal(" "))
}

/** Fills the outer border of a [rows]×9 GUI with grey glass panes. */
internal fun border(gui: SimpleGui, rows: Int) {
    val size = rows * 9
    for (i in 0 until size) {
        val row = i / 9
        val col = i % 9
        if (row == 0 || row == rows - 1 || col == 0 || col == 8) gui.setSlot(i, glassPane(7))
    }
}

