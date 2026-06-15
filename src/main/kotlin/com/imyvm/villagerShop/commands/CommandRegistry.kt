package com.imyvm.villagerShop.commands

import com.imyvm.hoki.util.CommandUtil
import com.imyvm.villagerShop.VillagerShopMain
import com.imyvm.villagerShop.VillagerShopMain.Companion.shopDBService
import com.imyvm.villagerShop.VillagerShopMain.Companion.shopEntityList
import com.imyvm.villagerShop.apis.ShopService.Companion.ShopType
import com.imyvm.villagerShop.apis.ShopService.Companion.rangeSearch
import com.imyvm.villagerShop.apis.Translator.tr
import com.imyvm.villagerShop.apis.cancelPendingOperationJob
import com.imyvm.villagerShop.apis.coroutineScope
import com.imyvm.villagerShop.gui.PendingConfirmGui
import com.imyvm.villagerShop.gui.ShopCreateGui
import com.imyvm.villagerShop.gui.ShopManageGui
import com.imyvm.villagerShop.items.ItemManager
import com.imyvm.villagerShop.items.ItemManager.Companion.countItemInInventory
import com.imyvm.villagerShop.items.ItemManager.Companion.offerItemToPlayer
import com.imyvm.villagerShop.items.ItemManager.Companion.removeItemFromInventory
import com.imyvm.villagerShop.shops.ShopEntity
import com.imyvm.villagerShop.shops.ShopEntity.Companion.calculateAndTakeMoney
import com.imyvm.villagerShop.shops.ShopEntity.Companion.checkCanAddTradeOffer
import com.imyvm.villagerShop.shops.ShopEntity.Companion.checkCanCreateShop
import com.imyvm.villagerShop.shops.ShopEntity.Companion.checkPlayerMoney
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg
import com.mojang.brigadier.arguments.DoubleArgumentType.getDouble
import com.mojang.brigadier.arguments.IntegerArgumentType.getInteger
import com.mojang.brigadier.arguments.IntegerArgumentType.integer
import com.mojang.brigadier.arguments.StringArgumentType.*
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos
import net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos
import net.minecraft.commands.arguments.item.ItemArgument.getItem
import net.minecraft.commands.arguments.item.ItemArgument.item
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.PermissionLevel
import net.minecraft.world.item.ItemStack
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

data class PendingOperation(val playerUuid: UUID, val operation: () -> Unit)
data class TempShop(val shopEntity: ShopEntity, val takeMoney: Long, val shopType: Int)

val pendingOperations = ConcurrentHashMap<UUID, PendingOperation>()
val tempShops = ConcurrentHashMap<UUID, TempShop>()

// ─── Shared helpers ──────────────────────────────────────────────────────────

/** Looks up a player's shop by name (owner-scoped). */
private fun normalizeShopLookupName(raw: String): String = raw.trim()
    .removeSurrounding("\"")
    .removeSurrounding("“", "”")
    .replace('\u3000', ' ')
    .replace(Regex("\\s+"), " ")

private fun maybeSendQuotedNameHint(player: ServerPlayer, rawName: String) {
    if (rawName.contains(' ') || rawName.any { it.code > 127 }) {
        player.sendSystemMessage(tr("commands.shop.name.quote_hint"))
    }
}

private fun findShopByName(
    name: String,
    player: ServerPlayer,
    context: CommandContext<CommandSourceStack>
): ShopEntity? {
    val registryAccess = context.source.registryAccess()
    val owner = player.scoreboardName
    val normalizedName = normalizeShopLookupName(name)
    shopDBService.readByShopName(normalizedName, owner, registryAccess).firstOrNull()?.let { return it }
    if (normalizedName != name) {
        shopDBService.readByShopName(name, owner, registryAccess).firstOrNull()?.let { return it }
    }
    return shopDBService.readByOwner(owner, registryAccess)
        .firstOrNull { normalizeShopLookupName(it.shopname) == normalizedName }
}

private fun findShopByNameOrNotify(
    name: String,
    player: ServerPlayer,
    context: CommandContext<CommandSourceStack>
): ShopEntity? {
    val shop = findShopByName(name, player, context)
    if (shop == null) {
        player.sendSystemMessage(tr("commands.shops.none"))
        maybeSendQuotedNameHint(player, name)
    }
    return shop
}

