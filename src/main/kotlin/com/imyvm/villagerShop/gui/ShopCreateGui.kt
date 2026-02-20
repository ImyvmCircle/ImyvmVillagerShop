package com.imyvm.villagerShop.gui

import com.imyvm.villagerShop.VillagerShopMain
import com.imyvm.villagerShop.apis.Translator.tr
import eu.pb4.sgui.api.gui.SimpleGui
import net.minecraft.component.DataComponentTypes
import net.minecraft.enchantment.Enchantments
import net.minecraft.item.Item
import net.minecraft.registry.RegistryWrapper
import net.minecraft.screen.ScreenHandlerType
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Unit

class ShopCreateGui(
    private val playerEntity: ServerPlayerEntity,
    private val registries: RegistryWrapper.WrapperLookup
) {
    private val gui = object : SimpleGui(ScreenHandlerType.GENERIC_9X1, playerEntity, false) {
        override fun onClose() {
            removeGui(playerEntity)
            super.onClose()
        }
    }

    fun open() {
        this.setIcons()
        this.gui.title = tr("gui.shop_create.title")
        gui.open()
        addGui(playerEntity)
    }

    private fun setIcons() {
        // TODO: Implement icon setup for ShopCreate GUI
    }

    private fun setIcon(
        slot: Int,
        item: Item,
        name: Text,
        enchanted: Boolean,
        loreList: List<Text>,
        callback: Runnable
    ) {
        val itemStack = item.defaultStack
        itemStack.set(DataComponentTypes.HIDE_ADDITIONAL_TOOLTIP, Unit.INSTANCE)
        itemStack.set(DataComponentTypes.CUSTOM_NAME, name)
        if (enchanted) {
            val enchantmentRegistry = registries.getWrapperOrThrow(net.minecraft.registry.RegistryKeys.ENCHANTMENT)
            itemStack.addEnchantment(enchantmentRegistry.getOrThrow(Enchantments.MENDING), 1)
        }

//        if (loreList.isNotEmpty()) {
//            val lore = NbtList()
//            for (line in loreList) {
//                lore.add(NbtString.of(Text.Serializer.))
//            }
//        }
    }

    private fun addGui(playerEntity: ServerPlayerEntity) {
        VillagerShopMain.guiSet.add(playerEntity)
    }

    fun removeGui(playerEntity: ServerPlayerEntity) {
        VillagerShopMain.guiSet.remove(playerEntity)
    }
}