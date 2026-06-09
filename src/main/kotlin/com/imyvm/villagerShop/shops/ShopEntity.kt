package com.imyvm.villagerShop.shops

import com.imyvm.villagerShop.VillagerShopMain.Companion.itemList
import com.imyvm.villagerShop.VillagerShopMain.Companion.shopDBService
import com.imyvm.villagerShop.apis.EconomyData
import com.imyvm.villagerShop.apis.ShopService.Companion.ShopType
import com.imyvm.villagerShop.apis.Translator.tr
import com.imyvm.villagerShop.apis.customScope
import com.imyvm.villagerShop.items.ItemManager
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.context.CommandContext
import kotlinx.coroutines.launch
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos
import net.minecraft.commands.arguments.item.ItemInput
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.item.ItemStack
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.core.BlockPos
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
        context: CommandContext<CommandSourceStack>,
        type: ShopType
    ) : this(
        id = -1,
        shopname = getString(context, "shopName"),
        posX = getBlockPos(context, "pos").x,
        posY = getBlockPos(context, "pos").y,
        posZ = getBlockPos(context, "pos").z,
        world = context.source.player!!.level().dimension().identifier().toString(),
        admin = 1,
        type = type,
        owner = context.source.player!!.scoreboardName,
        ownerUUID = context.source.player!!.uuid,
        items = mutableListOf(),
        income = 0.0
    )
//    ) {
//        val sellItemListString = getString(context, "items")
//        val (compare, itemList, errorMessage) = checkParameterLegality(sellItemListString, context.source.registryAccess())
//        when (compare) {
//            0 -> throw SimpleCommandExceptionType(tr("commands.shop.create.no_item")).create()
//            1 -> throw SimpleCommandExceptionType(tr("commands.shop.create.count_not_equal")).create()
//            2 -> throw SimpleCommandExceptionType(tr("commands.shop.create.nbt_error", errorMessage)).create()
//            else -> this.items = itemList
//        }
//    }

    constructor(
        context: CommandContext<CommandSourceStack>,
    ) : this(
        id = -1,
        shopname = getString(context, "shopName"),
        posX = getBlockPos(context, "pos").x,
        posY = getBlockPos(context, "pos").y,
        posZ = getBlockPos(context, "pos").z,
        world = context.source.player!!.level().dimension().identifier().toString(),
        admin = 0,
        type = ShopType.SELL,
        owner = context.source.player!!.scoreboardName,
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

    fun addTradeOffer(tradeItem: ItemManager, player: ServerPlayer) {
        this.items.add(tradeItem)
        player.sendSystemMessage(tr("commands.shop.item.add.success"))
        updateAsync()
    }

    fun info(player: ServerPlayer) {
        sendMessageByType(this, player)
        val itemInfo = this.items
        for (item in itemInfo) {
            player.sendSystemMessage(
                tr("commands.shopinfo.items",
                    item.item.itemStack.hoverName,
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

    fun deleteAsync() {
        customScope.launch {
            shopDBService.dbQueryAsync { shopDBService.delete(this@ShopEntity.id) }
        }
    }

    fun setAdmin() {
        this.admin = 1
        updateAsync()
    }

    fun spawnOrRespawn(world: ServerLevel): Villager {
        return spawnInvulnerableVillager(
            BlockPos(this.posX, this.posY, this.posZ),
            world,
            this.shopname,
            this.type.ordinal,
            this.id
        )
    }

    fun getTradedItem(tradeItem: ItemInput): ItemManager? {
        return this.items.find {
            it.item.item.value() == tradeItem.item &&
                    it.item.itemStack.components == tradeItem.createItemStack(1).components
        }
    }

    fun getTradedItem(tradeItem: ItemStack): ItemManager? {
        return this.items.find {
            it.item.item.value() == tradeItem.item &&
                    it.item.itemStack.components == tradeItem.components
        }
    }

    fun deleteTradedItem(itemToRemove: ItemInput) {
        this.items.removeIf {
            it.item.item.value() == itemToRemove.item &&
                    it.item.itemStack.components == itemToRemove.createItemStack(1).components
        }
        updateAsync()
    }

    fun deleteTradedItem(itemToRemove: ItemStack) {
        this.items.removeIf {
            it.item.item.value() == itemToRemove.item &&
                    it.item.itemStack.components == itemToRemove.components
        }
        updateAsync()
    }

    fun update() {
        shopDBService.update(this)
    }

    fun updateAsync() {
        customScope.launch {
            shopDBService.dbQueryAsync { shopDBService.update(this@ShopEntity) }
        }
    }

    companion object {
        fun checkCanCreateShop(
            shopName: String,
            playerName: String,
            registryAccess: CommandBuildContext
        ): Boolean {
            return shopDBService.readByShopName(shopName, playerName, registryAccess).singleOrNull() == null
        }

        fun checkPlayerMoney(
            player: ServerPlayer,
            registryAccess: CommandBuildContext
        ): Long {
            val shopCount = shopDBService.readByOwner(player.scoreboardName, registryAccess).size
            val amount = if (shopCount < 3) {
                40L
            } else {
                2.0.pow(shopCount - 1).toLong()
            } * 100

            val sourceData = EconomyData(player)
            return if (sourceData.isLargerThanTheAmountOfMoney(amount)) amount else 0L
        }

        fun calculateAndTakeMoney(
            player: ServerPlayer,
            amount: Long
        ) {
            val playerEconomyData = EconomyData(player)

            playerEconomyData.takeMoney(amount)
            player.sendSystemMessage(tr("commands.balance.consume", amount / 100))
        }

        fun checkCanAddTradeOffer(
            shop: ShopEntity,
            tradeItem: ItemManager,
            player: ServerPlayer
        ): Boolean {
            shop.items.let {
                if (it.isNotEmpty()) {
                    if (shop.items.contains(tradeItem)) {
                        player.sendSystemMessage(tr("commands.playershop.add.repeat"))
                        return false
                    }
                    if (shop.items.size == 7) {
                        player.sendSystemMessage(tr("commands.playershop.item.limit"))
                        return false
                    }
                }
            }

            for (i in itemList) {
                if ((i.item == tradeItem.item) && i.price.toLong() <= tradeItem.price / tradeItem.sellPerTime * 0.8) {
                    player.sendSystemMessage(tr("commands.shop.create.item.price.toolow", tradeItem.item.itemStack.hoverName))
                    return false
                }
            }
            return true
        }

        fun sendMessageByType(shopInfo: ShopEntity, player: ServerPlayer) {
            player.sendSystemMessage(tr("commands.shopinfo.id", shopInfo.id))
            player.sendSystemMessage(tr("commands.shopinfo.shopname", shopInfo.shopname))
            player.sendSystemMessage(tr("commands.shopinfo.owner", shopInfo.owner))
            player.sendSystemMessage(tr("commands.shopinfo.pos", "${shopInfo.posX}, ${shopInfo.posY}, ${shopInfo.posZ}"))
        }

        fun getDefaultShop(): ShopEntity {
            return ShopEntity(
                -1, "default", 0, 0, 0, "default", 0, ShopType.SELL, "default", UUID.randomUUID(), mutableListOf(), 0.0
            )
        }
    }
}
