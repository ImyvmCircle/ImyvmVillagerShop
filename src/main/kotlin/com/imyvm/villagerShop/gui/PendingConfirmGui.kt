package com.imyvm.villagerShop.gui

import com.imyvm.villagerShop.apis.Translator.tr
import com.imyvm.villagerShop.apis.cancelPendingOperationJob
import com.imyvm.villagerShop.commands.pendingOperations
import eu.pb4.sgui.api.elements.GuiElementBuilder
import eu.pb4.sgui.api.gui.SimpleGui
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.Items

object PendingConfirmGui {
    fun open(player: ServerPlayer) {
        val gui = object : SimpleGui(MenuType.GENERIC_9x3, player, false) {}
        gui.title = tr("gui.confirm.title")
        border(gui, 3)

        gui.setSlot(13, GuiElementBuilder(Items.PAPER)
            .setName(tr("gui.confirm.pending"))
            .addLoreLine(tr("gui.confirm.tip"))
        )

        gui.setSlot(11, GuiElementBuilder(Items.LIME_DYE)
            .setName(tr("gui.confirm.confirm"))
            .setCallback { _, _, _, _ ->
                val op = pendingOperations.remove(player.uuid)
                if (op != null) {
                    op.operation()
                    player.sendSystemMessage(tr("commands.confirm.ok"))
                } else {
                    player.sendSystemMessage(tr("commands.confirm.none"))
                }
                cancelPendingOperationJob(player.uuid)
                player.level().server.execute { player.level().server.playerList.sendPlayerPermissionLevel(player) }
                gui.close()
            }
        )

        gui.setSlot(15, GuiElementBuilder(Items.RED_DYE)
            .setName(tr("gui.confirm.cancel"))
            .setCallback { _, _, _, _ ->
                val hadOp = pendingOperations.remove(player.uuid) != null
                player.sendSystemMessage(if (hadOp) tr("commands.cancel.ok") else tr("commands.cancel.none"))
                cancelPendingOperationJob(player.uuid)
                player.level().server.execute { player.level().server.playerList.sendPlayerPermissionLevel(player) }
                gui.close()
            }
        )

        gui.open()
    }
}

