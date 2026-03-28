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
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.command.argument.BlockPosArgumentType.blockPos
import net.minecraft.command.argument.BlockPosArgumentType.getBlockPos
import net.minecraft.command.argument.ItemStackArgumentType.getItemStackArgument
import net.minecraft.command.argument.ItemStackArgumentType.itemStack
import net.minecraft.item.ItemStack
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

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

private fun maybeSendQuotedNameHint(player: ServerPlayerEntity, rawName: String) {
    if (rawName.contains(' ') || rawName.any { it.code > 127 }) {
        player.sendMessage(tr("commands.shop.name.quote_hint"))
    }
}

private fun findShopByName(
    name: String,
    player: ServerPlayerEntity,
    context: CommandContext<ServerCommandSource>
): ShopEntity? {
    val normalized = normalizeShopLookupName(name)
    return shopDBService.readByShopName(normalized, player.nameForScoreboard, context.source.registryManager).firstOrNull()
        ?: if (normalized != name) {
            shopDBService.readByShopName(name, player.nameForScoreboard, context.source.registryManager).firstOrNull()
        } else null
        ?: shopDBService.readByOwner(player.nameForScoreboard, context.source.registryManager)
            .firstOrNull { normalizeShopLookupName(it.shopname) == normalized }
}

private fun findShopByNameOrNotify(
    name: String,
    player: ServerPlayerEntity,
    context: CommandContext<ServerCommandSource>
): ShopEntity? {
    val shop = findShopByName(name, player, context)
    if (shop == null) {
        player.sendMessage(tr("commands.shops.none"))
        maybeSendQuotedNameHint(player, name)
    }
    return shop
}

/** Looks up any shop by numeric ID (admin use). */
private fun findShopById(
    id: Int,
    context: CommandContext<ServerCommandSource>
): ShopEntity? = shopDBService.readById(id, context.source.registryManager)

/**
 * Returns the ItemStack to operate on: the explicit [itemArg] if provided,
 * otherwise the player's main-hand item. Sends an error and returns null if
 * the main hand is empty.
 */
private fun resolveItem(
    context: CommandContext<ServerCommandSource>,
    player: ServerPlayerEntity,
    itemArg: String? = null
): ItemStack? {
    if (itemArg != null) return getItemStackArgument(context, itemArg).createStack(1, false)
    val held = player.mainHandStack
    if (held.isEmpty) { player.sendMessage(tr("commands.shop.create.no_item")); return null }
    return held.copyWithCount(1)
}

// ─── Command registration ────────────────────────────────────────────────────

