package com.imyvm.villagerShop.gui

import com.imyvm.villagerShop.VillagerShopMain
import com.imyvm.villagerShop.VillagerShopMain.Companion.shopDBService
import com.imyvm.villagerShop.apis.EconomyData
import com.imyvm.villagerShop.apis.ShopService.Companion.ShopType
import com.imyvm.villagerShop.apis.Translator.tr
import com.imyvm.villagerShop.apis.customScope
import com.imyvm.villagerShop.items.ItemManager.Companion.countItemInInventory
import com.imyvm.villagerShop.shops.ShopEntity
import eu.pb4.sgui.api.ClickType
import eu.pb4.sgui.api.gui.MerchantGui
import kotlinx.coroutines.launch
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponentExactPredicate
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.trading.ItemCost
import net.minecraft.world.item.trading.MerchantOffer
import java.time.LocalDate
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.random.Random


class ShopTrade(private val playerEntity: ServerPlayer, private val registries: HolderLookup.Provider) {
    private val gui = object : MerchantGui(playerEntity, false) {
        override fun onAnyClick(index: Int, type: ClickType?, action: ContainerInput?): Boolean {
            if (index == 0 || index == 1) {
                return false
            } else if (index == 2 &&
                this.merchantInventory.getItem(2).item == this.selectedTrade?.result?.item &&
                action != ContainerInput.SWAP
            ) {
                if (shopEntity?.type == ShopType.SELL || shopEntity?.type == ShopType.REFRESHABLE_SELL) { // Sell
                    val imyvmCurry = this.merchantInventory.getItem(0)
                    val moneyShouldTakeOnce =
                        imyvmCurry.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getDouble("price")?.orElse(null) ?: return false
                    val stock = this.selectedTrade.maxUses - this.selectedTrade.uses
                    val sellItem = this.merchantInventory.getItem(2)
                    if (stock < sellItem.count) {
                        this.merchantInventory.removeItem(0, imyvmCurry.count)
                        player.sendSystemMessage(tr("shop.buy.stock.lack"))
                        return false
                    }
                    val economyData = EconomyData(player)
                    val playerBalance = economyData.getMoney()
                    if (playerBalance < moneyShouldTakeOnce) {
                        this.merchantInventory.removeItem(0, imyvmCurry.count)
                        player.sendSystemMessage(tr("shop.buy.money.lack", moneyShouldTakeOnce))
                        return false
                    }
                    val tradeTimes = if (action == ContainerInput.PICKUP && playerBalance >= moneyShouldTakeOnce) {
                        1
                    } else if (action == ContainerInput.QUICK_MOVE) {
                        if (playerBalance / moneyShouldTakeOnce >= 64) {
                            if (stock >= sellItem.count * 64) {
                                64
                            } else {
                                stock / sellItem.count
                            }
                        } else {
                            if ((playerBalance / moneyShouldTakeOnce).toInt() < stock) {
                                (playerBalance / moneyShouldTakeOnce).toInt()
                            } else {
                                stock / sellItem.count
                            }
                        }
                    } else {
                        return false
                    }
                    val tradeNumber = if (tradeTimes * sellItem.count >= stock) {
                        stock
                    } else {
                        tradeTimes * sellItem.count
                    }
                    shopEntity?.items?.find { it.item.itemStack.item == sellItem.item }?.let { itemEntry ->
                        if (shopEntity?.admin == 0) {
                            itemEntry.stock["default"] = itemEntry.stock.getOrPut("default") { stock } - tradeNumber
                        } else {
                            itemEntry.stock[player.uuid.toString()] = itemEntry.stock.getOrPut(player.uuid.toString()) {
                                stock
                            } - tradeNumber
                        }
                    }
                    val tradeMoney = moneyShouldTakeOnce * tradeTimes * 100
                    economyData.addMoney((-tradeMoney).toLong())
                    sellItem.count = tradeNumber
                    player.inventory.placeItemBackInInventory(sellItem)
                    repeat(tradeNumber) { this.selectedTrade.increaseUses() }
                    player.sendSystemMessage(
                        tr(
                            "shop.buy.success",
                            moneyShouldTakeOnce * tradeTimes,
                            tradeNumber,
                            this.selectedTrade?.result?.hoverName
                        )
                    )
                    val shopOwnerEntity = shopEntity?.ownerUUID?.let { playerEntity.level().server.playerList.getPlayer(it) }
                    shopOwnerEntity?.let { shopOwner ->
                        val shopOwnerEconomyData = EconomyData(shopOwner)
                        shopOwnerEconomyData.addMoney(tradeMoney.toLong())
                        shopOwner.sendSystemMessage(
                            tr(
                                "shop.sell.success",
                                playerEntity.name,
                                moneyShouldTakeOnce * tradeTimes,
                                tradeNumber,
                                this.selectedTrade?.result?.hoverName
                            )
                        )
                    } ?: {
                        shopEntity?.income += tradeMoney
                    }
                    this.sendUpdate()
                } else { // Buy
                    val imyvmCurry = this.merchantInventory.getItem(2)
                    val moneyShouldGetOnce =
                        imyvmCurry.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getDouble("price")?.orElse(null) ?: return false
                    val stock = this.selectedTrade.maxUses - this.selectedTrade.uses
                    val buyItem = this.merchantInventory.getItem(0)
                    val economyData = EconomyData(player)
                    val playerBalance = economyData.getMoney()
                    val sellTimes = if (action == ContainerInput.PICKUP && playerBalance >= moneyShouldGetOnce) {
                        1
                    } else if (action == ContainerInput.QUICK_MOVE) {
                        (countItemInInventory(player, buyItem.item) / buyItem.count)
                    } else {
                        return false
                    }
                    val sellNumber = sellTimes * buyItem.count
                    buyItem.count = sellNumber
                    player.inventory.removeItem(buyItem)
                    shopEntity?.items?.find { it.item.itemStack.item == buyItem.item }?.let { itemEntry ->
                        itemEntry.stock[player.uuid.toString()] = itemEntry.stock.getOrPut(player.uuid.toString()) {
                            stock
                        } - sellNumber
                    }
                    economyData.addMoney((moneyShouldGetOnce * 100 * sellTimes).toLong())
                    repeat(sellNumber) { this.selectedTrade.increaseUses() }
                    player.sendSystemMessage(tr("shop.purchase.success", moneyShouldGetOnce * sellTimes))
                    this.sendUpdate()
                }
                shopEntity?.let { shop -> customScope.launch { shopDBService.dbQueryAsync { shopDBService.update(shop) } } }
            }
            return super.onAnyClick(index, type, action)
        }

        override fun onTrade(offer: MerchantOffer?): Boolean {
            return false
        }

        override fun onPlayerClose(auto: Boolean) {
            villagerEntity?.let { removeGui(it) }
            shopEntity?.let { shop -> customScope.launch { shopDBService.dbQueryAsync { shopDBService.update(shop) } } }
            if (!playerEntity.level().isClientSide) {
                val firstItemStack = this.merchantInventory.getItem(0)
                val firstItemStackCustomData = firstItemStack.get(DataComponents.CUSTOM_DATA)
                if (firstItemStack.item === Items.BAMBOO && firstItemStackCustomData != null && firstItemStackCustomData.copyTag().contains(
                        "securityCode"
                    )
                ) {
                    this.merchantInventory.removeItemNoUpdate(0)
                    this.merchantInventory.removeItemNoUpdate(1)
                    this.merchantInventory.removeItemNoUpdate(2)
                }
            }
            super.onPlayerClose(auto)
        }
    }
    private var villagerEntity: Villager? = null
    private var type = -1
    private var shopEntity: ShopEntity? = null
    var id = -1
    fun open(villager: Villager) {
        this.villagerEntity = villager
        villagerEntity!!.entityTags().forEach { value ->
            val idPattern: Pattern = Pattern.compile("id:[0-9]+")
            val typePattern: Pattern = Pattern.compile("type:[0-9]+")
            val idMatcher: Matcher = idPattern.matcher(value)
            if (idMatcher.find()) {
                this.id = idMatcher.group().split(":")[1].toInt()
            }
            val typeMatcher: Matcher = typePattern.matcher(value)
            if (typeMatcher.find()) {
                this.type = typeMatcher.group().split(":")[1].toInt()
            }
        }
        if (id == -1 || type == -1) {
            return
        }

        addGui(villager)

        customScope.launch {
            val loadedShop = shopDBService.dbQueryAsync {
                shopDBService.readById(id, registries)
            }
            playerEntity.level().server.execute {
                shopEntity = loadedShop
                buildAndOpenGui()
            }
        }
    }

