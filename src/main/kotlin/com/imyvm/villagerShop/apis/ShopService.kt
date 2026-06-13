package com.imyvm.villagerShop.apis

import com.imyvm.villagerShop.VillagerShopMain.Companion.LOGGER
import com.imyvm.villagerShop.VillagerShopMain.Companion.shopDBService
import com.imyvm.villagerShop.apis.Translator.tr
import com.imyvm.villagerShop.items.ItemManager
import com.imyvm.villagerShop.items.ItemManager.Companion.restoreItemList
import com.imyvm.villagerShop.items.ItemManager.Companion.storeItemList
import com.imyvm.villagerShop.shops.ShopEntity
import com.imyvm.villagerShop.shops.ShopEntity.Companion.sendMessageByType
import com.mojang.brigadier.context.CommandContext
import com.mojang.serialization.JsonOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.TagParser
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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

    private fun createShopEntity(resultRow: ResultRow, registries: HolderLookup.Provider): ShopEntity {
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

    suspend fun <T> dbQueryAsync(block: () -> T): T = withContext(Dispatchers.IO) {
        transaction(database) { block() }
    }

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

    fun readById(id: Int, registries: HolderLookup.Provider): ShopEntity? = dbQuery {
        Shops.selectAll()
            .where { Shops.id eq id }
            .map {
                createShopEntity(it, registries)
        }.singleOrNull()
    }

    fun readByShopName(shopName: String,
                       playerName: String = "",
                       registries: HolderLookup.Provider
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

    fun readByOwner(playerName: String, registries: HolderLookup.Provider): List<ShopEntity> = dbQuery {
        Shops.selectAll()
            .where { Shops.owner eq playerName }
            .map {
                createShopEntity(it, registries)
            }
    }

    fun readByLocation(
        pos: String,
        rangeX: Int, rangeY: Int, rangeZ: Int, world: String,
        registries: HolderLookup.Provider
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

    fun readByType(registries: HolderLookup.Provider, shopTypes: List<ShopType>): List<ShopEntity> = dbQuery {
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

    private fun backupShopsTableBeforeMigration(): String {
        // 1. 生成带时间戳的唯一备份表名（例如：imyvm_shops_bak_20260613_201530）
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupTableName = "${Shops.tableName}_bak_$timestamp"

        try {
            // 2. 开启一个独立的事务来做备份
            transaction {
                // 标准 SQL：创建新表并全量复制数据
                val sql = "CREATE TABLE $backupTableName AS SELECT * FROM ${Shops.tableName}"
                exec(sql)
            }
            LOGGER.info("[ImyvmVillagerShop] 数据库备份成功！临时快照表已建立: $backupTableName")
            return backupTableName
        } catch (e: Exception) {
            LOGGER.error("[ImyvmVillagerShop] 致命错误：迁移前的自动备份失败！", e)
            LOGGER.error("[ImyvmVillagerShop] 为防止数据损坏，已强行熔断并中止数据迁移流程！")
            throw e
        }
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
        val targetVersion = 2
        if (currentVersion >= targetVersion) {
            LOGGER.info("[ImyvmVillagerShop] 数据库已是最新版本 ($currentVersion)，无需迁移。")
            return
        }
        val backupTable = backupShopsTableBeforeMigration()
        try {
            if (currentVersion < 1) {
                applyMigration(1, "Add ownerUUID column") {
                    migrateToAddOwnerUUID()
                }
            }
            if (currentVersion < 2) {
                applyMigration(2, "Migrate ItemManager serialization from NBT string to JSON elements") {
                    migrateToJsonElements()
                }
            }
            // Add future migrations here

        } catch (e: Exception) {
            LOGGER.error("[ImyvmVillagerShop] 数据库迁移过程中发生致命异常！数据可能已损坏！", e)
            LOGGER.error("[ImyvmVillagerShop] 请通过 Navicat 或数据库控制台执行以下两条 SQL 来完全恢复原状：")
            LOGGER.error("    DROP TABLE ${Shops.tableName};")
            LOGGER.error("    ALTER TABLE $backupTable RENAME TO ${Shops.tableName};")

            throw e // 强行抛出，阻止插件继续加载，防止脏数据在服务器运行时扩散
        }
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
                val playerUUID = server.services().nameToIdCache().get(playerName).map { it.id }.orElse(UUID.fromString("00000000-0000-4000-8000-000000000000"))
                Shops.update({ Shops.id eq id }) {
                    it[ownerUUID] = playerUUID
                }
            }

            LOGGER.info("[ImyvmVillagerShop] Migration to add ownerUUID completed")
        }
    }

    private fun migrateToJsonElements() {
        val allShops = Shops.selectAll().map { row ->
            row[Shops.id].value to row[Shops.items]
        }

        LOGGER.info("[ImyvmVillagerShop] Migrating ${allShops.size} shops to JSON elements for items")

        val itemDataJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        allShops.forEach { (id, itemsString) ->
            val outerList = Json.decodeFromString<List<String>>(itemsString)
            val migratedInnerStrings = outerList.map { innerJsonStr ->
                val jsonObject = Json.parseToJsonElement(innerJsonStr).jsonObject
                val mutableMap = jsonObject.toMutableMap().apply {
                    val oldNbtString = this["itemNbt"]?.jsonPrimitive?.content ?: return@apply
                    val nbtOps = server.registryAccess().createSerializationContext(NbtOps.INSTANCE)
                    val nbt = TagParser.parseCompoundFully(oldNbtString)
                    val itemStack = ItemStack.CODEC.parse(nbtOps, nbt).getOrThrow()
                    val jsonOps = server.registryAccess().createSerializationContext(JsonOps.INSTANCE)
                    val gsonElement = ItemStack.CODEC.encodeStart(jsonOps, itemStack).getOrThrow()
                    val newItemsElement = itemDataJson.parseToJsonElement(gsonElement.toString())
                    remove("itemNbt")
                    put("itemStackJsonElement", newItemsElement)
                }
                val migratedObject = JsonObject(mutableMap)
                val verifiedNewConfig = Json.decodeFromJsonElement<ItemManager.ItemData>(migratedObject)
                Json.encodeToString(verifiedNewConfig)
            }

            Shops.update({ Shops.id eq id }) {
                it[items] = Json.encodeToString(migratedInnerStrings)
            }
        }

        LOGGER.info("[ImyvmVillagerShop] Migration to JSON elements for items completed")
    }

    companion object {

        enum class ShopType {
            SELL, UNLIMITED_BUY, REFRESHABLE_SELL, REFRESHABLE_BUY
        }

        fun rangeSearch(
            context: CommandContext<CommandSourceStack>,
            searchCondition: String,
        ): Int {
            val player = context.source.player!!
            val results = mutableListOf<ShopEntity?>()
            val registries = context.source.registryAccess()
            for (i in searchCondition.split(" ")) {
                if (i.contains(":")) {
                    val (condition, parameter) = i.split(":", limit = 2)
                    val temp = when (condition) {
                        "id" -> mutableListOf(shopDBService.readById(parameter.toInt(), registries))
                        "shopname" -> shopDBService.readByShopName(parameter, registries = registries)
                        "owner" -> shopDBService.readByOwner(parameter, registries)
                        "location" -> shopDBService.readByLocation(parameter, 0, 0, 0, player.level().dimension().identifier().toString(), registries)
                        "range" -> {
                            val (rangeX,rangeY,rangeZ) = parameter.split(",").map {it.toInt()}
                            shopDBService.readByLocation(
                                "${player.position().x},${player.position().y},${player.position().z}",
                                rangeX, rangeY, rangeZ ,player.level().dimension().identifier().toString(), registries)
                        }
                        else -> mutableListOf()
                    }
                    if (!results.containsAll(temp)) results.addAll(temp)
                } else {
                    player.sendSystemMessage(tr("commands.range.search.failed", i))
                }
            }
            if (results.isEmpty()) {
                player.sendSystemMessage(tr("commands.search.none"))
                return -1
            }
            for (shop in results) {
                shop?.let { sendMessageByType(it, player) }
            }
            return 1
        }

        fun resetRefreshableSellAndBuy(registries: HolderLookup.Provider) {
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
