package com.imyvm.villagerShop.commands

import com.imyvm.villagerShop.VillagerShopMain.Companion.CONFIG
import com.imyvm.villagerShop.apis.ModConfig.Companion.TAX_RESTOCK
import com.imyvm.villagerShop.apis.Translator.tr
import com.mojang.brigadier.Command
import com.mojang.brigadier.context.CommandContext
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import java.util.function.Supplier

fun taxRateChange(context: CommandContext<ServerCommandSource>, taxrate: Double): Int {
    TAX_RESTOCK.setValue(taxrate)
    CONFIG.loadAndSave()
    val textSupplier = Supplier<Text> { tr("commands.imyvm_villager_shop.tax_change.success") }
    context.source.sendFeedback(textSupplier, true)
    return Command.SINGLE_SUCCESS
}

fun reload(context: CommandContext<ServerCommandSource>): Int {
    CONFIG.loadAndSave()
    val textSupplier = Supplier<Text> { tr("commands.imyvm_villager_shop.tax_change.success") }
    context.source.sendFeedback(textSupplier, true)
    return Command.SINGLE_SUCCESS
}