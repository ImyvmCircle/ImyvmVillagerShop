package com.imyvm.villagerShop.items

import com.imyvm.villagerShop.apis.Translator.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.command.argument.ItemStackArgument
import net.minecraft.component.DataComponentTypes
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.nbt.StringNbtReader
import net.minecraft.predicate.ComponentPredicate
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
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

        fun storeItemList(itemList: MutableList<ItemManager>): String {
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

        // ── Container (shulker box / bundle) helpers ─────────────────────────

        /** Returns true if the item is a shulker box (any colour) or a bundle. */
        fun isContainer(stack: ItemStack): Boolean =
            stack.item is net.minecraft.item.BlockItem &&
                    (stack.item as net.minecraft.item.BlockItem).block is net.minecraft.block.ShulkerBoxBlock ||
            stack.isOf(Items.BUNDLE)

        /**
         * Reads the items stored inside a container ItemStack
         * (shulker box or bundle) using DataComponentTypes.CONTAINER /
         * DataComponentTypes.BUNDLE_CONTENTS.
         * Returns a merged map: itemKey → (representative stack, total count).
         */
        fun getItemsInsideContainerStack(container: ItemStack): LinkedHashMap<String, Pair<ItemStack, Int>> {
            val merged = linkedMapOf<String, Pair<ItemStack, Int>>()

            fun addStack(s: ItemStack) {
                if (s.isEmpty) return
                val key = Registries.ITEM.getId(s.item).toString() + "|" + s.components.toString()
                val ex = merged[key]
                merged[key] = if (ex == null) Pair(s.copy(), s.count) else Pair(ex.first, ex.second + s.count)
            }

            // Shulker box stores items in CONTAINER component
            container.get(DataComponentTypes.CONTAINER)?.iterateNonEmpty()?.forEach { addStack(it) }
            // Bundle stores items in BUNDLE_CONTENTS component
            container.get(DataComponentTypes.BUNDLE_CONTENTS)?.iterate()?.forEach { addStack(it) }

            return merged
        }

        /**
         * Scans the player's inventory for all container stacks (shulker boxes / bundles).
         * Returns a list of (invSlotIndex, containerStack) for display in the container picker.
         */
        fun findContainersInInventory(player: PlayerEntity): List<Pair<Int, ItemStack>> {
            val result = mutableListOf<Pair<Int, ItemStack>>()
            val inv = player.inventory
            for (slot in 0 until inv.size()) {
                val stack = inv.getStack(slot)
                if (!stack.isEmpty && isContainer(stack)) result.add(Pair(slot, stack.copy()))
            }
            return result
        }

        /**
         * Scans the world within [range] blocks of [origin] for placed shulker box block entities.
         * Returns a list of (BlockPos, merged item map) for each shulker box found.
         */
        fun findShulkerBoxesInWorld(
            world: ServerWorld,
            origin: BlockPos,
            range: Int = 8
        ): List<Pair<BlockPos, LinkedHashMap<String, Pair<ItemStack, Int>>>> {
            val result = mutableListOf<Pair<BlockPos, LinkedHashMap<String, Pair<ItemStack, Int>>>>()
            for (dx in -range..range) for (dy in -range..range) for (dz in -range..range) {
                val pos = origin.add(dx, dy, dz)
                val be = world.getBlockEntity(pos)
                if (be is net.minecraft.block.entity.ShulkerBoxBlockEntity) {
                    val merged = linkedMapOf<String, Pair<ItemStack, Int>>()
                    for (slot in 0 until be.size()) {
                        val stack = be.getStack(slot)
                        if (stack.isEmpty) continue
                        val key = Registries.ITEM.getId(stack.item).toString() + "|" + stack.components.toString()
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
            player: PlayerEntity,
            itemToRemove: ItemStack,
            quantity: Int
        ): Int {
            var remaining = quantity
            val inv = player.inventory
            for (slot in 0 until inv.size()) {
                if (remaining <= 0) break
                val containerStack = inv.getStack(slot)
                if (containerStack.isEmpty || !isContainer(containerStack)) continue
                val containerItems = containerStack.get(DataComponentTypes.CONTAINER) ?: continue
                // We need to mutate the container's contents
                val stacks = containerItems.iterateNonEmpty().toMutableList()
                var modified = false
                for (inner in stacks) {
                    if (remaining <= 0) break
                    if (inner.components == itemToRemove.components) {
                        val toRemove = min(remaining, inner.count)
                        inner.decrement(toRemove)
                        remaining -= toRemove
                        modified = true
                    }
                }
                if (modified) {
                    // Rebuild the container component with updated stacks
                    val newItems = net.minecraft.component.type.ContainerComponent.fromStacks(
                        containerItems.iterateNonEmpty().toList()
                    )
                    containerStack.set(DataComponentTypes.CONTAINER, newItems)
                }
            }
            return quantity - remaining
        }

        /**
         * Returns a merged map of all items currently stored in [player]'s ender chest.
         */
        fun getItemsInEnderChest(player: net.minecraft.server.network.ServerPlayerEntity): LinkedHashMap<String, Pair<ItemStack, Int>> {
            val merged = linkedMapOf<String, Pair<ItemStack, Int>>()
            val inv = player.enderChestInventory
            for (slot in 0 until inv.size()) {
                val stack = inv.getStack(slot)
                if (stack.isEmpty) continue
                val key = Registries.ITEM.getId(stack.item).toString() + "|" + stack.components.toString()
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
            player: net.minecraft.server.network.ServerPlayerEntity,
            itemToRemove: ItemStack,
            quantity: Int
        ): Int {
            val inv = player.enderChestInventory
            var remaining = quantity
            for (slot in 0 until inv.size()) {
                if (remaining <= 0) break
                val stack = inv.getStack(slot)
                if (stack.isEmpty || stack.components != itemToRemove.components) continue
                val toRemove = min(remaining, stack.count)
                stack.decrement(toRemove)
                remaining -= toRemove
                inv.markDirty()
            }
            return quantity - remaining
        }

        /**
         * Removes [quantity] matching items from a placed shulker box block entity.
         * Returns the number actually removed.
         */
        fun removeItemFromShulkerBoxBlockEntity(
            world: ServerWorld,
            pos: BlockPos,
            itemToRemove: ItemStack,
            quantity: Int
        ): Int {
            val be = world.getBlockEntity(pos) as? net.minecraft.block.entity.ShulkerBoxBlockEntity ?: return 0
            var remaining = quantity
            for (slot in 0 until be.size()) {
                if (remaining <= 0) break
                val stack = be.getStack(slot)
                if (stack.isEmpty || stack.components != itemToRemove.components) continue
                val toRemove = min(remaining, stack.count)
                stack.decrement(toRemove)
                remaining -= toRemove
                be.markDirty()
            }
            return quantity - remaining
        }
    }
}