package com.imyvm.villagerShop.apis

import com.imyvm.villagerShop.VillagerShopMain.Companion.LOGGER
import com.imyvm.villagerShop.VillagerShopMain.Companion.shopDBService
import com.imyvm.villagerShop.apis.Translator.tr
import com.imyvm.villagerShop.items.ItemManager.Companion.restoreItemList
import com.imyvm.villagerShop.items.ItemManager.Companion.storeItemList
import com.imyvm.villagerShop.shops.ShopEntity
import com.imyvm.villagerShop.shops.ShopEntity.Companion.sendMessageByType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class ShopService(private val database: Database, private val server: MinecraftServer) {
    object Shops : IntIdTable() {
        val shopname = varchar("shopname", 20)
        val posX = integer("posX")
        val posY = integer("posY")
        val posZ = integer("posZ")
        val world = varchar("world", 100)
        val owner = varchar("owner", 40)
        val ownerUUID = uuid("ownerUUID").default(UUID.fromString("00000000-0000-4000-8000-000000000000"))
        val admin = integer("admin")
        val type = integer("type")
        val items = text("items")
        val income = double("income")
    }

    object DataBaseInfo : IntIdTable() {
        val dbVersion = integer("dbVersion").uniqueIndex()
        val appliedAt = varchar("applied_at", 50)
    }

    init {
        transaction(database) {
            SchemaUtils.create(Shops, DataBaseInfo)
            applyMigrations()
        }
    }

    private fun createShopEntity(resultRow: ResultRow, registries: RegistryWrapper.WrapperLookup): ShopEntity {
        return ShopEntity(
            resultRow[Shops.id].value,
            resultRow[Shops.shopname],
            resultRow[Shops.posX],
            resultRow[Shops.posY],
            resultRow[Shops.posZ],
            resultRow[Shops.world],
            resultRow[Shops.admin],
            ShopType.entries[resultRow[Shops.type]],
            resultRow[Shops.owner],
            resultRow[Shops.ownerUUID],
            restoreItemList(resultRow[Shops.items], registries),
            resultRow[Shops.income]
        )
    }

    fun <T> dbQuery(block: () -> T): T =
        transaction(database) { block() }

    @Suppress("DuplicatedCode")
    fun create(shop: ShopEntity): Int = dbQuery {
        Shops.insert {
            it[shopname] = shop.shopname
            it[posX] = shop.posX
            it[posY] = shop.posY
            it[posZ] = shop.posZ
            it[world] = shop.world
            it[owner] = shop.owner
            it[ownerUUID] = shop.ownerUUID
            it[admin] = shop.admin
            it[type] = shop.type.ordinal
            it[items] = storeItemList(shop.items)
            it[income] = shop.income
        }[Shops.id].value
    }

    fun readById(id: Int, registries: RegistryWrapper.WrapperLookup): ShopEntity? = dbQuery {
        Shops.selectAll()
            .where { Shops.id eq id }
            .map {
                createShopEntity(it, registries)
        }.singleOrNull()
    }

    fun readByShopName(shopName: String,
                       playerName: String = "",
                       registries: RegistryWrapper.WrapperLookup
    ): List<ShopEntity?> = dbQuery {
        val condition = if (playerName != "") {
            Shops.selectAll()
                .where { (Shops.shopname eq shopName) and (Shops.owner eq playerName) }
        } else {
            Shops.selectAll()
                .where { Shops.shopname eq shopName }
        }
        condition.map {
            createShopEntity(it, registries)
        }
    }

    fun readByOwner(playerName: String, registries: RegistryWrapper.WrapperLookup): List<ShopEntity> = dbQuery {
        Shops.selectAll()
            .where { Shops.owner eq playerName }
            .map {
                createShopEntity(it, registries)
            }
    }

    fun readByLocation(
        pos: String,
        rangeX: Int, rangeY: Int, rangeZ: Int, world: String,
        registries: RegistryWrapper.WrapperLookup
    ): List<ShopEntity> = dbQuery {
        val (x, y, z) = pos.split(",").map { it.toInt() }
        Shops.selectAll()
            .where { (Shops.posX.between(x - rangeX, x + rangeX)) and
                    (Shops.posY.between(y - rangeY, y + rangeY)) and
                    (Shops.posZ.between(z - rangeZ, z + rangeZ)) and
                    (Shops.world eq world) }
            .map {
                createShopEntity(it, registries)
            }
    }

    fun readByType(registries: RegistryWrapper.WrapperLookup, shopTypes: List<ShopType>): List<ShopEntity> = dbQuery {
        Shops.selectAll()
            .where { Shops.type inList shopTypes.map { it.ordinal } }
            .map {
                createShopEntity(it, registries)
            }
    }

    @Suppress("DuplicatedCode")
    fun update(shop: ShopEntity) = dbQuery {
        Shops.update({ Shops.id eq shop.id }) {
            it[shopname] = shop.shopname
            it[posX] = shop.posX
            it[posY] = shop.posY
            it[posZ] = shop.posZ
            it[world] = shop.world
            it[owner] = shop.owner
            it[ownerUUID] = shop.ownerUUID
            it[admin] = shop.admin
            it[type] = shop.type.ordinal
            it[items] = storeItemList(shop.items)
            it[income] = shop.income
        }
    }

    fun delete(id: Int) = dbQuery {
        Shops.deleteWhere { Shops.id.eq(id) }
    }

    private fun getCurrentDbVersion(): Int = dbQuery {
        DataBaseInfo.selectAll().maxByOrNull { it[DataBaseInfo.dbVersion] }?.get(DataBaseInfo.dbVersion) ?: 0
    }

    private fun applyMigration(version: Int, migrationName: String, migration: () -> Unit) = dbQuery {
        val exists = DataBaseInfo.selectAll()
            .where { DataBaseInfo.dbVersion eq version }
            .count() > 0
        if (!exists) {
            LOGGER.info("[ImyvmVillagerShop] Applying migration $migrationName ($version)")
            migration()
            DataBaseInfo.insert {
                it[dbVersion] = version
                it[appliedAt] = Date().toString()
            }
            LOGGER.info("[ImyvmVillagerShop] Migration $migrationName ($version) applied")
        } else {
            LOGGER.info("[ImyvmVillagerShop] Migration $migrationName ($version) already applied, skipping")
        }
    }

    private fun applyMigrations() {
        val currentVersion = getCurrentDbVersion()
        if (currentVersion < 1) {
            applyMigration(1, "Add ownerUUID column") {
                migrateToAddOwnerUUID()
            }
        }
        // Add future migrations here
    }

    private fun migrateToAddOwnerUUID() {
        val currentColumns = SchemaUtils.statementsRequiredForDatabaseMigration(Shops)
        val needsOwnerUUIDColumn = currentColumns.any { statement ->
            statement.contains("ownerUUID", ignoreCase = true) && statement.contains("ADD", ignoreCase = true)
        }

        if (needsOwnerUUIDColumn) {
            SchemaUtils.createMissingTablesAndColumns(Shops)

            val allShops = Shops.selectAll().map { row ->
                row[Shops.id].value to row[Shops.owner]
            }

            LOGGER.info("[ImyvmVillagerShop] Migrating ${allShops.size} shops to add ownerUUID")

            allShops.forEach { (id, playerName) ->
                val playerUUID = server.userCache?.findByName(playerName)?.get()?.id ?: UUID.fromString("00000000-0000-4000-8000-000000000000")
                Shops.update({ Shops.id eq id }) {
                    it[ownerUUID] = playerUUID
                }
            }

            LOGGER.info("[ImyvmVillagerShop] Migration to add ownerUUID completed")
        }
    }

    companion object {

        enum class ShopType {
            SELL, UNLIMITED_BUY, REFRESHABLE_SELL, REFRESHABLE_BUY
        }

        fun rangeSearch(
            context: CommandContext<ServerCommandSource>,
            searchCondition: String,
        ): Int {
            val player = context.source.player!!
            val results = mutableListOf<ShopEntity?>()
            val registries = context.source.registryManager
            for (i in searchCondition.split(" ")) {
                if (i.contains(":")) {
                    val (condition, parameter) = i.split(":", limit = 2)
                    val temp = when (condition) {
                        "id" -> mutableListOf(shopDBService.readById(parameter.toInt(), registries))
                        "shopname" -> shopDBService.readByShopName(parameter, registries = registries)
                        "owner" -> shopDBService.readByOwner(parameter, registries)
                        "location" -> shopDBService.readByLocation(parameter, 0, 0, 0, player.world.asString(), registries)
                        "range" -> {
                            val (rangeX,rangeY,rangeZ) = parameter.split(",").map {it.toInt()}
                            shopDBService.readByLocation(
                                "${player.pos.x},${player.pos.y},${player.pos.z}",
                                rangeX, rangeY, rangeZ ,player.world.asString(), registries)
                        }
                        else -> mutableListOf()
                    }
                    if (!results.containsAll(temp)) results.addAll(temp)
                } else {
                    player.sendMessage(tr("commands.range.search.failed", i))
                }
            }
            if (results.isEmpty()) {
                player.sendMessage(tr("commands.search.none"))
                return -1
            }
            for (shop in results) {
                shop?.let { sendMessageByType(it, player) }
            }
            return 1
        }

        fun resetRefreshableSellAndBuy(registries: RegistryWrapper.WrapperLookup) {
            shopDBService.readByType(registries, listOf(ShopType.UNLIMITED_BUY, ShopType.REFRESHABLE_BUY,
                ShopType.REFRESHABLE_SELL)).forEach { shop ->
                shop.items.forEach { item ->
                    item.stock.keys.drop(1).forEach { item.stock.remove(it) }
                }
                shopDBService.update(shop)
            }
        }
    }
}