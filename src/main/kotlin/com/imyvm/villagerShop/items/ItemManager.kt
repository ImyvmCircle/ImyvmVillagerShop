package com.imyvm.villagerShop.items

import com.imyvm.villagerShop.apis.Translator.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.command.argument.ItemStackArgument
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.nbt.StringNbtReader
import net.minecraft.predicate.ComponentPredicate
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryWrapper
import net.minecraft.village.TradedItem
import kotlin.jvm.optionals.getOrNull
import kotlin.math.min

class ItemManager(
    var item: TradedItem,
    var sellPerTime: Int,
    var price: Double,
    var stock: MutableMap<String, Int>,
    var registries: RegistryWrapper.WrapperLookup
) {
    constructor(
        item: ItemStackArgument,
        sellPerTime: Int,
        price: Double,
        stock: MutableMap<String, Int> = mutableMapOf<String, Int>(),
        registries: RegistryWrapper.WrapperLookup
    ): this(
        TradedItem(
            Registries.ITEM.getEntry(Registries.ITEM.getKey(item.item).get()).get(),
            sellPerTime,
            ComponentPredicate.of(item.createStack(sellPerTime, false).components),
            item.createStack(sellPerTime, false)
        ),
        sellPerTime, price, stock,
        registries)

    @Serializable
    data class ItemData(
        val itemNbt: String,
        val sellPerTime: Int,
        val price: Double,
        val stock: MutableMap<String, Int>
    )

    fun toJsonString(): String {
        val nbt = item.itemStack.encode(registries).asString()
        val itemData = ItemData(nbt, this@ItemManager.sellPerTime, price, stock)
        return Json.encodeToString(itemData)
    }

    companion object {
        fun removeItemFromInventory(player: PlayerEntity, itemToRemove: ItemStack, quantity: Int) :Int {
            val inventory = player.inventory
            val needAddStock = if (quantity <= player.inventory.count(itemToRemove.item)) {
                quantity
            } else {
                player.inventory.count(itemToRemove.item)
            }
            var addedStock = 0
            for (i in 0 until inventory.size()) {
                val currentItem = inventory.getStack(i)
                if (currentItem.components == itemToRemove.components) {
                    val itemsToRemoveFromSlot = min(needAddStock-addedStock, currentItem.count)
                    currentItem.decrement(itemsToRemoveFromSlot)
                    if (itemsToRemoveFromSlot == needAddStock-addedStock) {
                        player.sendMessage(tr("commands.stock.add.ok", needAddStock))
                        return needAddStock
                    } else {
                        addedStock += itemsToRemoveFromSlot
                    }
                }
            }
            player.sendMessage(tr("commands.stock.add.ok", addedStock))
            return addedStock
        }

        fun offerItemToPlayer(player: PlayerEntity, itemToGiveList: MutableList<ItemManager>) {
            val inventory = player.inventory
            for (item in itemToGiveList) {
                if (item.stock["default"] == null || item.stock["default"] == 0) {
                    continue
                }
                else {
                    inventory.offerOrDrop(
                        ItemStack(item.item.item, item.stock["default"]!!)
                    )
                }
            }
        }

        fun storeItemList (itemList: MutableList<ItemManager>): String {
            return Json.encodeToString(itemList.map { it.toJsonString() })
        }

        fun restoreItemList(jsonString: String, registries: RegistryWrapper.WrapperLookup): MutableList<ItemManager> {
            val stringList: List<String> = Json.decodeFromString(jsonString)
            val itemDataList = stringList.map { jsonItem ->
                Json.decodeFromString<ItemData>(jsonItem)
            }

            val itemManagerList = mutableListOf<ItemManager>()

            itemDataList.map { itemData ->
                val nbt = StringNbtReader.parse(itemData.itemNbt)
                val itemStack = ItemStack.fromNbt(registries, nbt)

                itemStack.getOrNull()?.let {
                    itemManagerList.add(
                        ItemManager(
                            TradedItem(
                                Registries.ITEM.getEntry(Registries.ITEM.getKey(it.item).get()).get(),
                                itemData.sellPerTime,
                                ComponentPredicate.of(it.components),
                                it
                            ),
                            itemData.sellPerTime,
                            itemData.price,
                            itemData.stock,
                            registries
                        )
                    )
                }
            }

            return itemManagerList
        }
    }
}