/** Looks up any shop by numeric ID (admin use). */
private fun findShopById(
    id: Int,
    context: CommandContext<CommandSourceStack>
): ShopEntity? = shopDBService.readById(id, context.source.registryAccess())

/**
 * Returns the ItemStack to operate on: the explicit [itemArg] if provided,
 * otherwise the player's main-hand item. Sends an error and returns null if
 * the main hand is empty.
 */
private fun resolveItem(
    context: CommandContext<CommandSourceStack>,
    player: ServerPlayer,
    itemArg: String? = null
): ItemStack? {
    if (itemArg != null) return getItem(context, itemArg).createItemStack(1)
    val held = player.mainHandItem
    if (held.isEmpty) { player.sendSystemMessage(tr("commands.shop.create.no_item")); return null }
    return held.copyWithCount(1)
}

// ─── Command registration ────────────────────────────────────────────────────

fun register(
    dispatcher: CommandDispatcher<CommandSourceStack>,
    registryAccess: CommandBuildContext
) {
    val builder = literal("villagerShop")
        .requires(CommandSourceStack::isPlayer)
        .executes { context -> openCreateGui(context, registryAccess) }

        // ── /vs create  →  open GUI directly ──────────────────────────────
        // (no args: GUI; with args: legacy command-line flow preserved)
        .then(literal("create")
            .executes { context ->
                openCreateGui(context, registryAccess)
            }
            .then(literal("adminShop")
                .requires { source ->
                    val player = source.player ?: return@requires true
                    Permissions.check(source, VillagerShopMain.MOD_ID + ".admin", PermissionLevel.ADMINS) &&
                        !tempShops.containsKey(player.uuid)
                }
                .executes { context -> openCreateGui(context, registryAccess) }
                .then(argument("shopName", string())
                    .then(argument("pos", blockPos())
                        .then(argument("type", string())
                            .suggests { _, b -> suggestOptions(b, ShopType.entries.map { it.name }) }
                            .executes { context ->
                                val player = context.source.player!!
                                val newShop = ShopEntity(context, ShopType.valueOf(getString(context, "type")))
                                tempShops[player.uuid] = TempShop(newShop, 0L, 1)
                                newShop.info(player)
                                player.sendSystemMessage(tr("commands.shop.create.define.success",
                                    CommandUtil.getSuggestCommandText("/villagerShop create addItem")))
                                refreshCommandTree(player)
                                1
                            }
                        )
                    )
                )
            )
            .then(literal("shop")
                .requires { source ->
                    val player = source.player ?: return@requires true
                    !tempShops.containsKey(player.uuid)
                }
                .executes { context -> openCreateGui(context, registryAccess) }
                .then(argument("shopName", string())
                    .then(argument("pos", blockPos())
                        .executes { context ->
                            val player = context.source.player!!
                            val shopName = getString(context, "shopName")
                            if (!checkCanCreateShop(shopName, player.scoreboardName, registryAccess)) {
                                player.sendSystemMessage(tr("commands.shop.create.name_used")); return@executes 1
                            }
                            val amount = checkPlayerMoney(player, registryAccess)
                            if (amount <= 0) {
                                player.sendSystemMessage(tr("commands.shop.create.failed.lack")); return@executes 1
                            }
                            val newShop = ShopEntity(context)
                            tempShops[player.uuid] = TempShop(newShop, amount, 0)
                            newShop.info(player)
                            player.sendSystemMessage(tr("commands.shop.create.define.success",
                                CommandUtil.getSuggestCommandText("/villagerShop create addItem")))
                            refreshCommandTree(player)
                            1
                        }
                    )
                )
            )
            .then(literal("addItem")
                .requires { source ->
                    val player = source.player ?: return@requires true
                    source.isPlayer && tempShops.containsKey(player.uuid)
                }
                .executes { context -> openCreateGui(context, registryAccess) }
                .then(argument("item", item(registryAccess))
                    .then(argument("quantitySoldEachTime", integer(1, 99))
                        .then(argument("price", doubleArg(0.1))
                            // Admin branch: explicit stock
                            .then(argument("stock", integer(0))
                                .requires { source ->
                                    Permissions.check(source, VillagerShopMain.MOD_ID + ".admin", PermissionLevel.ADMINS) &&
                                        tempShops[source.player!!.uuid]?.shopType == 1
                                }
                                .executes { context ->
                                    addItemToTempShop(context, getInteger(context, "stock"))
                                }
                            )
                            // Player branch: take from inventory
                            .executes { context ->
                                val player = context.source.player!!
                                val tempShop = tempShops[player.uuid]!!
                                val candidate = ItemManager(
                                    getItem(context, "item"),
                                    getInteger(context, "quantitySoldEachTime"),
                                    getDouble(context, "price"),
                                    mutableMapOf("default" to 0),
                                    context.source.registryAccess()
                                )
                                if (!checkCanAddTradeOffer(tempShop.shopEntity, candidate, player)) return@executes 1
                                val stock = removeItemFromInventory(
                                    player,
                                    getItem(context, "item").createItemStack(1),
                                    getInteger(context, "quantitySoldEachTime")
                                )
                                addItemToTempShop(context, stock)
                            }
                        )
                    )
                )
            )
            .then(literal("removeItem")
                .requires { source -> source.isPlayer && tempShops.containsKey(source.player!!.uuid) }
                .executes { context -> openCreateGui(context, registryAccess) }
                .then(argument("item", item(registryAccess))
                    .executes { context ->
                        val player = context.source.player!!
                        val shopEntity = tempShops[player.uuid]!!.shopEntity
                        val toDelete: net.minecraft.commands.arguments.item.ItemInput = getItem(context, "item")
                        val itemInShop = shopEntity.getTradedItem(toDelete)
                        val stockBack = itemInShop?.stock?.get("default") ?: 0
                        shopEntity.deleteTradedItem(toDelete)
                        player.sendSystemMessage(tr("commands.shop.item.delete.success",
                            toDelete.createItemStack(1).hoverName))
                        if (stockBack > 0)
                            player.inventory.placeItemBackInInventory(ItemStack(itemInShop!!.item.item.value(), stockBack))
                        1
                    }
                )
            )
            .then(literal("preview")
                .requires { source -> source.isPlayer && tempShops.containsKey(source.player!!.uuid) }
                .executes { context ->
                    tempShops[context.source.player!!.uuid]!!.shopEntity.info(context.source.player!!)
                    1
                }
            )
            .then(literal("cancel")
                .requires { source -> source.isPlayer && tempShops.containsKey(source.player!!.uuid) }
                .executes { context ->
                    val player = context.source.player!!
                    val tempShop = tempShops.remove(player.uuid)!!
                    offerItemToPlayer(player, tempShop.shopEntity.items)
                    player.sendSystemMessage(tr("commands.shop.create.cancelled"))
                    refreshCommandTree(player)
                    1
                }
            )
            .then(literal("submit")
                .requires { source -> source.isPlayer && tempShops.containsKey(source.player!!.uuid) }
                .executes { context ->
                    addPendingOperation(context) { submitTempShop(context) }
                    1
                }
            )
        )

        // ── /vs manager [shopName]  + grouped shop operations ───────────────
        .then(literal("manager")
            .executes { context -> openManageGui(context, registryAccess) }
            .then(argument("shopName", greedyString())
                .executes { context ->
                    val player = context.source.player!!
                    val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                        ?: return@executes 1
                    ShopManageGui(player, registryAccess).openFor(shop)
                    1
                }
            )

            .then(literal("delete")
                .executes { context -> openManageGui(context, registryAccess) }
                .then(argument("shopName", greedyString())
                    .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                    .executes { context ->
                        val player = context.source.player!!
                        val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                            ?: return@executes 1
                        addPendingOperation(context) {
                            shopEntityList.getOrDefault(shop.id, null)?.discard()
                            synchronized(shopEntityList) { shopEntityList.remove(shop.id) }
                            shop.deleteAsync()
                            offerItemToPlayer(player, shop.items)
                            player.sendSystemMessage(tr("commands.deleteshop.ok"))
                        }
                        1
                    }
                )
                .then(argument("id", integer(1))
                    .requires(Permissions.require(VillagerShopMain.MOD_ID + ".manager", PermissionLevel.GAMEMASTERS))
                    .executes { context ->
                        val player = context.source.player!!
                        val shopId = getInteger(context, "id")
                        val shop = findShopById(shopId, context)
                            ?: run { player.sendSystemMessage(tr("commands.shops.none")); return@executes 1 }
                        addPendingOperation(context) {
                            shopEntityList.getOrDefault(shopId, null)?.discard()
                            synchronized(shopEntityList) { shopEntityList.remove(shopId) }
                            shop.deleteAsync()
                            player.sendSystemMessage(tr("commands.deleteshop.ok"))
                        }
                        1
                    }
                )
            )

            .then(literal("info")
                .executes { context -> openManageGui(context, registryAccess) }
                .then(argument("shopName", greedyString())
                    .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                    .executes { context ->
                        val player = context.source.player!!
                        val shopNameArg = getString(context, "shopName")
                        (findShopByName(shopNameArg, player, context)
                            ?: findShopById(shopNameArg.toIntOrNull() ?: -1, context))
                            ?.info(player)
                            ?: run {
                                player.sendSystemMessage(tr("commands.shops.none"))
                                maybeSendQuotedNameHint(player, shopNameArg)
                            }
                        1
                    }
                )
            )

            .then(literal("rename")
                .executes { context -> openManageGui(context, registryAccess) }
                .then(argument("shopName", string())
                    .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                    .then(argument("newName", string())
                        .executes { context ->
                            val player = context.source.player!!
                            val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                                ?: return@executes 1
                            shop.shopname = getString(context, "newName")
                            shop.updateAsync()
                            shopEntityList.getOrDefault(shop.id, null)?.customName = Component.literal(shop.shopname)
                            player.sendSystemMessage(tr("commands.execute.success"))
                            1
                        }
                    )
                )
                .then(argument("newName", string())
                    .executes { context -> openManageGui(context, registryAccess) }
                    .then(literal("of")
                        .then(argument("shopName", greedyString())
                            .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                            .executes { context ->
                                val player = context.source.player!!
                                val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                                    ?: return@executes 1
                                shop.shopname = getString(context, "newName")
                                shop.updateAsync()
                                shopEntityList.getOrDefault(shop.id, null)?.customName = Component.literal(shop.shopname)
                                player.sendSystemMessage(tr("commands.execute.success"))
                                maybeSendQuotedNameHint(player, shop.shopname)
                                1
                            }
                        )
                    )
                )
            )

            .then(literal("move")
                .executes { context -> openManageGui(context, registryAccess) }
                .then(argument("shopName", string())
                    .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                    .then(argument("newPos", blockPos())
                        .executes { context ->
                            val player = context.source.player!!
                            val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                                ?: return@executes 1
                            shopPosChange(context, player, shop, "newPos")
                        }
                    )
                )
                .then(argument("newPos", blockPos())
                    .executes { context -> openManageGui(context, registryAccess) }
                    .then(literal("of")
                        .then(argument("shopName", greedyString())
                            .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                            .executes { context ->
                                val player = context.source.player!!
                                val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                                    ?: return@executes 1
                                shopPosChange(context, player, shop, "newPos")
                            }
                        )
                    )
                )
            )

            .then(literal("stock")
                .requires { source ->
                    val player = source.player ?: return@requires true
                    !tempShops.containsKey(player.uuid)
                }
                .executes { context ->
                    val player = context.source.player!!
                    ShopManageGui(player, registryAccess).open()
                    player.sendSystemMessage(tr("gui.manage.stock.entry_hint"))
                    1
                }
                .then(argument("shopName", string())
                    .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                    .then(argument("item", item(registryAccess))
                        .then(argument("amount", integer(1))
                            .executes { context ->
                                addStock(context,
                                    getItem(context, "item").createItemStack(1),
                                    getInteger(context, "amount"))
                            }
                        )
                        .executes { context ->
                            val itemStack = getItem(context, "item").createItemStack(1)
                            val player = context.source.player!!
                            addStock(context, itemStack,
                                countItemInInventory(player, getItem(context, "item").item.value()))
                        }
                    )
                    .then(argument("amount", integer(1))
                        .executes { context ->
                            val player = context.source.player!!
                            val held = resolveItem(context, player) ?: return@executes 1
                            addStock(context, held, getInteger(context, "amount"))
                        }
                    )
                    .executes { context ->
                        val player = context.source.player!!
                        val held = resolveItem(context, player) ?: return@executes 1
                        addStock(context, held, countItemInInventory(player, held.item))
                    }
                )
                .then(literal("hand")
                    .executes { context -> openManageGui(context, registryAccess) }
                    .then(argument("amount", integer(1))
                        .then(argument("shopName", greedyString())
                            .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                            .executes { context ->
                                val player = context.source.player!!
                                val held = resolveItem(context, player) ?: return@executes 1
                                addStockForShopName(context, getString(context, "shopName"), held, getInteger(context, "amount"))
                            }
                        )
                    )
                    .then(argument("shopName", greedyString())
                        .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                        .executes { context ->
                            val player = context.source.player!!
                            val held = resolveItem(context, player) ?: return@executes 1
                            addStockForShopName(context, getString(context, "shopName"), held, countItemInInventory(player, held.item))
                        }
                    )
                )
                .then(literal("item")
                    .executes { context -> openManageGui(context, registryAccess) }
                    .then(argument("item", item(registryAccess))
                        .then(argument("amount", integer(1))
                            .then(argument("shopName", greedyString())
                                .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                                .executes { context ->
                                    addStockForShopName(
                                        context,
                                        getString(context, "shopName"),
                                        getItem(context, "item").createItemStack(1),
                                        getInteger(context, "amount")
                                    )
                                }
                            )
                        )
                        .then(argument("shopName", greedyString())
                            .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                            .executes { context ->
                                val itemStack = getItem(context, "item").createItemStack(1)
                                val player = context.source.player!!
                                addStockForShopName(context, getString(context, "shopName"), itemStack,
                                    countItemInInventory(player, getItem(context, "item").item.value()))
                            }
                        )
                    )
                )
            )

            .then(literal("item")
                .requires { source ->
                    val player = source.player ?: return@requires true
                    !tempShops.containsKey(player.uuid)
                }
                .executes { context -> openManageGui(context, registryAccess) }
                .then(literal("add")
                    .executes { context -> openManageGui(context, registryAccess) }
                    .then(argument("item", item(registryAccess))
                        .then(argument("qty", integer(1))
                            .then(argument("price", doubleArg(0.1))
                                .then(literal("to")
                                    .then(argument("shopName", greedyString())
                                        .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                                        .executes { context ->
                                            val player = context.source.player!!
                                            val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                                                ?: return@executes 1
                                            val newItem = ItemManager(
                                                getItem(context, "item"),
                                                getInteger(context, "qty"),
                                                getDouble(context, "price"),
                                                registries = context.source.registryAccess()
                                            )
                                            if (checkCanAddTradeOffer(shop, newItem, player)) shop.addTradeOffer(newItem, player)
                                            1
                                        }
                                    )
                                )
                            )
                        )
                    )
                )
                .then(literal("remove")
                    .executes { context -> openManageGui(context, registryAccess) }
                    .then(argument("item", item(registryAccess))
                        .then(literal("from")
                            .then(argument("shopName", greedyString())
                                .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                                .executes { context ->
                                    val player = context.source.player!!
                                    val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                                        ?: return@executes 1
                                    removeItemFromShop(context, player, shop,
                                        getItem(context, "item").createItemStack(1))
                                }
                            )
                        )
                    )
                    .then(literal("from")
                        .then(argument("shopName", greedyString())
                            .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                            .executes { context ->
                                val player = context.source.player!!
                                val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                                    ?: return@executes 1
                                val held = resolveItem(context, player) ?: return@executes 1
                                removeItemFromShop(context, player, shop, held)
                            }
                        )
                    )
                )
                .then(literal("change")
                    .executes { context -> openManageGui(context, registryAccess) }
                    .then(argument("item", item(registryAccess))
                        .then(argument("qty", integer(1))
                            .then(argument("price", doubleArg(0.1))
                                .then(literal("in")
                                    .then(argument("shopName", greedyString())
                                        .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                                        .executes { context ->
                                            val player = context.source.player!!
                                            val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                                                ?: return@executes 1
                                            val toChange = shop.getTradedItem(
                                                getItem(context, "item")
                                            )
                                                ?: run { player.sendSystemMessage(tr("commands.shop.item.none")); return@executes 1 }
                                            toChange.sellPerTime = getInteger(context, "qty")
                                            toChange.price = getDouble(context, "price")
                                            shop.updateAsync()
                                            player.sendSystemMessage(tr("commands.shop.item.change.success"))
                                            1
                                        }
                                    )
                                )
                            )
                        )
                    )
                )
                .then(argument("shopName", string())
                    .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                    .then(literal("add")
                        .then(argument("item", item(registryAccess))
                            .then(argument("qty", integer(1))
                                .then(argument("price", doubleArg(0.1))
                                    .executes { context ->
                                        val player = context.source.player!!
                                        val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                                            ?: return@executes 1
                                        val newItem = ItemManager(
                                            getItem(context, "item"),
                                            getInteger(context, "qty"),
                                            getDouble(context, "price"),
                                            registries = context.source.registryAccess()
                                        )
                                        if (checkCanAddTradeOffer(shop, newItem, player)) shop.addTradeOffer(newItem, player)
                                        1
                                    }
                                )
                            )
                        )
                    )
                    .then(literal("remove")
                        .then(argument("item", item(registryAccess))
                            .executes { context ->
                                val player = context.source.player!!
                                val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                                    ?: return@executes 1
                                removeItemFromShop(context, player, shop,
                                    getItem(context, "item").createItemStack(1))
                            }
                        )
                        .executes { context ->
                            val player = context.source.player!!
                            val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                                ?: return@executes 1
                            val held = resolveItem(context, player) ?: return@executes 1
                            removeItemFromShop(context, player, shop, held)
                        }
                    )
                    .then(literal("change")
                        .then(argument("item", item(registryAccess))
                            .then(argument("qty", integer(1))
                                .then(argument("price", doubleArg(0.1))
                                    .executes { context ->
                                        val player = context.source.player!!
                                        val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                                            ?: return@executes 1
                                        val toChange = shop.getTradedItem(
                                            getItem(context, "item")
                                        )
                                            ?: run { player.sendSystemMessage(tr("commands.shop.item.none")); return@executes 1 }
                                        toChange.sellPerTime = getInteger(context, "qty")
                                        toChange.price = getDouble(context, "price")
                                        shop.updateAsync()
                                        player.sendSystemMessage(tr("commands.shop.item.change.success"))
                                        1
                                    }
                                )
                            )
                        )
                    )
                )
            )
        )

        // ── /vs admin ...  (CLI only) ───────────────────────────────────────
        .then(literal("admin")
            .then(literal("tax")
                .requires { source -> Permissions.check(source, VillagerShopMain.MOD_ID + ".admin", PermissionLevel.ADMINS) }
                .then(argument("rate", doubleArg())
                    .executes { context -> taxRateChange(context, getDouble(context, "rate")) }
                )
            )
            .then(literal("reload")
                .requires { source -> Permissions.check(source, VillagerShopMain.MOD_ID + ".admin", PermissionLevel.ADMINS) }
                .executes { context -> reload(context) }
            )
            .then(literal("respawn")
                .requires { source ->
                    val player = source.player ?: return@requires true
                    Permissions.check(source, VillagerShopMain.MOD_ID + ".admin", PermissionLevel.ADMINS) &&
                        !tempShops.containsKey(player.uuid)
                }
                .then(argument("id", integer(1))
                    .executes { context ->
                        val player = context.source.player!!
                        val shop = findShopById(getInteger(context, "id"), context)
                            ?: run { player.sendSystemMessage(tr("commands.search.none")); return@executes 1 }
                        val world = context.source.level
                        if (world.dimension().identifier().toString() == shop.world) {
                            shop.spawnOrRespawn(world)
                            player.sendSystemMessage(tr("commands.execute.success"))
                        } else {
                            player.sendSystemMessage(tr("commands.failed"))
                        }
                        1
                    }
                )
            )
            .then(literal("search")
                .requires(Permissions.require(VillagerShopMain.MOD_ID + ".manage", PermissionLevel.GAMEMASTERS))
                .then(argument("condition", greedyString())
                    .executes { context -> rangeSearch(context, getString(context, "condition")) }
                )
            )
            .then(literal("setAdmin")
                .requires(Permissions.require(VillagerShopMain.MOD_ID + ".admin", PermissionLevel.ADMINS))
                .then(argument("id", integer(1))
                    .executes { context ->
                        val shop = findShopById(getInteger(context, "id"), context)
                            ?: run {
                                context.source.sendSuccess({ tr("commands.shops.none") }, true)
                                return@executes 1
                            }
                        addPendingOperation(context) {
                            shop.setAdmin()
                            context.source.sendSuccess({ tr("commands.setadmin.ok") }, true)
                        }
                        1
                    }
                )
            )
        )

        // ── /vs confirm / /vs cancel  ─────────────────────────────────────
        .then(literal("confirm")
            .requires { source ->
                val player = source.player ?: return@requires true
                pendingOperations.containsKey(player.uuid)
            }
            .executes { context ->
                val player = context.source.player!!
                val uuid = player.uuid
                val op = pendingOperations.remove(uuid)
                if (op != null) {
                    op.operation()
                    context.source.sendSuccess({ tr("commands.confirm.ok") }, false)
                } else {
                    context.source.sendSuccess({ tr("commands.confirm.none") }, false)
                }
                cancelPendingOperationJob(uuid)
                refreshCommandTree(player)
                1
            }
        )
        .then(literal("cancel")
            .requires { source ->
                val player = source.player ?: return@requires true
                pendingOperations.containsKey(player.uuid) }
            .executes { context ->
                val player = context.source.player!!
                val uuid = player.uuid
                val hadOp = pendingOperations.remove(uuid) != null
                context.source.sendSuccess(
                    { if (hadOp) tr("commands.cancel.ok") else tr("commands.cancel.none") },
                    false
                )
                cancelPendingOperationJob(uuid)
                refreshCommandTree(player)
                1
            }
        )



    val villagerShopCommandNode = dispatcher.register(builder)
    dispatcher.register(literal("vlsp").redirect(villagerShopCommandNode))
}

