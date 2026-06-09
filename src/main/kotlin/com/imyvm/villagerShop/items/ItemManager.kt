package com.imyvm.villagerShop.items

import com.imyvm.villagerShop.apis.Translator.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.minecraft.commands.arguments.item.ItemInput
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponentExactPredicate
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.TagParser
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.trading.ItemCost
import kotlin.math.min

class ItemManager(
    var item: ItemCost,
    var sellPerTime: Int,
    var price: Double,
    var stock: MutableMap<String, Int>,
    var registries: HolderLookup.Provider
) {
    constructor(
        item: ItemInput,
        sellPerTime: Int,
        price: Double,
        stock: MutableMap<String, Int> = mutableMapOf(),
        registries: HolderLookup.Provider
    ): this(
        ItemCost(
            item.item,
            sellPerTime,
            DataComponentExactPredicate.allOf(item.createItemStack(sellPerTime).components),
            item.createItemStack(sellPerTime)
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
        val nbt = encodeItemStack(item.itemStack, registries)
        val itemData = ItemData(nbt, this@ItemManager.sellPerTime, price, stock)
        return Json.encodeToString(itemData)
    }

    companion object {
        private fun encodeItemStack(stack: ItemStack, registries: HolderLookup.Provider): String {
            val ops = registries.createSerializationContext(NbtOps.INSTANCE)
            return ItemStack.CODEC.encodeStart(ops, stack).getOrThrow().toString()
        }

        private fun decodeItemStack(itemNbt: String, registries: HolderLookup.Provider): ItemStack {
            val ops = registries.createSerializationContext(NbtOps.INSTANCE)
            val nbt = TagParser.parseCompoundFully(itemNbt)
            return ItemStack.CODEC.parse(ops, nbt).getOrThrow()
        }

        fun countItemInInventory(player: Player, item: Item): Int {
            val inventory = player.inventory
            var count = 0
            for (i in 0 until inventory.containerSize) {
                val currentItem = inventory.getItem(i)
                if (currentItem.item == item) count += currentItem.count
            }
            return count
        }

        fun removeItemFromInventory(player: Player, itemToRemove: ItemStack, quantity: Int) :Int {
            val inventory = player.inventory
            val itemCount = countItemInInventory(player, itemToRemove.item)
            val needAddStock = if (quantity <= itemCount) {
                quantity
            } else {
                itemCount
            }
            var addedStock = 0
            for (i in 0 until inventory.containerSize) {
                val currentItem = inventory.getItem(i)
                if (currentItem.components == itemToRemove.components) {
                    val itemsToRemoveFromSlot = min(needAddStock-addedStock, currentItem.count)
                    currentItem.shrink(itemsToRemoveFromSlot)
                    if (itemsToRemoveFromSlot == needAddStock-addedStock) {
                        player.sendSystemMessage(tr("commands.stock.add.ok", needAddStock))
                        return needAddStock
                    } else {
                        addedStock += itemsToRemoveFromSlot
                    }
                }
            }
            player.sendSystemMessage(tr("commands.stock.add.ok", addedStock))
            return addedStock
        }

        fun offerItemToPlayer(player: Player, itemToGiveList: MutableList<ItemManager>) {
            val inventory = player.inventory
            for (item in itemToGiveList) {
                if (item.stock["default"] == null || item.stock["default"] == 0) {
                    continue
                }
                else {
                    inventory.placeItemBackInInventory(
                        ItemStack(item.item.item, item.stock["default"]!!)
                    )
                }
            }
        }

        fun storeItemList(itemList: MutableList<ItemManager>): String {
            return Json.encodeToString(itemList.map { it.toJsonString() })
        }

        fun restoreItemList(jsonString: String, registries: HolderLookup.Provider): MutableList<ItemManager> {
            val stringList: List<String> = Json.decodeFromString(jsonString)
            val itemDataList = stringList.map { jsonItem ->
                Json.decodeFromString<ItemData>(jsonItem)
            }

            val itemManagerList = mutableListOf<ItemManager>()

            itemDataList.forEach { itemData ->
                val itemStack = decodeItemStack(itemData.itemNbt, registries)
                if (!itemStack.isEmpty) {
                    itemManagerList.add(
                        ItemManager(
                            ItemCost(
                                BuiltInRegistries.ITEM.wrapAsHolder(itemStack.item),
                                itemData.sellPerTime,
                                DataComponentExactPredicate.allOf(itemStack.components),
                                itemStack
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

        // ── Container (shulker box / bundle) helpers ─────────────────────────

        /** Returns true if the item is a shulker box (any colour) or a bundle. */
        fun isContainer(stack: ItemStack): Boolean =
            stack.item is net.minecraft.world.item.BlockItem &&
                    (stack.item as net.minecraft.world.item.BlockItem).block is net.minecraft.world.level.block.ShulkerBoxBlock ||
            stack.item == Items.BUNDLE

        /**
         * Reads the items stored inside a container ItemStack
         * (shulker box or bundle) using DataComponents.CONTAINER /
         * DataComponents.BUNDLE_CONTENTS.
         * Returns a merged map: itemKey → (representative stack, total count).
         */
        fun getItemsInsideContainerStack(container: ItemStack): LinkedHashMap<String, Pair<ItemStack, Int>> {
            val merged = linkedMapOf<String, Pair<ItemStack, Int>>()

            fun addStack(s: ItemStack) {
                if (s.isEmpty) return
                val key = BuiltInRegistries.ITEM.getId(s.item).toString() + "|" + s.components.toString()
                val ex = merged[key]
                merged[key] = if (ex == null) Pair(s.copy(), s.count) else Pair(ex.first, ex.second + s.count)
            }

            // Shulker box stores items in CONTAINER component
            container.get(DataComponents.CONTAINER)?.nonEmptyItemCopyStream()?.forEach { addStack(it) }
            // Bundle stores items in BUNDLE_CONTENTS component
            container.get(DataComponents.BUNDLE_CONTENTS)?.itemCopyStream()?.forEach { addStack(it) }

            return merged
        }

        /**
         * Scans the player's inventory for all container stacks (shulker boxes / bundles).
         * Returns a list of (invSlotIndex, containerStack) for display in the container picker.
         */
        fun findContainersInInventory(player: Player): List<Pair<Int, ItemStack>> {
            val result = mutableListOf<Pair<Int, ItemStack>>()
            val inv = player.inventory
            for (slot in 0 until inv.containerSize) {
                val stack = inv.getItem(slot)
                if (!stack.isEmpty && isContainer(stack)) result.add(Pair(slot, stack.copy()))
            }
            return result
        }

        /**
         * Scans the world within [range] blocks of [origin] for placed shulker box block entities.
         * Returns a list of (BlockPos, merged item map) for each shulker box found.
         */
        fun findShulkerBoxesInWorld(
            world: ServerLevel,
            origin: BlockPos,
            range: Int = 8
        ): List<Pair<BlockPos, LinkedHashMap<String, Pair<ItemStack, Int>>>> {
            val result = mutableListOf<Pair<BlockPos, LinkedHashMap<String, Pair<ItemStack, Int>>>>()
            for (dx in -range..range) for (dy in -range..range) for (dz in -range..range) {
                val pos = origin.offset(dx, dy, dz)
                val be = world.getBlockEntity(pos)
                if (be is net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity) {
                    val merged = linkedMapOf<String, Pair<ItemStack, Int>>()
                    for (slot in 0 until be.containerSize) {
                        val stack = be.getItem(slot)
                        if (stack.isEmpty) continue
                        val key = BuiltInRegistries.ITEM.getId(stack.item).toString() + "|" + stack.components.toString()
                        val ex = merged[key]
                        merged[key] = if (ex == null) Pair(stack.copy(), stack.count) else Pair(ex.first, ex.second + stack.count)
                    }
                    if (merged.isNotEmpty()) result.add(Pair(pos, merged))
                }
            }
            return result
        }

        /**
         * Removes [quantity] matching items from shulker boxes in the player's inventory.
         * Returns the number actually removed.
         */
        fun removeItemFromShulkerBoxesInInventory(
            player: Player,
            itemToRemove: ItemStack,
            quantity: Int
        ): Int {
            var remaining = quantity
            val inv = player.inventory
            for (slot in 0 until inv.containerSize) {
                if (remaining <= 0) break
                val containerStack = inv.getItem(slot)
                if (containerStack.isEmpty || !isContainer(containerStack)) continue
                val containerItems = containerStack.get(DataComponents.CONTAINER) ?: continue
                // We need to mutate the container's contents
                val stacks = containerItems.nonEmptyItemCopyStream().toList().toMutableList()
                var modified = false
                for (inner in stacks) {
                    if (remaining <= 0) break
                    if (inner.components == itemToRemove.components) {
                        val toRemove = min(remaining, inner.count)
                        inner.shrink(toRemove)
                        remaining -= toRemove
                        modified = true
                    }
                }
                if (modified) {
                    // Rebuild the container component with updated stacks
                    val newItems = net.minecraft.world.item.component.ItemContainerContents.fromItems(
                        stacks.filter { !it.isEmpty }
                    )
                    containerStack.set(DataComponents.CONTAINER, newItems)
                }
            }
            return quantity - remaining
        }

        /**
         * Returns a merged map of all items currently stored in [player]'s ender chest.
         */
        fun getItemsInEnderChest(player: net.minecraft.server.level.ServerPlayer): LinkedHashMap<String, Pair<ItemStack, Int>> {
            val merged = linkedMapOf<String, Pair<ItemStack, Int>>()
            val inv = player.enderChestInventory
            for (slot in 0 until inv.containerSize) {
                val stack = inv.getItem(slot)
                if (stack.isEmpty) continue
                val key = BuiltInRegistries.ITEM.getId(stack.item).toString() + "|" + stack.components.toString()
                val ex = merged[key]
                merged[key] = if (ex == null) Pair(stack.copy(), stack.count) else Pair(ex.first, ex.second + stack.count)
            }
            return merged
        }

        /**
         * Removes [quantity] matching items from [player]'s ender chest inventory.
         * Returns the number actually removed.
         */
        fun removeItemFromEnderChest(
            player: net.minecraft.server.level.ServerPlayer,
            itemToRemove: ItemStack,
            quantity: Int
        ): Int {
            val inv = player.enderChestInventory
            var remaining = quantity
            for (slot in 0 until inv.containerSize) {
                if (remaining <= 0) break
                val stack = inv.getItem(slot)
                if (stack.isEmpty || stack.components != itemToRemove.components) continue
                val toRemove = min(remaining, stack.count)
                stack.shrink(toRemove)
                remaining -= toRemove
                inv.setChanged()
            }
            return quantity - remaining
        }

        /**
         * Removes [quantity] matching items from a placed shulker box block entity.
         * Returns the number actually removed.
         */
        fun removeItemFromShulkerBoxBlockEntity(
            world: ServerLevel,
            pos: BlockPos,
            itemToRemove: ItemStack,
            quantity: Int
        ): Int {
            val be = world.getBlockEntity(pos) as? net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity ?: return 0
            var remaining = quantity
            for (slot in 0 until be.containerSize) {
                if (remaining <= 0) break
                val stack = be.getItem(slot)
                if (stack.isEmpty || stack.components != itemToRemove.components) continue
                val toRemove = min(remaining, stack.count)
                stack.shrink(toRemove)
                remaining -= toRemove
                be.setChanged()
            }
            return quantity - remaining
        }
    }
}
