package com.imyvm.villagerShop.commands

import com.imyvm.villagerShop.VillagerShopMain.Companion.CONFIG
import com.imyvm.villagerShop.apis.ModConfig.Companion.TAX_RESTOCK
import com.imyvm.villagerShop.apis.Translator.tr
import com.mojang.brigadier.Command
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import java.util.function.Supplier

fun taxRateChange(context: CommandContext<CommandSourceStack>, taxrate: Double): Int {
    TAX_RESTOCK.setValue(taxrate)
    CONFIG.loadAndSave()
    val textSupplier = Supplier<Component> { tr("commands.imyvm_villager_shop.tax_change.success") }
    context.source.sendSuccess(textSupplier, true)
    return Command.SINGLE_SUCCESS
}

fun reload(context: CommandContext<CommandSourceStack>): Int {
    CONFIG.loadAndSave()
    val textSupplier = Supplier<Component> { tr("commands.imyvm_villager_shop.tax_change.success") }
    context.source.sendSuccess(textSupplier, true)
    return Command.SINGLE_SUCCESS
}