// ─── Private helpers ─────────────────────────────────────────────────────────

private fun openCreateGui(context: CommandContext<CommandSourceStack>, registryAccess: CommandBuildContext): Int {
    ShopCreateGui(context.source.player!!, registryAccess).open()
    return 1
}

private fun openManageGui(context: CommandContext<CommandSourceStack>, registryAccess: CommandBuildContext): Int {
    ShopManageGui(context.source.player!!, registryAccess).open()
    return 1
}

private fun suggestPlayerShopNames(
    context: CommandContext<CommandSourceStack>,
    builder: SuggestionsBuilder,
    registryAccess: CommandBuildContext
): CompletableFuture<Suggestions> {
    val player = context.source.player ?: return builder.buildFuture()
    shopDBService.readByOwner(player.scoreboardName, registryAccess)
        .forEach { builder.suggest(it.shopname) }
    return builder.buildFuture()
}

/** Adds an item to the active temp shop. Assumes stock has already been resolved. */
private fun addItemToTempShop(context: CommandContext<CommandSourceStack>, stock: Int): Int {
    val player = context.source.player!!
    val tempShop = tempShops[player.uuid]!!
    tempShop.shopEntity.addTradeOffer(
        ItemManager(
            getItem(context, "item"),
            getInteger(context, "quantitySoldEachTime"),
            getDouble(context, "price"),
            mutableMapOf("default" to stock),
            context.source.registryAccess()
        ),
        player
    )
    player.sendSystemMessage(tr("commands.shop.item.add.success"))
    player.sendSystemMessage(tr("commands.shop.item.continue.or.submit",
        CommandUtil.getSuggestCommandText("/villagerShop create addItem"),
        CommandUtil.getSuggestCommandText("/villagerShop create submit")))
    player.sendSystemMessage(tr("commands.shop.item.remove",
        CommandUtil.getSuggestCommandText("/villagerShop create removeItem")))
    return 1
}