fun register(
    dispatcher: CommandDispatcher<ServerCommandSource>,
    registryAccess: CommandRegistryAccess
) {
    val builder = literal("villagerShop")
        .requires(ServerCommandSource::isExecutedByPlayer)
        .executes { context -> openCreateGui(context, registryAccess) }

        // ── /vs create  →  open GUI directly ──────────────────────────────
        // (no args: GUI; with args: legacy command-line flow preserved)
        .then(literal("create")
            .executes { context ->
                openCreateGui(context, registryAccess)
            }
            .then(literal("adminShop")
                .requires { source ->
                    Permissions.check(source, VillagerShopMain.MOD_ID + ".admin", 3) &&
                        !tempShops.containsKey(source.player!!.uuid)
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
                                player.sendMessage(tr("commands.shop.create.define.success",
                                    CommandUtil.getSuggestCommandText("/villagerShop create addItem")))
                                refreshCommandTree(player)
                                1
                            }
                        )
                    )
                )
            )
            .then(literal("shop")
                .requires { source -> !tempShops.containsKey(source.player!!.uuid) }
                .executes { context -> openCreateGui(context, registryAccess) }
                .then(argument("shopName", string())
                    .then(argument("pos", blockPos())
                        .executes { context ->
                            val player = context.source.player!!
                            val shopName = getString(context, "shopName")
                            if (!checkCanCreateShop(shopName, player.nameForScoreboard, registryAccess)) {
                                player.sendMessage(tr("commands.shop.create.name_used")); return@executes 1
                            }
                            val amount = checkPlayerMoney(player, registryAccess)
                            if (amount <= 0) {
                                player.sendMessage(tr("commands.shop.create.failed.lack")); return@executes 1
                            }
                            val newShop = ShopEntity(context)
                            tempShops[player.uuid] = TempShop(newShop, amount, 0)
                            newShop.info(player)
                            player.sendMessage(tr("commands.shop.create.define.success",
                                CommandUtil.getSuggestCommandText("/villagerShop create addItem")))
                            refreshCommandTree(player)
                            1
                        }
                    )
                )
            )
            .then(literal("addItem")
                .requires { source -> source.isExecutedByPlayer && tempShops.containsKey(source.player!!.uuid) }
                .executes { context -> openCreateGui(context, registryAccess) }
                .then(argument("item", itemStack(registryAccess))
                    .then(argument("quantitySoldEachTime", integer(1, 99))
                        .then(argument("price", doubleArg(0.1))
                            // Admin branch: explicit stock
                            .then(argument("stock", integer(0))
                                .requires { source ->
                                    Permissions.check(source, VillagerShopMain.MOD_ID + ".admin", 3) &&
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
                                    getItemStackArgument(context, "item"),
                                    getInteger(context, "quantitySoldEachTime"),
                                    getDouble(context, "price"),
                                    mutableMapOf("default" to 0),
                                    context.source.registryManager
                                )
                                if (!checkCanAddTradeOffer(tempShop.shopEntity, candidate, player)) return@executes 1
                                val stock = removeItemFromInventory(
                                    player,
                                    getItemStackArgument(context, "item").createStack(1, false),
                                    getInteger(context, "quantitySoldEachTime")
                                )
                                addItemToTempShop(context, stock)
                            }
                        )
                    )
                )
            )
            .then(literal("removeItem")
                .requires { source -> source.isExecutedByPlayer && tempShops.containsKey(source.player!!.uuid) }
                .executes { context -> openCreateGui(context, registryAccess) }
                .then(argument("item", itemStack(registryAccess))
                    .executes { context ->
                        val player = context.source.player!!
                        val shopEntity = tempShops[player.uuid]!!.shopEntity
                        val toDelete: net.minecraft.command.argument.ItemStackArgument = getItemStackArgument(context, "item")
                        val itemInShop = shopEntity.getTradedItem(toDelete)
                        val stockBack = itemInShop?.stock?.get("default") ?: 0
                        shopEntity.deleteTradedItem(toDelete)
                        player.sendMessage(tr("commands.shop.item.delete.success",
                            toDelete.createStack(1, false).toHoverableText()))
                        if (stockBack > 0)
                            player.inventory.offerOrDrop(ItemStack(itemInShop?.item?.item, stockBack))
                        1
                    }
                )
            )
            .then(literal("preview")
                .requires { source -> source.isExecutedByPlayer && tempShops.containsKey(source.player!!.uuid) }
                .executes { context ->
                    tempShops[context.source.player!!.uuid]!!.shopEntity.info(context.source.player!!)
                    1
                }
            )
            .then(literal("cancel")
                .requires { source -> source.isExecutedByPlayer && tempShops.containsKey(source.player!!.uuid) }
                .executes { context ->
                    val player = context.source.player!!
                    val tempShop = tempShops.remove(player.uuid)!!
                    offerItemToPlayer(player, tempShop.shopEntity.items)
                    player.sendMessage(tr("commands.shop.create.cancelled"))
                    refreshCommandTree(player)
                    1
                }
            )
            .then(literal("submit")
                .requires { source -> source.isExecutedByPlayer && tempShops.containsKey(source.player!!.uuid) }
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
                            shopEntityList.getOrDefault(shop.id, null)?.kill()
                            synchronized(shopEntityList) { shopEntityList.remove(shop.id) }
                            shop.deleteAsync()
                            offerItemToPlayer(player, shop.items)
                            player.sendMessage(tr("commands.deleteshop.ok"))
                        }
                        1
                    }
                )
                .then(argument("id", integer(1))
                    .requires(Permissions.require(VillagerShopMain.MOD_ID + ".manager", 2))
                    .executes { context ->
                        val player = context.source.player!!
                        val shopId = getInteger(context, "id")
                        val shop = findShopById(shopId, context)
                            ?: run { player.sendMessage(tr("commands.shops.none")); return@executes 1 }
                        addPendingOperation(context) {
                            shopEntityList.getOrDefault(shopId, null)?.kill()
                            synchronized(shopEntityList) { shopEntityList.remove(shopId) }
                            shop.deleteAsync()
                            player.sendMessage(tr("commands.deleteshop.ok"))
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
                                player.sendMessage(tr("commands.shops.none"))
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
                            shopEntityList.getOrDefault(shop.id, null)?.customName = Text.of(shop.shopname)
                            player.sendMessage(tr("commands.execute.success"))
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
                                shopEntityList.getOrDefault(shop.id, null)?.customName = Text.of(shop.shopname)
                                player.sendMessage(tr("commands.execute.success"))
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
                .requires { source -> !tempShops.containsKey(source.player!!.uuid) }
                .executes { context ->
                    val player = context.source.player!!
                    ShopManageGui(player, registryAccess).open()
                    player.sendMessage(tr("gui.manage.stock.entry_hint"))
                    1
                }
                .then(argument("shopName", string())
                    .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                    .then(argument("item", itemStack(registryAccess))
                        .then(argument("amount", integer(1))
                            .executes { context ->
                                addStock(context,
                                    getItemStackArgument(context, "item").createStack(1, false),
                                    getInteger(context, "amount"))
                            }
                        )
                        .executes { context ->
                            val itemStack = getItemStackArgument(context, "item").createStack(1, false)
                            val player = context.source.player!!
                            addStock(context, itemStack,
                                player.inventory.count(getItemStackArgument(context, "item").item))
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
                        addStock(context, held, player.inventory.count(held.item))
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
                            addStockForShopName(context, getString(context, "shopName"), held, player.inventory.count(held.item))
                        }
                    )
                )
                .then(literal("item")
                    .executes { context -> openManageGui(context, registryAccess) }
                    .then(argument("item", itemStack(registryAccess))
                        .then(argument("amount", integer(1))
                            .then(argument("shopName", greedyString())
                                .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                                .executes { context ->
                                    addStockForShopName(
                                        context,
                                        getString(context, "shopName"),
                                        getItemStackArgument(context, "item").createStack(1, false),
                                        getInteger(context, "amount")
                                    )
                                }
                            )
                        )
                        .then(argument("shopName", greedyString())
                            .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                            .executes { context ->
                                val itemStack = getItemStackArgument(context, "item").createStack(1, false)
                                val player = context.source.player!!
                                addStockForShopName(context, getString(context, "shopName"), itemStack,
                                    player.inventory.count(getItemStackArgument(context, "item").item))
                            }
                        )
                    )
                )
            )

            .then(literal("item")
                .requires { source -> !tempShops.containsKey(source.player!!.uuid) }
                .executes { context -> openManageGui(context, registryAccess) }
                .then(literal("add")
                    .executes { context -> openManageGui(context, registryAccess) }
                    .then(argument("item", itemStack(registryAccess))
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
                                                getItemStackArgument(context, "item"),
                                                getInteger(context, "qty"),
                                                getDouble(context, "price"),
                                                registries = context.source.registryManager
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
                    .then(argument("item", itemStack(registryAccess))
                        .then(literal("from")
                            .then(argument("shopName", greedyString())
                                .suggests { context, builder -> suggestPlayerShopNames(context, builder, registryAccess) }
                                .executes { context ->
                                    val player = context.source.player!!
                                    val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                                        ?: return@executes 1
                                    removeItemFromShop(context, player, shop,
                                        getItemStackArgument(context, "item").createStack(1, false))
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
                    .then(argument("item", itemStack(registryAccess))
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
                                                getItemStackArgument(context, "item") as net.minecraft.command.argument.ItemStackArgument)
                                                ?: run { player.sendMessage(tr("commands.shop.item.none")); return@executes 1 }
                                            toChange.sellPerTime = getInteger(context, "qty")
                                            toChange.price = getDouble(context, "price")
                                            shop.updateAsync()
                                            player.sendMessage(tr("commands.shop.item.change.success"))
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
                        .then(argument("item", itemStack(registryAccess))
                            .then(argument("qty", integer(1))
                                .then(argument("price", doubleArg(0.1))
                                    .executes { context ->
                                        val player = context.source.player!!
                                        val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                                            ?: return@executes 1
                                        val newItem = ItemManager(
                                            getItemStackArgument(context, "item"),
                                            getInteger(context, "qty"),
                                            getDouble(context, "price"),
                                            registries = context.source.registryManager
                                        )
                                        if (checkCanAddTradeOffer(shop, newItem, player)) shop.addTradeOffer(newItem, player)
                                        1
                                    }
                                )
                            )
                        )
                    )
                    .then(literal("remove")
                        .then(argument("item", itemStack(registryAccess))
                            .executes { context ->
                                val player = context.source.player!!
                                val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                                    ?: return@executes 1
                                removeItemFromShop(context, player, shop,
                                    getItemStackArgument(context, "item").createStack(1, false))
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
                        .then(argument("item", itemStack(registryAccess))
                            .then(argument("qty", integer(1))
                                .then(argument("price", doubleArg(0.1))
                                    .executes { context ->
                                        val player = context.source.player!!
                                        val shop = findShopByNameOrNotify(getString(context, "shopName"), player, context)
                                            ?: return@executes 1
                                        val toChange = shop.getTradedItem(
                                            getItemStackArgument(context, "item") as net.minecraft.command.argument.ItemStackArgument)
                                            ?: run { player.sendMessage(tr("commands.shop.item.none")); return@executes 1 }
                                        toChange.sellPerTime = getInteger(context, "qty")
                                        toChange.price = getDouble(context, "price")
                                        shop.updateAsync()
                                        player.sendMessage(tr("commands.shop.item.change.success"))
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
                .requires { source -> Permissions.check(source, VillagerShopMain.MOD_ID + ".admin", 3) }
                .then(argument("rate", doubleArg())
                    .executes { context -> taxRateChange(context, getDouble(context, "rate")) }
                )
            )
            .then(literal("reload")
                .requires { source -> Permissions.check(source, VillagerShopMain.MOD_ID + ".admin", 3) }
                .executes { context -> reload(context) }
            )
            .then(literal("respawn")
                .requires { source ->
                    Permissions.check(source, VillagerShopMain.MOD_ID + ".admin", 3) &&
                        !tempShops.containsKey(source.player!!.uuid)
                }
                .then(argument("id", integer(1))
                    .executes { context ->
                        val player = context.source.player!!
                        val shop = findShopById(getInteger(context, "id"), context)
                            ?: run { player.sendMessage(tr("commands.search.none")); return@executes 1 }
                        val world = context.source.world
                        if (world.registryKey.value.toString() == shop.world) {
                            shop.spawnOrRespawn(world)
                            player.sendMessage(tr("commands.execute.success"))
                        } else {
                            player.sendMessage(tr("commands.failed"))
                        }
                        1
                    }
                )
            )
            .then(literal("search")
                .requires(Permissions.require(VillagerShopMain.MOD_ID + ".manage", 2))
                .then(argument("condition", greedyString())
                    .executes { context -> rangeSearch(context, getString(context, "condition")) }
                )
            )
            .then(literal("setAdmin")
                .requires(Permissions.require(VillagerShopMain.MOD_ID + ".admin", 3))
                .then(argument("id", integer(1))
                    .executes { context ->
                        val shop = findShopById(getInteger(context, "id"), context)
                            ?: run {
                                context.source.sendFeedback(Supplier { tr("commands.shops.none") }, true)
                                return@executes 1
                            }
                        addPendingOperation(context) {
                            shop.setAdmin()
                            context.source.sendFeedback(Supplier { tr("commands.setadmin.ok") }, true)
                        }
                        1
                    }
                )
            )
        )

        // ── /vs confirm / /vs cancel  ─────────────────────────────────────
        .then(literal("confirm")
            .requires { source -> pendingOperations.containsKey(source.player!!.uuid) }
            .executes { context ->
                val player = context.source.player!!
                val uuid = player.uuid
                val op = pendingOperations.remove(uuid)
                if (op != null) {
                    op.operation()
                    context.source.sendFeedback(Supplier { tr("commands.confirm.ok") }, false)
                } else {
                    context.source.sendFeedback(Supplier { tr("commands.confirm.none") }, false)
                }
                cancelPendingOperationJob(uuid)
                refreshCommandTree(player)
                1
            }
        )
        .then(literal("cancel")
            .requires { source -> pendingOperations.containsKey(source.player!!.uuid) }
            .executes { context ->
                val player = context.source.player!!
                val uuid = player.uuid
                val hadOp = pendingOperations.remove(uuid) != null
                context.source.sendFeedback(
                    Supplier { if (hadOp) tr("commands.cancel.ok") else tr("commands.cancel.none") },
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

private fun openCreateGui(context: CommandContext<ServerCommandSource>, registryAccess: CommandRegistryAccess): Int {
    ShopCreateGui(context.source.player!!, registryAccess).open()
    return 1
}

private fun openManageGui(context: CommandContext<ServerCommandSource>, registryAccess: CommandRegistryAccess): Int {
    ShopManageGui(context.source.player!!, registryAccess).open()
    return 1
}

private fun suggestPlayerShopNames(
    context: CommandContext<ServerCommandSource>,
    builder: SuggestionsBuilder,
    registryAccess: CommandRegistryAccess
): CompletableFuture<Suggestions> {
    val player = context.source.player ?: return builder.buildFuture()
    shopDBService.readByOwner(player.nameForScoreboard, registryAccess)
        .forEach { builder.suggest(it.shopname) }
    return builder.buildFuture()
}

/** Adds an item to the active temp shop. Assumes stock has already been resolved. */
private fun addItemToTempShop(context: CommandContext<ServerCommandSource>, stock: Int): Int {
    val player = context.source.player!!
    val tempShop = tempShops[player.uuid]!!
    tempShop.shopEntity.addTradeOffer(
        ItemManager(
            getItemStackArgument(context, "item"),
            getInteger(context, "quantitySoldEachTime"),
            getDouble(context, "price"),
            mutableMapOf("default" to stock),
            context.source.registryManager
        ),
        player
    )
    player.sendMessage(tr("commands.shop.item.add.success"))
    player.sendMessage(tr("commands.shop.item.continue.or.submit",
        CommandUtil.getSuggestCommandText("/villagerShop create addItem"),
        CommandUtil.getSuggestCommandText("/villagerShop create submit")))
    player.sendMessage(tr("commands.shop.item.remove",
        CommandUtil.getSuggestCommandText("/villagerShop create removeItem")))
    return 1
}

/** Submits the temp shop immediately, without a confirm step. */
private fun submitTempShop(context: CommandContext<ServerCommandSource>): Int {
    val player = context.source.player!!
    val tempShop = tempShops[player.uuid]!!
    val shopEntity = tempShop.shopEntity

    if (tempShop.shopType == 0) {
        calculateAndTakeMoney(player, tempShop.takeMoney)
        shopEntity.playerShopCreate()
    } else if (Permissions.check(context.source, VillagerShopMain.MOD_ID + ".admin", 3)) {
        shopEntity.adminShopCreate()
    } else {
        player.sendMessage(tr("commands.shop.create.premission"))
        return 1
    }

    val newShopEntity = shopEntity.spawnOrRespawn(context.source.world)
    synchronized(shopEntityList) { shopEntityList[shopEntity.id] = newShopEntity }
    tempShops.remove(player.uuid)
    player.sendMessage(tr("commands.shop.create.success"))
    refreshCommandTree(player)
    return 1
}

/** Adds stock to a shop item, taking items from the player's inventory. */
private fun addStock(
    context: CommandContext<ServerCommandSource>,
    itemStack: ItemStack,
    requestedAmount: Int
): Int {
    val player = context.source.player!!
    val shopName = getString(context, "shopName")
    return addStockForShopName(context, shopName, itemStack, requestedAmount)
}

private fun addStockForShopName(
    context: CommandContext<ServerCommandSource>,
    shopName: String,
    itemStack: ItemStack,
    requestedAmount: Int
): Int {
    val player = context.source.player!!
    val shop = findShopByNameOrNotify(shopName, player, context) ?: return 1
    val traded = shop.getTradedItem(itemStack)
        ?: run { player.sendMessage(tr("commands.shop.create.no_item")); return 1 }
    val taken = removeItemFromInventory(player, itemStack.copyWithCount(1), requestedAmount)
    traded.stock["default"] = (traded.stock["default"] ?: 0) + taken
    shop.updateAsync()
    player.sendMessage(tr("commands.stock.add.ok", taken))
    return 1
}

/** Removes an item from a shop with a confirm prompt, returning stock to player. */
private fun removeItemFromShop(
    context: CommandContext<ServerCommandSource>,
    player: ServerPlayerEntity,
    shop: ShopEntity,
    itemStack: ItemStack
): Int {
    val itemInShop = shop.getTradedItem(itemStack)
        ?: run { player.sendMessage(tr("commands.shop.item.none")); return 1 }
    val stockBack = itemInShop.stock["default"] ?: 0
    addPendingOperation(context) {
        shop.deleteTradedItem(itemStack)
        player.sendMessage(tr("commands.shop.item.delete.success", itemStack.toHoverableText()))
        if (stockBack > 0)
            player.inventory.offerOrDrop(ItemStack(itemInShop.item.item, stockBack))
        shop.updateAsync()
    }
    return 1
}

private fun shopPosChange(
    context: CommandContext<ServerCommandSource>,
    player: ServerPlayerEntity,
    shop: ShopEntity?,
    posArgName: String = "newShopPos"
): Int {
    shop ?: run { player.sendMessage(tr("commands.shops.none")); return 1 }
    val newPos = getBlockPos(context, posArgName)
    shop.posX = newPos.x; shop.posY = newPos.y; shop.posZ = newPos.z
    shop.updateAsync()
    shopEntityList.getOrDefault(shop.id, null)?.setPos(
        newPos.x + 0.5, newPos.y + 1.0, newPos.z + 0.5
    )
    player.sendMessage(tr("commands.execute.success"))
    return 1
}

private fun refreshCommandTree(player: ServerPlayerEntity) {
    player.server.execute { player.server.playerManager.sendCommandTree(player) }
}

private fun addPendingOperation(context: CommandContext<ServerCommandSource>, operation: () -> Unit) {
    val player = context.source.player!!
    val uuid = player.uuid
    if (pendingOperations.containsKey(uuid)) {
        context.source.sendError(tr("commands.confirm.already.have"))
    } else {
        pendingOperations[uuid] = PendingOperation(uuid, operation)
        player.sendMessage(tr("commands.confirm.need",
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