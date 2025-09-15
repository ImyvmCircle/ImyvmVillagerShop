package com.imyvm.villagerShop.commands

import com.imyvm.hoki.util.CommandUtil
import com.imyvm.villagerShop.VillagerShopMain
import com.imyvm.villagerShop.VillagerShopMain.Companion.shopDBService
import com.imyvm.villagerShop.VillagerShopMain.Companion.shopEntityList
import com.imyvm.villagerShop.apis.ShopService.Companion.ShopType
import com.imyvm.villagerShop.apis.ShopService.Companion.rangeSearch
import com.imyvm.villagerShop.apis.Translator.tr
import com.imyvm.villagerShop.apis.coroutineScope
import com.imyvm.villagerShop.apis.customScope
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
import kotlinx.coroutines.cancel
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
fun register(dispatcher: CommandDispatcher<ServerCommandSource>,
             registryAccess: CommandRegistryAccess) {
        val builder = literal("villagerShop")
        .requires(ServerCommandSource::isExecutedByPlayer)
            .then(literal("create")
                .then(literal("adminShop")
                    .requires{ source ->
                        Permissions.check(source, VillagerShopMain.MOD_ID + ".admin", 3) &&
                                tempShops.containsKey(source.player!!.uuid).not()
                    }
                    .then(argument("shopName", string())
                        .then(argument("pos", blockPos())
                            .then(argument("type", string())
                                .suggests { _, builder ->
                                    suggestOptions(builder, ShopType.entries.map { it.name })
                                }
                                .executes { context ->
                                    val newShop = ShopEntity(context, ShopType.valueOf(getString(context, "type")))
                                    val player = context.source.player!!
                                    tempShops[player.uuid] = TempShop(newShop, 0L, 1)
                                    newShop.info(player)
                                    val command = CommandUtil.getSuggestCommandText("/villagerShop create addItem")
                                    player.sendMessage(tr("commands.shop.create.define.success", command))
                                    refreshCommandTree(player)
                                    1
                                }
                            )
                        )
                    )
                )
                .then(literal("shop")
                    .requires { source ->
                        tempShops.containsKey(source.player!!.uuid).not()
                    }
                    .then(argument("shopName", string())
                        .then(argument("pos", blockPos())
                            .executes { context ->
                                val player = context.source.player!!
                                val amount = if (checkCanCreateShop(
                                    getString(context, "shopName"),
                                    player.nameForScoreboard,
                                    registryAccess
                                )) {
                                    checkPlayerMoney(player, registryAccess)
                                } else {
                                    player.sendMessage(tr("commands.shop.create.name_used"))
                                    return@executes 1
                                }
                                if (amount <= 0) {
                                    player.sendMessage(tr("commands.shop.create.failed.lack"))
                                    return@executes 1
                                }
                                val newShop = ShopEntity(context)
                                tempShops[player.uuid] = TempShop(newShop, amount, 0)
                                newShop.info(player)
                                val command = CommandUtil.getSuggestCommandText("/villagerShop create addItem")
                                player.sendMessage(tr("commands.shop.create.define.success", command))
                                refreshCommandTree(player)
                                1
                            }
                        )
                    )
                )
                .then(literal("addItem")
                    .requires { source ->
                        source.isExecutedByPlayer && tempShops.containsKey(source.player!!.uuid)
                    }
                    .then(argument("item", itemStack(registryAccess))
                        .then(argument("quantitySoldEachTime", integer(1, 99))
                            .then(argument("price", doubleArg(0.1))
                                .then(argument("stock", integer(0))
                                    .requires{ source ->
                                        Permissions.check(source, VillagerShopMain.MOD_ID + ".admin", 3) &&
                                                tempShops[source.player!!.uuid]!!.shopType == 1
                                    }
                                    .executes { context ->
                                        val player = context.source.player!!
                                        val tempShop = tempShops[player.uuid]!!
                                        val shopEntity = tempShop.shopEntity
                                        shopEntity.addTradeOffer(
                                            ItemManager(
                                                getItemStackArgument(context, "item"),
                                                getInteger(context, "quantitySoldEachTime"),
                                                getDouble(context, "price"),
                                                mutableMapOf(Pair("default", getInteger(context, "stock"))),
                                                context.source.registryManager
                                            ),
                                            player
                                        )
                                        player.sendMessage(tr("commands.shop.item.add.success"))
                                        val continueCommand = CommandUtil.getSuggestCommandText("/villagerShop create addItem")
                                        val submitCommand = CommandUtil.getSuggestCommandText("/villagerShop create submit")
                                        player.sendMessage(tr("commands.shop.item.continue.or.submit", continueCommand, submitCommand))
                                        val deleteCommand = CommandUtil.getSuggestCommandText("/villagerShop create removeItem")
                                        player.sendMessage(tr("commands.shop.item.remove", deleteCommand))
                                        1
                                    }
                                )
                            .executes { context ->
                                val player = context.source.player!!
                                val tempShop = tempShops[player.uuid]!!
                                val shopEntity = tempShop.shopEntity
                                if (checkCanAddTradeOffer(
                                    shopEntity,
                                    ItemManager(
                                        getItemStackArgument(context, "item"),
                                        getInteger(context, "quantitySoldEachTime"),
                                        getDouble(context, "price"),
                                        mutableMapOf(Pair("default", 0)),
                                        context.source.registryManager
                                    ),
                                    player
                                )) {
                                    val stock = removeItemFromInventory(
                                        player,
                                        getItemStackArgument(context, "item").createStack(1, false),
                                        getInteger(context, "quantitySoldEachTime")
                                    )
                                    shopEntity.addTradeOffer(
                                        ItemManager(
                                            getItemStackArgument(context, "item"),
                                            getInteger(context, "quantitySoldEachTime"),
                                            getDouble(context, "price"),
                                            mutableMapOf(Pair("default", stock)),
                                            context.source.registryManager
                                        ),
                                        player
                                    )
                                    tempShops[player.uuid] = TempShop(
                                        shopEntity,
                                        tempShop.takeMoney,
                                        tempShop.shopType
                                    )
                                    player.sendMessage(tr("commands.shop.item.add.success"))
                                    val continueCommand = CommandUtil.getSuggestCommandText("/villagerShop create addItem")
                                    val submitCommand = CommandUtil.getSuggestCommandText("/villagerShop create submit")
                                    player.sendMessage(tr("commands.shop.item.continue.or.submit", continueCommand, submitCommand))
                                    val deleteCommand = CommandUtil.getSuggestCommandText("/villagerShop create removeItem")
                                    player.sendMessage(tr("commands.shop.item.remove", deleteCommand))
                                }
                                1
                            })
                        )
                    )
                )
                .then(literal("removeItem")
                .requires { source ->
                        source.isExecutedByPlayer && tempShops.containsKey(source.player!!.uuid)
                    }
                    .then(argument("item", itemStack(registryAccess))
                        .executes { context ->
                            val player = context.source.player!!
                            val shopEntity = tempShops[player.uuid]!!.shopEntity
                            val tradedItemNeedDelete = getItemStackArgument(context, "item")
                            val itemInShop = shopEntity.getTradedItem(tradedItemNeedDelete)
                            val stockInShop = itemInShop?.stock?.get("default") ?: 0
                            shopEntity.deleteTradedItem(tradedItemNeedDelete)
                            player.sendMessage(tr("commands.shop.item.delete.success",
                                tradedItemNeedDelete.createStack(1, false).toHoverableText())
                            )
                            if (stockInShop > 0) {
                                player.inventory.offerOrDrop(
                                    ItemStack(
                                        itemInShop?.item?.item,
                                        itemInShop?.stock?.get("default") ?: 0
                                    )
                                )
                            }
                            1
                        }
                    )
                )
                .then(literal("preview")
                    .requires { source ->
                        source.isExecutedByPlayer && tempShops.containsKey(source.player!!.uuid)
                    }
                    .executes { context ->
                        val player = context.source.player!!
                        val tempShop = tempShops[player.uuid]!!
                        val shopEntity = tempShop.shopEntity
                        shopEntity.info(player)
                        1
                    }
                )
                .then(literal("cancel")
                    .requires { source ->
                        source.isExecutedByPlayer && tempShops.containsKey(source.player!!.uuid)
                    }
                    .executes { context ->
                        val player = context.source.player!!
                        val tempShop = tempShops[player.uuid]!!
                        val shopEntity = tempShop.shopEntity
                        val itemList = shopEntity.items
                        tempShops.remove(player.uuid)
                        player.sendMessage(tr("commands.shop.create.cancelled"))
                        offerItemToPlayer(player, itemList)
                        refreshCommandTree(player)
                        1
                    }
                )
                .then(literal("submit")
                    .requires { source ->
                        source.isExecutedByPlayer && tempShops.containsKey(source.player!!.uuid)
                    }
                    .executes { context ->
                        val player = context.source.player!!
                        val tempShop = tempShops[player.uuid]!!
                        val shopEntity = tempShop.shopEntity
                        val takeMoney = tempShop.takeMoney
                        val action = action@{
                            if (tempShop.shopType == 0) {
                                calculateAndTakeMoney(
                                    player,
                                    takeMoney
                                )
                                shopEntity.playerShopCreate()
                            } else if (Permissions.check(context.source, VillagerShopMain.MOD_ID + ".admin", 3)) {
                                shopEntity.adminShopCreate()
                            } else {
                                player.sendMessage(tr("commands.shop.create.premission"))
                                return@action
                            }
                            player.sendMessage(tr("commands.shop.create.success"))
                            val newShopEntity = shopEntity.spawnOrRespawn(context.source.world)
                            synchronized(shopEntityList) {
                                shopEntityList[shopEntity.id] = newShopEntity
                            }
                            tempShops.remove(player.uuid)
                            refreshCommandTree(player)
                        }
                        addPendingOperation(context, action)
                        1
                    }
                )
            )
            .then(literal("config")
                .requires { source ->
                    Permissions.check(source, VillagerShopMain.MOD_ID + ".admin", 3) &&
                        tempShops.containsKey(source.player!!.uuid).not()
                    }
                .then(literal("taxRate")
                    .then(literal("set")
                        .then(argument("taxRate", doubleArg())
                            .executes { context ->
                                taxRateChange(
                                    context,
                                    getDouble(context, "taxRate")
                                )
                            }
                        )
                    )
                )
                .then(literal("reload")
                    .executes{ context ->
                        reload(
                            context
                        )
                    }
                )
            )
            .then(literal("manager")
                .requires { source ->
                        tempShops.containsKey(source.player!!.uuid).not()
                    }
                .then(literal("changeInfo")
                    .then(literal("setShopName")
                        .then(argument("shopNameOld", string())
                            .then(argument("shopNameNew", string())
                                .executes { context ->
                                    val player = context.source.player!!
                                    val shop = shopDBService.readByShopName(
                                        getString(context, "shopNameOld"),
                                        player.nameForScoreboard,
                                        context.source.registryManager
                                    ).singleOrNull()
                                    shop?.let {
                                        shop.shopname = getString(context, "shopNameNew")
                                        shop.update()
                                        val shopEntity = shopEntityList.getOrDefault(it.id, null)
                                        shopEntity?.customName = Text.of(shop.shopname)
                                        context.source.player?.sendMessage(tr("commands.execute.success"))
                                    } ?: context.source.player?.sendMessage(tr("commands.shops.none"))
                                    1
                                }
                            )
                        )
                        .then(argument("id", integer())
                            .then(argument("shopNameNew", string())
                                .executes { context ->
                                    val player = context.source.player!!
                                    val shop = shopDBService.readById(
                                        getInteger(context, "id"),
                                        context.source.registryManager
                                    )
                                    shop?.let {
                                        shop.shopname = getString(context, "shopNameNew")
                                        shop.update()
                                        val shopEntity = shopEntityList.getOrDefault(it.id, null)
                                        shopEntity?.customName = Text.of(shop.shopname)
                                        player.sendMessage(tr("commands.execute.success"))
                                    } ?: player.sendMessage(tr("commands.shops.none"))
                                    1
                                }
                            )
                        )
                    )
                    .then(literal("setShopPos")
                        .then(argument("id", integer())
                            .then(argument("newShopPos", blockPos())
                                .executes { context ->
                                    val player = context.source.player!!
                                    val shop = shopDBService.readById(
                                        getInteger(context, "id"),
                                        context.source.registryManager
                                    )
                                    shopPosChange(context, player, shop)
                                }
                            )
                        )
                        .then(argument("shopName", string())
                            .then(argument("newShopPos", blockPos())
                                .executes { context ->
                                    val player = context.source.player!!
                                    val shop = shopDBService.readByShopName(
                                        getString(context, "shopName"),
                                        player.nameForScoreboard,
                                        context.source.registryManager
                                    ).singleOrNull()
                                    shopPosChange(context, player, shop)
                                }
                            )
                        )
                    )
                )
                .then(literal("delete")
                    .then(argument("id", integer(1))
                        .requires(Permissions.require(VillagerShopMain.MOD_ID + ".manager",2))
                        .executes { context ->
                            val shopId = getInteger(context, "id")
                            val shop = shopDBService.readById(
                                shopId,
                                context.source.registryManager
                            )
                            val player = context.source.player!!
                            shop?.let {
                                val action = {
                                    val shopEntity = shopEntityList.getOrDefault(shopId, null)
                                    shopEntity?.kill()
                                    synchronized(shopEntityList) {
                                        shopEntityList.remove(getInteger(context, "id"))
                                    }
                                    shop.delete()
                                    player.sendMessage(tr("commands.deleteshop.ok"))
                                }
                                addPendingOperation(context, action)
                            } ?: player.sendMessage(tr("commands.shops.none"))
                            1
                        }
                    )
                    .then(argument("shopName",string())
                        .executes { context ->
                            val player = context.source.player!!
                            val shop = shopDBService.readByShopName(
                                getString(context,"shopName"),
                                player.nameForScoreboard,
                                context.source.registryManager
                            ).singleOrNull()
                            shop?.let {
                                val action = {
                                    val shopEntity = shopEntityList.getOrDefault(it.id, null)
                                    shopEntity?.kill()
                                    synchronized(shopEntityList) {
                                        shopEntityList.remove(it.id)
                                    }
                                    shop.delete()
                                    offerItemToPlayer(player, it.items)
                                    player.sendMessage(tr("commands.deleteshop.ok"))
                                }
                                addPendingOperation(context, action)
                            } ?: player.sendMessage(tr("commands.shops.none"))
                            1
                        }
                    )
                )
                .then(literal("search")
                    .requires(Permissions.require(VillagerShopMain.MOD_ID + ".manage",2))
                    .then(argument("searchCondition", greedyString())
                        .executes { context ->
                            rangeSearch(
                                context,
                                getString(context, "searchCondition")
                            )
                        }
                    )
                )
                .then(literal("inquire")
                    .requires(Permissions.require(VillagerShopMain.MOD_ID + ".manage",2))
                    .then(argument("id", integer(1))
                        .executes { context ->
                            val player = context.source.player!!
                            val shop = shopDBService.readById(
                                getInteger(context, "id"),
                                context.source.registryManager
                            )
                            shop?.let {
                                shop.info(player)
                            } ?: player.sendMessage(tr("commands.search.none"))
                            1
                        }
                    )
                )
                .then(literal("setAdmin")
                    .requires(Permissions.require(VillagerShopMain.MOD_ID + ".admin",3))
                    .then(argument("id", integer(1))
                        .executes { context ->
                            val shop = shopDBService.readById(
                                getInteger(context, "id"),
                                context.source.registryManager
                            )
                            shop?.let {
                                val action = {
                                    shop.setAdmin()
                                    val textSupplier = Supplier<Text> { tr("commands.setadmin.ok") }
                                    context.source.sendFeedback(textSupplier,true)
                                }
                                addPendingOperation(context, action)
                            } ?: run {
                                val textSupplier = Supplier<Text> { tr("commands.shops.none") }
                                context.source.sendFeedback(textSupplier,true)
                            }
                            1
                        }
                    )
                )
            )
            .then(literal("respawn")
                .requires { source ->
                    Permissions.check(source, VillagerShopMain.MOD_ID + ".admin", 3) &&
                        tempShops.containsKey(source.player!!.uuid).not()
                    }
                .then(argument("id", integer(1))
                    .executes { context ->
                        val shop = shopDBService.readById(
                            getInteger(context, "id"),
                            context.source.registryManager
                        )
                        val world = context.source.world
                        val player = context.source.player!!
                        shop?.let {
                            if (world.registryKey.value.toString() == shop.world) {
                                shop.spawnOrRespawn(world)
                                player.sendMessage(tr("commands.execute.success"))
                            } else {
                                player.sendMessage(tr("commands.failed"))
                            }
                        } ?: player.sendMessage(tr("commands.search.none"))
                        1
                    }
                )
            )
            .then(literal("item")
                .requires { source ->
                        tempShops.containsKey(source.player!!.uuid).not()
                    }
                .then(literal("addStock")
                    .then(argument("shopName", string())
                        .then(argument("item", itemStack(registryAccess))
                            .executes { context ->
                                val player = context.source.player!!
                                val shop = shopDBService.readByShopName(
                                    getString(context, "shopName"),
                                    player.nameForScoreboard,
                                    context.source.registryManager
                                ).firstOrNull()
                                shop?.let {
                                    val tradeNeedChange = shop.getTradedItem(getItemStackArgument(context, "item"))
                                    tradeNeedChange?.let {
                                        val stockToAdd = removeItemFromInventory(
                                            player,
                                            getItemStackArgument(context, "item").createStack(1, false),
                                            player.inventory.count(getItemStackArgument(context, "item").item)
                                        )
                                        tradeNeedChange.stock["default"]?.let { stock ->
                                            if (stock == -1) stockToAdd + 1
                                            tradeNeedChange.stock["default"] = stock + stockToAdd
                                        }
                                        shop.update()
                                    } ?: player.sendMessage(tr("commands.shop.create.no_item"))
                                } ?: player.sendMessage(tr("commands.shops.none"))
                                1
                            }
                            .then(argument("addedStock", integer(1))
                                .executes { context ->
                                    val player = context.source.player!!
                                    val shop = shopDBService.readByShopName(
                                        getString(context, "shopName"),
                                        player.nameForScoreboard,
                                        context.source.registryManager
                                    ).firstOrNull()
                                    shop?.let {
                                        val tradeNeedChange = shop.getTradedItem(getItemStackArgument(context, "item"))
                                        tradeNeedChange?.let {
                                            val stockToAdd = removeItemFromInventory(
                                                player,
                                                getItemStackArgument(context, "item").createStack(1, false),
                                                getInteger(context, "addedStock")
                                            )
                                            tradeNeedChange.stock["default"]?.let { stock -> tradeNeedChange.stock["default"] = stock + stockToAdd }
                                            shop.update()
                                        } ?: player.sendMessage(tr("commands.shop.create.no_item"))
                                    } ?: player.sendMessage(tr("commands.shops.none"))
                                    1
                                }
                            )
                        )
                    )
                )
                .then(literal("add")
                    .then(argument("shopName", string())
                        .then(argument("item", itemStack(registryAccess))
                            .then(
                                argument("numberSoldEachTime", integer(1))
                                .then(argument("price", doubleArg(0.1))
                                    .executes { context ->
                                        val newTradedItem = ItemManager(
                                            getItemStackArgument(context, "item"),
                                            getInteger(context, "numberSoldEachTime"),
                                            getDouble(context, "price"),
                                            registries = context.source.registryManager
                                        )
                                        val player = context.source.player!!

                                        val shop = shopDBService.readByShopName(
                                            getString(context, "shopName"),
                                            player.nameForScoreboard,
                                            context.source.registryManager
                                        ).firstOrNull()
                                        shop?.let {
                                            if (checkCanAddTradeOffer(it, newTradedItem, player)) {
                                                it.addTradeOffer(newTradedItem, player)
                                            }
                                        } ?: player.sendMessage(tr("commands.shops.none"))
                                        1
                                    }
                                )
                            )
                        )
                    )
                )
                .then(literal("delete")
                    .then(argument("shopName", string())
                        .then(argument("item", itemStack(registryAccess))
                            .executes { context ->
                                val player = context.source.player!!
                                val shop = shopDBService.readByShopName(
                                    getString(context, "shopName"),
                                    player.nameForScoreboard,
                                    context.source.registryManager
                                ).firstOrNull()
                                val tradedItemNeedDelete = getItemStackArgument(context, "item")

                                shop?.let {
                                    val itemInShop = it.getTradedItem(tradedItemNeedDelete)
                                    val stockInShop = itemInShop?.stock?.get("default") ?: 0
                                    val action = {
                                        it.deleteTradedItem(tradedItemNeedDelete)
                                        player.sendMessage(tr("commands.shop.item.delete.success",
                                            tradedItemNeedDelete.createStack(1, false).toHoverableText())
                                        )
                                        if (stockInShop > 0) {
                                            player.inventory.offerOrDrop(
                                                ItemStack(
                                                    itemInShop?.item?.item,
                                                    itemInShop?.stock?.get("default") ?: 0
                                                )
                                            )
                                        }
                                        it.update()
                                    }
                                    addPendingOperation(context, action)
                                } ?: player.sendMessage(tr("commands.shops.none"))
                                1
                            }
                        )
                    )
                )
                .then(literal("change")
                    .then(argument("shopName", string())
                        .then(argument("item", itemStack(registryAccess))
                            .then(
                                argument("numberSoldEachTime", integer(1))
                                .then(argument("price", doubleArg(0.1))
                                    .executes { context ->
                                        val player = context.source.player!!
                                        val shop = shopDBService.readByShopName(
                                            getString(context, "shopName"),
                                            player.nameForScoreboard,
                                            context.source.registryManager
                                        ).singleOrNull()

                                        shop?.let {
                                            val tradedItemNeedChange = it.getTradedItem(getItemStackArgument(context, "item"))
                                            tradedItemNeedChange?.let { item ->
                                                item.sellPerTime = getInteger(context, "numberSoldEachTime")
                                                item.price = getDouble(context, "price")
                                                player.sendMessage(tr("commands.shop.item.change.success"))
                                            } ?: player.sendMessage(tr("commands.shop.item.none"))
                                            it.update()
                                        }
                                        1
                                    }
                                )
                            )
                        )
                    )
                )
            )
            .then(literal("confirm")
                .executes { context ->
                    val playerUUID = context.source.player?.uuid
                    val pendingOperation = pendingOperations.remove(playerUUID)
                    pendingOperation?.let {
                        it.operation()
                        val textSupplier = Supplier<Text> { tr("commands.confirm.ok") }
                        context.source.sendFeedback(textSupplier, false)
                    } ?: run {
                        val textSupplier = Supplier<Text> { tr("commands.confirm.none") }
                        context.source.sendFeedback(textSupplier, false)
                    }
                    customScope.cancel()
                    1
                }
            )
            .then(literal("cancel")
                .executes { context ->
                    val playerUUID = context.source.player?.uuid
                    val textSupplier: Supplier<Text> =
                    pendingOperations.remove(playerUUID)?.let {
                        Supplier<Text> { tr("commands.cancel.ok") }
                    } ?: run {
                        Supplier<Text> { tr("commands.cancel.none") }
                    }
                    context.source.sendFeedback(textSupplier, false)
                    customScope.cancel()
                    1
                }
            )
    val villagerShopCommandNode = dispatcher.register(builder)
    dispatcher.register(literal("vlsp").redirect(villagerShopCommandNode))
}