/** Submits the temp shop immediately, without a confirmation step. */
private fun submitTempShop(context: CommandContext<CommandSourceStack>): Int {
    val player = context.source.player!!
    val tempShop = tempShops[player.uuid]!!
    val shopEntity = tempShop.shopEntity

    if (tempShop.shopType == 0) {
        calculateAndTakeMoney(player, tempShop.takeMoney)
        shopEntity.playerShopCreate()
    } else if (Permissions.check(context.source, VillagerShopMain.MOD_ID + ".admin", PermissionLevel.ADMINS)) {
        shopEntity.adminShopCreate()
    } else {
        player.sendSystemMessage(tr("commands.shop.create.premission"))
        return 1
    }

    val newShopEntity = shopEntity.spawnOrRespawn(context.source.level)
    synchronized(shopEntityList) { shopEntityList[shopEntity.id] = newShopEntity }
    tempShops.remove(player.uuid)
    player.sendSystemMessage(tr("commands.shop.create.success"))
    refreshCommandTree(player)
    return 1
}

/** Adds stock to a shop item, taking items from the player's inventory. */
private fun addStock(
    context: CommandContext<CommandSourceStack>,
    itemStack: ItemStack,
    requestedAmount: Int
): Int {
    val shopName = getString(context, "shopName")
    return addStockForShopName(context, shopName, itemStack, requestedAmount)
}

