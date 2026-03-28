package com.imyvm.villagerShop

import com.imyvm.villagerShop.apis.*
import com.imyvm.villagerShop.apis.ShopService.Companion.resetRefreshableSellAndBuy
import com.imyvm.villagerShop.apis.Translator.tr
import com.imyvm.villagerShop.commands.register
import com.imyvm.villagerShop.events.PlayerConnectCallback
import com.imyvm.villagerShop.gui.ShopManageGui
import com.imyvm.villagerShop.gui.ShopTrade
import com.imyvm.villagerShop.items.ItemManager
import kotlinx.coroutines.launch
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.entity.passive.VillagerEntity
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit


class VillagerShopMain : ModInitializer {
	val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
	override fun onInitialize() {
		CONFIG.loadAndSave()
		CommandRegistrationCallback.EVENT.register { dispatcher, commandRegistryAccess, _ ->
			registryAccess = commandRegistryAccess
			register(dispatcher, commandRegistryAccess)
		}
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            shopDBService = ShopService(DbSettings.db, server)
        }
		ServerLifecycleEvents.SERVER_STARTED.register { server ->
			itemList.addAll(purchaseItemLoad(server))
			scheduleDailyTask(server.registryManager)
		}
		ServerEntityEvents.ENTITY_LOAD.register { entity, serverWorld ->
			if (entity is VillagerEntity && entity.commandTags.contains("VillagerShop")) {
				val id =
					entity.commandTags.firstOrNull { it.startsWith("id:") }?.split(":")?.getOrNull(1)?.toIntOrNull()
						?: -1
				if (id != -1) {
					customScope.launch {
						val shop = shopDBService.dbQueryAsync {
							shopDBService.readById(id, serverWorld.registryManager)
						}
						shop?.let { s ->
							serverWorld.server.execute {
								entity.setPos(
									s.posX.toDouble() + 0.5,
									s.posY.toDouble() + 1,
									s.posZ.toDouble() + 0.5
								)
								entity.customName = Text.of(s.shopname)
								synchronized(shopEntityList) {
									shopEntityList[s.id] = entity
								}
							}
						}
					}
				}
			}
		}
		ServerLifecycleEvents.SERVER_STOPPED.register {
			scheduler.shutdownNow()
		}
		UseEntityCallback.EVENT.register { player, world, _, entity, _ ->
			if (entity.commandTags.contains("VillagerShop") && player is ServerPlayerEntity && entity is VillagerEntity && !containGui(entity)) {
				val shopId = entity.commandTags
					.firstOrNull { it.startsWith("id:") }?.split(":")?.getOrNull(1)?.toIntOrNull() ?: -1
				// Owner right-clicks (without sneak) → manage GUI
				// Owner sneaks + right-clicks → trade GUI (preview as customer)
				// Non-owner → trade GUI
				if (shopId != -1 && !player.isSneaking) {
					customScope.launch {
						val shop = shopDBService.dbQueryAsync {
							shopDBService.readById(shopId, world.registryManager)
						}
						world.server?.execute {
							if (shop != null && shop.ownerUUID == player.uuid && shop.admin == 0) {
								ShopManageGui(player, registryAccess).openFor(shop)
							} else {
								ShopTrade(player, world.registryManager).open(entity)
							}
						}
					}
				} else {
					ShopTrade(player, world.registryManager).open(entity)
				}
				ActionResult.SUCCESS
			} else {
				ActionResult.PASS
			}
		}
		PlayerConnectCallback.EVENT.register { _, player ->
			customScope.launch {
				var incomeTotal = 0.0
				shopDBService.dbQueryAsync {
					shopDBService.readByOwner(player.nameForScoreboard, player.registryManager).forEach {
						incomeTotal += it.income
						it.income = 0.0
						shopDBService.update(it)
					}
				}

				if (incomeTotal != 0.0) {
					player.server.execute {
						EconomyData(player).addMoney((incomeTotal * 100).toLong())
						player.sendMessage(tr("commands.balance.add", incomeTotal.toLong()))
					}
				}
			}
		}
		LOGGER.info("Imyvm Villager Shop initialized")
	}

	companion object {
		@JvmField
		val LOGGER: Logger = LoggerFactory.getLogger("Imyvm-VillagerShop")
		const val MOD_ID = "imyvm_villagershop"
		val CONFIG: ModConfig = ModConfig()
		val itemList: MutableList<ItemManager> = mutableListOf()
		val guiSet: ConcurrentHashMap.KeySetView<Any, Boolean> = ConcurrentHashMap.newKeySet()
		val shopEntityList: MutableMap<Int, VillagerEntity> = mutableMapOf()
        lateinit var shopDBService: ShopService
            private set
        lateinit var registryAccess: CommandRegistryAccess
            private set
	}

	private fun purchaseItemLoad(server: MinecraftServer): MutableList<ItemManager> {
		val itemList: MutableList<ItemManager> = mutableListOf()
		for (i in shopDBService.readByType(server.registryManager,
			listOf(ShopService.Companion.ShopType.REFRESHABLE_BUY, ShopService.Companion.ShopType.UNLIMITED_BUY))
		) {
			itemList.addAll(i.items)
		}
		return itemList
	}

	private fun containGui(villager: VillagerEntity): Boolean {
		return guiSet.contains(villager)
	}

	private fun scheduleDailyTask(registries: RegistryWrapper.WrapperLookup) {

		val midnightTask = Runnable {
			// Reset daily limit
			resetRefreshableSellAndBuy(registries)
		}

		val now = LocalDateTime.now()
		val nextMidnight = now.plusDays(1).toLocalDate().atStartOfDay()
		val delayMillis = Duration.between(now, nextMidnight).toMillis()

		scheduler.scheduleAtFixedRate(midnightTask, delayMillis, 24 * 60 * 60 * 1000, TimeUnit.MILLISECONDS)
	}
}