private fun shopPosChange(context: CommandContext<ServerCommandSource>, player: ServerPlayerEntity, shop: ShopEntity?) : Int {
    val newPos = getBlockPos(context, "newShopPos")

    shop?.let {
        shop.posX = newPos.x
        shop.posY = newPos.y
        shop.posZ = newPos.z
        shop.update()

        val shopEntity = shopEntityList.getOrDefault(it.id, null)
        shopEntity?.setPos(
            newPos.x.toDouble() + 0.5,
            newPos.y.toDouble() + 1,
            newPos.z.toDouble() + 0.5
        )
        player.sendMessage(tr("commands.execute.success"))
    } ?: player.sendMessage(tr("commands.shops.none"))
    return 1
}

private fun refreshCommandTree(player: ServerPlayerEntity) {
    player.server.execute {
        player.server.playerManager.sendCommandTree(player)
    }
}

private fun addPendingOperation(context: CommandContext<ServerCommandSource>, operation: () -> Unit) {
    val player = context.source.player!!
    val playerUUID = player.uuid
    if (pendingOperations.containsKey(playerUUID)) {
        context.source.sendError(tr("commands.confirm.already.have"))
    } else {
        pendingOperations[playerUUID] = PendingOperation(playerUUID, operation)
        val command = CommandUtil.getSuggestCommandText("/villagerShop confirm")
        player.sendMessage(tr("commands.confirm.need", command))
        coroutineScope(context)
    }
}

private fun suggestOptions(builder: SuggestionsBuilder, options: List<String>): CompletableFuture<Suggestions> {
    options.forEach { option ->
        builder.suggest(option)
    }
    return builder.buildFuture()
}