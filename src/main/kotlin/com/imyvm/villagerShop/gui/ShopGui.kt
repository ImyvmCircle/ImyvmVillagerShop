package com.imyvm.villagerShop.gui

import com.imyvm.villagerShop.VillagerShopMain
import com.imyvm.villagerShop.apis.EconomyData
import com.imyvm.villagerShop.apis.ShopService.Companion.ShopType
import com.imyvm.villagerShop.apis.Translator.tr
import com.imyvm.villagerShop.shops.ShopEntity
import com.imyvm.villagerShop.shops.ShopEntity.Companion.shopDBService
import eu.pb4.sgui.api.ClickType
import eu.pb4.sgui.api.gui.MerchantGui
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.LoreComponent
import net.minecraft.component.type.NbtComponent
import net.minecraft.entity.passive.VillagerEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.nbt.NbtCompound
import net.minecraft.predicate.ComponentPredicate
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryWrapper
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.village.TradeOffer
import net.minecraft.village.TradedItem
import java.time.LocalDate
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.random.Random

class ShopGui(private val playerEntity: ServerPlayerEntity, private val registries: RegistryWrapper.WrapperLookup) {
    private val gui = object : MerchantGui(playerEntity, false) {
        override fun onAnyClick(index: Int, type: ClickType?, action: SlotActionType?): Boolean {
            if (index == 0 || index == 1) {
                return false
            } else if (index == 2 &&
                this.merchantInventory.getStack(2).item == this.selectedTrade?.sellItem?.item &&
                action != SlotActionType.SWAP
            ) {
                if (shopEntity?.type == ShopType.SELL || shopEntity?.type == ShopType.REFRESHABLE_SELL) { // Sell
                    val imyvmCurry = this.merchantInventory.getStack(0)
                    val moneyShouldTakeOnce =
                        imyvmCurry.get(DataComponentTypes.CUSTOM_DATA)?.copyNbt()?.getDouble("price") ?: return false
                    val stock = this.selectedTrade.maxUses - this.selectedTrade.uses
                    val sellItem = this.merchantInventory.getStack(2)
                    if (stock < sellItem.count) {
                        this.merchantInventory.removeStack(0)
                        player.sendMessage(tr("shop.buy.stock.lack"))
                        return false
                    }
                    val economyData = EconomyData(player)
                    val playerBalance = economyData.getMoney()
                    if (playerBalance < moneyShouldTakeOnce) {
                        this.merchantInventory.removeStack(0)
                        player.sendMessage(tr("shop.buy.money.lack", moneyShouldTakeOnce))
                        return false
                    }
                    val tradeTimes = if (action == SlotActionType.PICKUP && playerBalance >= moneyShouldTakeOnce) {
                        1
                    } else if (action == SlotActionType.QUICK_MOVE) {
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
                    shopEntity?.items?.find { it.item.itemStack.isOf(sellItem.item) }?.let { itemEntry ->
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
                    player.inventory.offerOrDrop(sellItem)
                    repeat(tradeNumber) { this.selectedTrade.use() }
                    player.sendMessage(
                        tr(
                            "shop.buy.success",
                            moneyShouldTakeOnce * tradeTimes,
                            tradeNumber,
                            this.selectedTrade?.sellItem?.toHoverableText()
                        )
                    )
                    val shopOwnerEntity = shopEntity?.owner?.let { playerEntity.server.playerManager.getPlayer(it) }
                    shopOwnerEntity?.let { shopOwner ->
                        val shopOwnerEconomyData = EconomyData(shopOwner)
                        shopOwnerEconomyData.addMoney(tradeMoney.toLong())
                        shopOwner.sendMessage(
                            tr(
                                "shop.sell.success",
                                playerEntity.name,
                                moneyShouldTakeOnce * tradeTimes,
                                tradeNumber,
                                this.selectedTrade?.sellItem?.toHoverableText()
                            )
                        )
                    } ?: {
                        shopEntity?.income += tradeMoney
                    }
                    this.sendUpdate()
                } else { // Buy
                    val imyvmCurry = this.merchantInventory.getStack(2)
                    val moneyShouldGetOnce =
                        imyvmCurry.get(DataComponentTypes.CUSTOM_DATA)?.copyNbt()?.getDouble("price") ?: return false
                    val stock = this.selectedTrade.maxUses - this.selectedTrade.uses
                    val buyItem = this.merchantInventory.getStack(0)
                    val economyData = EconomyData(player)
                    val playerBalance = economyData.getMoney()
                    val sellTimes = if (action == SlotActionType.PICKUP && playerBalance >= moneyShouldGetOnce) {
                        1
                    } else if (action == SlotActionType.QUICK_MOVE) {
                        (player.inventory.count(buyItem.item) / buyItem.count)
                    } else {
                        return false
                    }
                    val sellNumber = sellTimes * buyItem.count
                    buyItem.count = sellNumber
                    player.inventory.removeOne(buyItem)
                    shopEntity?.items?.find { it.item.itemStack.isOf(buyItem.item) }?.let { itemEntry ->
                        itemEntry.stock[player.uuid.toString()] = itemEntry.stock.getOrPut(player.uuid.toString()) {
                            stock
                        } - sellNumber
                    }
                    economyData.addMoney((moneyShouldGetOnce * 100 * sellTimes).toLong())
                    repeat(sellNumber) { this.selectedTrade.use() }
                    player.sendMessage(tr("shop.purchase.success", moneyShouldGetOnce * sellTimes))
                    this.sendUpdate()
                }
                shopEntity?.update()
            }
            return super.onAnyClick(index, type, action)
        }

        override fun onTrade(offer: TradeOffer?): Boolean {
            return false
        }

        override fun onClose() {
            villagerEntity?.let { removeGui(it) }
            shopEntity?.update()
            super.onClose()
        }
    }
    private var villagerEntity: VillagerEntity? = null
    private var type = -1
    private var shopEntity: ShopEntity? = null
    var id = -1
    fun open(villager: VillagerEntity) {
        this.villagerEntity = villager
        villagerEntity!!.commandTags.forEach { value ->
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

        shopEntity = shopDBService.readById(id, this.registries)

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
            currencyItemStack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)
            val lore = LoreComponent(
                listOf(
                    tr("shop.curry.lore_1"),
                    tr("shop.curry.lore_2"),
                    tr("shop.curry.lore_3"),
                    tr("shop.curry.lore_4")
                )
            )
            val sellItemName = tr("shop.curry.displayname", items.price)
            currencyItemStack.set(DataComponentTypes.CUSTOM_NAME, sellItemName)
            currencyItemStack.set(DataComponentTypes.LORE, lore)
            currencyItemStack.set(DataComponentTypes.REPAIR_COST, stock)
            val nbtTemp = NbtCompound()
            nbtTemp.putDouble("price", items.price)
            val localDate = LocalDate.now()
            nbtTemp.putString("securityCode",
                Random(localDate.year + localDate.dayOfYear + localDate.monthValue + items.sellPerTime + stock + Random.nextInt()).nextInt()
                    .toString()
            )
            currencyItemStack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbtTemp))
            val currencyItem = TradedItem(
                Registries.ITEM.getEntry(Registries.ITEM.getKey(Items.BAMBOO).get()).get(),
                1, ComponentPredicate.of(currencyItemStack.components), currencyItemStack
            )

            val sellItem = items.item
            if (type == 0) {
                val tradeOffer =
                    if (stock >= items.sellPerTime) {
                        TradeOffer(currencyItem, sellItem.itemStack, stock, 0 ,0f)
                    } else {
                        TradeOffer(currencyItem, sellItem.itemStack, 0, 0, 0f)
                    }
                gui.addTrade(tradeOffer)
            } else {
                gui.addTrade(TradeOffer(sellItem, currencyItem.itemStack, stock, 0, 0f))
            }
        }

        gui.title = Text.of(shopEntity?.shopname)
        gui.open()

        addGui(villager)
    }

    private fun addGui(villager: VillagerEntity) {
        VillagerShopMain.guiSet.add(villager)
    }

    fun removeGui(villager: VillagerEntity) {
        VillagerShopMain.guiSet.remove(villager)
    }
}