private fun addStockForShopName(
    context: CommandContext<CommandSourceStack>,
    shopName: String,
    itemStack: ItemStack,
    requestedAmount: Int
): Int {
    val player = context.source.player!!
    val shop = findShopByNameOrNotify(shopName, player, context) ?: return 1
    val traded = shop.getTradedItem(itemStack)
        ?: run { player.sendSystemMessage(tr("commands.shop.create.no_item")); return 1 }
    val taken = removeItemFromInventory(player, itemStack.copyWithCount(1), requestedAmount)
    traded.stock["default"] = (traded.stock["default"] ?: 0) + taken
    shop.updateAsync()
    player.sendSystemMessage(tr("commands.stock.add.ok", taken))
    return 1
}

/** Removes an item from a shop with a confirmation prompt, returning stock to player. */
private fun removeItemFromShop(
    context: CommandContext<CommandSourceStack>,
    player: ServerPlayer,
    shop: ShopEntity,
    itemStack: ItemStack
): Int {
    val itemInShop = shop.getTradedItem(itemStack)
        ?: run { player.sendSystemMessage(tr("commands.shop.item.none")); return 1 }
    val stockBack = itemInShop.stock["default"] ?: 0
    addPendingOperation(context) {
        shop.deleteTradedItem(itemStack)
        player.sendSystemMessage(tr("commands.shop.item.delete.success", itemStack.hoverName))
        if (stockBack > 0)
            player.inventory.placeItemBackInInventory(ItemStack(itemInShop.item.item, stockBack))
        shop.updateAsync()
    }
    return 1
}

