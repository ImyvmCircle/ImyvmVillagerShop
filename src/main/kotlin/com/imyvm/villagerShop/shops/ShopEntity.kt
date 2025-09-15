package com.imyvm.villagerShop.shops

import com.imyvm.villagerShop.VillagerShopMain.Companion.itemList
import com.imyvm.villagerShop.VillagerShopMain.Companion.shopDBService
import com.imyvm.villagerShop.apis.EconomyData
import com.imyvm.villagerShop.apis.ShopService.Companion.ShopType
import com.imyvm.villagerShop.apis.Translator.tr
import com.imyvm.villagerShop.items.ItemManager
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.context.CommandContext
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.command.argument.BlockPosArgumentType.getBlockPos
import net.minecraft.command.argument.ItemStackArgument
import net.minecraft.entity.passive.VillagerEntity
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import java.util.*
import kotlin.math.pow

class ShopEntity(
    var id: Int,
    var shopname: String,
    var posX: Int,
    var posY: Int,
    var posZ: Int,
    val world: String,
    var admin: Int,
    var type: ShopType,
    val owner: String,
    val ownerUUID: UUID,
    var items: MutableList<ItemManager>,
    var income: Double
) {
    constructor(
        context: CommandContext<ServerCommandSource>,
        type: ShopType
    ) : this(
        id = -1,
        shopname = getString(context, "shopName"),
        posX = getBlockPos(context, "pos").x,
        posY = getBlockPos(context, "pos").y,
        posZ = getBlockPos(context, "pos").z,
        world = context.source.player!!.world.registryKey.value.toString(),
        admin = 1,
        type = type,
        owner = context.source.player!!.nameForScoreboard,
        ownerUUID = context.source.player!!.uuid,
        items = mutableListOf(),
        income = 0.0
    )
//    ) {
//        val sellItemListString = getString(context, "items")
//        val (compare, itemList, errorMessage) = checkParameterLegality(sellItemListString, context.source.registryManager)
//        when (compare) {
//            0 -> throw SimpleCommandExceptionType(tr("commands.shop.create.no_item")).create()
//            1 -> throw SimpleCommandExceptionType(tr("commands.shop.create.count_not_equal")).create()
//            2 -> throw SimpleCommandExceptionType(tr("commands.shop.create.nbt_error", errorMessage)).create()
//            else -> this.items = itemList
//        }
//    }

    constructor(
        context: CommandContext<ServerCommandSource>,
    ) : this(
        id = -1,
        shopname = getString(context, "shopName"),
        posX = getBlockPos(context, "pos").x,
        posY = getBlockPos(context, "pos").y,
        posZ = getBlockPos(context, "pos").z,
        world = context.source.player!!.world.registryKey.value.toString(),
        admin = 0,
        type = ShopType.SELL,
        owner = context.source.player!!.nameForScoreboard,
        ownerUUID = context.source.player!!.uuid,
        items = mutableListOf(),
        income = 0.0
    )

    fun adminShopCreate() {
        this.id = shopDBService.create(this)
        if (type == ShopType.SELL || type == ShopType.REFRESHABLE_SELL) this.items.let { itemList.addAll(it) }
    }

    fun playerShopCreate() {
        this.id = shopDBService.create(this)
    }

    fun addTradeOffer(tradeItem: ItemManager, player: ServerPlayerEntity) {
        this.items.add(tradeItem)
        player.sendMessage(tr("commands.shop.item.add.success"))
        update()
    }

    fun info(player: ServerPlayerEntity) {
        sendMessageByType(this, player)
        val itemInfo = this.items
        for (item in itemInfo) {
            player.sendMessage(
                tr("commands.shopinfo.items",
                    item.item.itemStack.toHoverableText(),
                    item.sellPerTime,
                    item.price,
                    item.stock
                )
            )
        }
    }

    fun delete() {
        shopDBService.delete(this.id)
    }

    fun setAdmin() {
        this.admin = 1
        update()
    }

    fun spawnOrRespawn(world: ServerWorld): VillagerEntity {
        return spawnInvulnerableVillager(
            BlockPos(this.posX, this.posY, this.posZ),
            world,
            this.shopname,
            this.type.ordinal,
            this.id
        )
    }

    fun getTradedItem(tradeItem: ItemStackArgument): ItemManager? {
        return this.items.find {
            it.item.item.value() == tradeItem.item &&
                    it.item.itemStack.components == tradeItem.createStack(1, false).components
        }
    }

    fun deleteTradedItem(itemToRemove: ItemStackArgument) {
        this.items.removeIf {
            it.item.item.value() == itemToRemove.item &&
                    it.item.itemStack.components == itemToRemove.createStack(1, false).components
        }
        update()
    }

    fun update() {
        shopDBService.update(this)
    }

    companion object {
        fun checkCanCreateShop(
            shopName: String,
            playerName: String,
            registryAccess: CommandRegistryAccess
        ): Boolean {
            return shopDBService.readByShopName(shopName, playerName, registryAccess).singleOrNull() == null
        }

        fun checkPlayerMoney(
            player: ServerPlayerEntity,
            registryAccess: CommandRegistryAccess
        ): Long {
            val shopCount = shopDBService.readByOwner(player.nameForScoreboard, registryAccess).size
            val amount = if (shopCount < 3) {
                40L
            } else {
                2.0.pow(shopCount - 1).toLong()
            } * 100

            val sourceData = EconomyData(player)
            return if (sourceData.isLargerThanTheAmountOfMoney(amount)) amount else 0L
        }

        fun calculateAndTakeMoney(
            player: ServerPlayerEntity,
            amount: Long
        ) {
            val playerEconomyData = EconomyData(player)

            playerEconomyData.takeMoney(amount)
            player.sendMessage(tr("commands.balance.consume", amount / 100))
        }

        fun checkCanAddTradeOffer(
            shop: ShopEntity,
            tradeItem: ItemManager,
            player: ServerPlayerEntity
        ): Boolean {
            shop.items.let {
                if (it.isNotEmpty()) {
                    if (shop.items.contains(tradeItem)) {
                        player.sendMessage(tr("commands.playershop.add.repeat"))
                        return false
                    }
                    if (shop.items.size == 7) {
                        player.sendMessage(tr("commands.playershop.item.limit"))
                        return false
                    }
                }
            }

            for (i in itemList) {
                if ((i.item == tradeItem.item) && i.price.toLong() <= tradeItem.price / tradeItem.sellPerTime * 0.8) {
                    player.sendMessage(tr("commands.shop.create.item.price.toolow", tradeItem.item.itemStack.toHoverableText()))
                    return false
                }
            }
            return true
        }

        fun sendMessageByType(shopInfo: ShopEntity, player: ServerPlayerEntity) {
            player.sendMessage(tr("commands.shopinfo.id", shopInfo.id))
            player.sendMessage(tr("commands.shopinfo.shopname", shopInfo.shopname))
            player.sendMessage(tr("commands.shopinfo.owner", shopInfo.owner))
            player.sendMessage(tr("commands.shopinfo.pos", "${shopInfo.posX}, ${shopInfo.posY}, ${shopInfo.posZ}"))
        }

        fun getDefaultShop(): ShopEntity {
            return ShopEntity(
                -1, "default", 0, 0, 0, "default", 0, ShopType.SELL, "default", UUID.randomUUID(), mutableListOf(), 0.0
            )
        }
    }
}
