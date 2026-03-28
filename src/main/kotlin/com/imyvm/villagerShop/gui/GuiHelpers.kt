package com.imyvm.villagerShop.gui

import eu.pb4.sgui.api.elements.GuiElementBuilder
import eu.pb4.sgui.api.gui.SimpleGui
import net.minecraft.item.Items
import net.minecraft.text.Text

// ─── Shared GUI layout helpers used by ShopCreateGui and ShopManageGui ───────

internal fun glassPane(color: Int = 7): GuiElementBuilder {
    val item = when (color) {
        0  -> Items.WHITE_STAINED_GLASS_PANE
        1  -> Items.ORANGE_STAINED_GLASS_PANE
        2  -> Items.MAGENTA_STAINED_GLASS_PANE
        3  -> Items.LIGHT_BLUE_STAINED_GLASS_PANE
        4  -> Items.YELLOW_STAINED_GLASS_PANE
        5  -> Items.LIME_STAINED_GLASS_PANE
        6  -> Items.PINK_STAINED_GLASS_PANE
        7  -> Items.GRAY_STAINED_GLASS_PANE
        8  -> Items.LIGHT_GRAY_STAINED_GLASS_PANE
        9  -> Items.CYAN_STAINED_GLASS_PANE
        10 -> Items.PURPLE_STAINED_GLASS_PANE
        11 -> Items.BLUE_STAINED_GLASS_PANE
        12 -> Items.BROWN_STAINED_GLASS_PANE
        13 -> Items.GREEN_STAINED_GLASS_PANE
        14 -> Items.RED_STAINED_GLASS_PANE
        15 -> Items.BLACK_STAINED_GLASS_PANE
        else -> Items.GRAY_STAINED_GLASS_PANE
    }
    return GuiElementBuilder(item).setName(Text.literal(" "))
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