private fun shopPosChange(
    context: CommandContext<CommandSourceStack>,
    player: ServerPlayer,
    shop: ShopEntity?,
    posArgName: String = "newShopPos"
): Int {
    shop ?: run { player.sendSystemMessage(tr("commands.shops.none")); return 1 }
    val newPos = getBlockPos(context, posArgName)
    shop.posX = newPos.x; shop.posY = newPos.y; shop.posZ = newPos.z
    shop.updateAsync()
    shopEntityList.getOrDefault(shop.id, null)?.setPos(
        newPos.x + 0.5, newPos.y + 1.0, newPos.z + 0.5
    )
    player.sendSystemMessage(tr("commands.execute.success"))
    return 1
}

private fun refreshCommandTree(player: ServerPlayer) {
    player.level().server.execute { player.level().server.playerList.sendPlayerPermissionLevel(player) }
}

private fun addPendingOperation(context: CommandContext<CommandSourceStack>, operation: () -> Unit) {
    val player = context.source.player!!
    val uuid = player.uuid
    if (pendingOperations.containsKey(uuid)) {
        context.source.sendFailure(tr("commands.confirm.already.have"))
    } else {
        pendingOperations[uuid] = PendingOperation(uuid, operation)
        player.sendSystemMessage(tr("commands.confirm.need",
            CommandUtil.getSuggestCommandText("/villagerShop confirm")))
        PendingConfirmGui.open(player)
        refreshCommandTree(player)
        coroutineScope(context)
    }
}

private fun suggestOptions(builder: SuggestionsBuilder, options: List<String>): CompletableFuture<Suggestions> {
    options.forEach { builder.suggest(it) }
    return builder.buildFuture()
}