    private fun buildAndOpenGui() {
        shopEntity?.items?.forEach { items ->
            // Get stock

            val stock = if (shopEntity?.admin == 0) {
                items.stock.getOrPut("default") { 0 }
            } else {
                items.stock.getOrPut(playerEntity.uuid.toString()) {
                    items.stock.getOrPut("default") { -1 }
                }.let { stockValue ->
                    if (stockValue == -1) Int.MAX_VALUE else stockValue
                }
            }

            // Set currency

            val currencyItemStack = ItemStack(Items.BAMBOO)
            currencyItemStack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
            val lore = ItemLore(
                listOf(
                    tr("shop.curry.lore_1"),
                    tr("shop.curry.lore_2"),
                    tr("shop.curry.lore_3"),
                    tr("shop.curry.lore_4")
                )
            )
            val sellItemName = tr("shop.curry.displayname", items.price)
            currencyItemStack.set(DataComponents.CUSTOM_NAME, sellItemName)
            currencyItemStack.set(DataComponents.LORE, lore)
            currencyItemStack.set(DataComponents.REPAIR_COST, stock)
            val nbtTemp = CompoundTag()
            nbtTemp.putDouble("price", items.price)
            val localDate = LocalDate.now()
            nbtTemp.putString("securityCode",
                Random(localDate.year + localDate.dayOfYear + localDate.monthValue + items.sellPerTime + stock + Random.nextInt()).nextInt()
                    .toString()
            )
            currencyItemStack.set(DataComponents.CUSTOM_DATA, CustomData.of(nbtTemp))
            val currencyItem = ItemCost(
                BuiltInRegistries.ITEM.wrapAsHolder(Items.BAMBOO),
                1, DataComponentExactPredicate.allOf(currencyItemStack.components), currencyItemStack
            )

            val sellItem = items.item
            if (type == 0) {
                val tradeOffer =
                    if (stock >= items.sellPerTime) {
                        MerchantOffer(currencyItem, sellItem.itemStack, stock, 0 ,0f)
                    } else {
                        MerchantOffer(currencyItem, sellItem.itemStack, 0, 0, 0f)
                    }
                gui.addTrade(tradeOffer)
            } else {
                gui.addTrade(MerchantOffer(sellItem, currencyItem.itemStack, stock, 0, 0f))
            }
        }

        gui.title = Component.literal(shopEntity?.shopname ?: "")
        gui.open()
    }

    private fun addGui(villager: Villager) {
        VillagerShopMain.guiSet.add(villager)
    }

    fun removeGui(villager: Villager) {
        VillagerShopMain.guiSet.remove(villager)
    }
}
