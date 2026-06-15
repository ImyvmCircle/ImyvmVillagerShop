package com.imyvm.villagerShop.gui

import com.imyvm.villagerShop.VillagerShopMain
import com.imyvm.villagerShop.VillagerShopMain.Companion.shopEntityList
import com.imyvm.villagerShop.apis.ShopService.Companion.ShopType
import com.imyvm.villagerShop.apis.Translator.tr
import com.imyvm.villagerShop.apis.customScope
import com.imyvm.villagerShop.items.ItemManager
import com.imyvm.villagerShop.items.ItemManager.Companion.findContainersInInventory
import com.imyvm.villagerShop.items.ItemManager.Companion.findShulkerBoxesInWorld
import com.imyvm.villagerShop.items.ItemManager.Companion.getItemsInsideContainerStack
import com.imyvm.villagerShop.items.ItemManager.Companion.offerItemToPlayer
import com.imyvm.villagerShop.items.ItemManager.Companion.removeItemFromInventory
import com.imyvm.villagerShop.items.ItemManager.Companion.removeItemFromShulkerBoxBlockEntity
import com.imyvm.villagerShop.items.ItemManager.Companion.removeItemFromShulkerBoxesInInventory
import com.imyvm.villagerShop.shops.ShopEntity
import com.imyvm.villagerShop.shops.ShopEntity.Companion.calculateAndTakeMoney
import com.imyvm.villagerShop.shops.ShopEntity.Companion.checkCanAddTradeOffer
import com.imyvm.villagerShop.shops.ShopEntity.Companion.checkCanCreateShop
import com.imyvm.villagerShop.shops.ShopEntity.Companion.checkPlayerMoney
import eu.pb4.sgui.api.ClickType
import eu.pb4.sgui.api.elements.GuiElementBuilder
import eu.pb4.sgui.api.gui.AnvilInputGui
import eu.pb4.sgui.api.gui.SimpleGui
import kotlinx.coroutines.launch
import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.commands.CommandBuildContext
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.PermissionLevel
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

// ─── Helpers ────────────────────────────────────────────────────────────────
// glassPane() and border() are defined in GuiHelpers.kt (same package)

// ─── Container source ───────────────────────────────────────────────────────

/** Represents where a browsable container lives. */
private sealed class ContainerSource {
    /** A shulker box or bundle in the player's inventory at [invSlot]. */
    data class InventorySlot(val invSlot: Int, val stack: ItemStack) : ContainerSource()
    /** A shulker box block entity placed in the world at [pos]. */
    data class WorldBlock(val pos: BlockPos) : ContainerSource()
    /** The player's personal ender chest. */
    object EnderChest : ContainerSource()
}

// ─── ShopCreateGui ──────────────────────────────────────────────────────────

class ShopCreateGui(
    private val playerEntity: ServerPlayer,
    private val registryAccess: CommandBuildContext
) {
    private val registries: HolderLookup.Provider = registryAccess

    private fun buildAnvilInputItem(icon: ItemStack, hint: Component): GuiElementBuilder =
        GuiElementBuilder.from(icon.copy())
            // Keep the anvil input blank so players can type directly without deleting prompt text.
            .setName(Component.literal(" "))
            .addLoreLine(hint)

    private fun parseQuickValue(clickType: ClickType, left: String, right: String): String? = when (clickType) {
        ClickType.MOUSE_LEFT -> left
        ClickType.MOUSE_RIGHT -> right
        else -> null
    }

    fun open() = openTypeSelectPage()

    // ── Step 1: choose shop type ────────────────────────────────────────────

    private fun openTypeSelectPage() {
        val gui = object : SimpleGui(MenuType.GENERIC_9x3, playerEntity, false) {
            override fun onPlayerClose(auto: Boolean) { removeGui(playerEntity); super.onPlayerClose(auto) }
        }
        gui.title = tr("gui.create.step1.title")
        border(gui, 3)

        gui.setSlot(11, GuiElementBuilder(Items.CHEST)
            .setName(tr("gui.create.type.player"))
            .addLoreLine(tr("gui.create.type.player.lore"))
            .setCallback { _, _, _, _ -> openNameInputPage(isAdmin = false) }
        )

        if (Permissions.check(playerEntity, VillagerShopMain.MOD_ID + ".admin", PermissionLevel.ADMINS)) {
            gui.setSlot(15, GuiElementBuilder(Items.ENDER_CHEST)
                .setName(tr("gui.create.type.admin"))
                .addLoreLine(tr("gui.create.type.admin.lore"))
                .setCallback { _, _, _, _ -> openAdminTypeSelectPage() }
            )
        }

        addGui(playerEntity)
        gui.open()
    }

    // ── Step 1b: admin – choose ShopType ───────────────────────────────────

    private fun openAdminTypeSelectPage() {
        val gui = object : SimpleGui(MenuType.GENERIC_9x3, playerEntity, false) {
            override fun onPlayerClose(auto: Boolean) { removeGui(playerEntity); super.onPlayerClose(auto) }
        }
        gui.title = tr("gui.create.step1b.title")
        border(gui, 3)

        val typeItems = linkedMapOf(
            ShopType.SELL             to Items.EMERALD,
            ShopType.UNLIMITED_BUY    to Items.GOLD_INGOT,
            ShopType.REFRESHABLE_SELL to Items.EMERALD_BLOCK,
            ShopType.REFRESHABLE_BUY  to Items.GOLD_BLOCK
        )
        typeItems.entries.forEachIndexed { i, (type, item) ->
            gui.setSlot(listOf(10, 12, 14, 16)[i], GuiElementBuilder(item)
                .setName(tr("gui.create.type.${type.name.lowercase()}"))
                .addLoreLine(tr("gui.create.type.${type.name.lowercase()}.lore"))
                .setCallback { _, _, _, _ -> openNameInputPage(isAdmin = true, adminShopType = type) }
            )
        }

        gui.setSlot(18, GuiElementBuilder(Items.ARROW)
            .setName(tr("gui.back"))
            .setCallback { _, _, _, _ -> openTypeSelectPage() }
        )
        gui.open()
    }

    // ── Step 2: enter shop name via anvil ───────────────────────────────────

    private fun openNameInputPage(isAdmin: Boolean, adminShopType: ShopType = ShopType.SELL) {
        val anvilGui = object : AnvilInputGui(playerEntity, false) {
            override fun onPlayerClose(auto: Boolean) { removeGui(playerEntity); super.onPlayerClose(auto) }
            override fun onInput(input: String) {}
        }
        anvilGui.title = tr("gui.create.step2.title")
        anvilGui.setSlot(0, buildAnvilInputItem(ItemStack(Items.NAME_TAG), tr("gui.create.name.placeholder")))
        anvilGui.setSlot(2, GuiElementBuilder(Items.DYE.lime)
            .setName(tr("gui.create.name.confirm"))
            .setCallback { _, clickType, _, _ ->
                if (clickType != ClickType.MOUSE_LEFT && clickType != ClickType.MOUSE_RIGHT) return@setCallback
                val name = anvilGui.input.trim()
                if (name.isEmpty()) { playerEntity.sendSystemMessage(tr("gui.create.name.empty")); return@setCallback }
                if (!checkCanCreateShop(name, playerEntity.scoreboardName, registryAccess)) {
                    playerEntity.sendSystemMessage(tr("commands.shop.create.name_used")); return@setCallback
                }
                anvilGui.close()
                openPosConfirmPage(name, isAdmin, adminShopType)
            }
        )
        anvilGui.open()
    }

    // ── Step 3: confirm position ────────────────────────────────────────────

    private fun openPosConfirmPage(
        shopName: String,
        isAdmin: Boolean,
        adminShopType: ShopType,
        basePos: BlockPos = playerEntity.blockPosition(),
        offsetX: Int = 0,
        offsetY: Int = 0,
        offsetZ: Int = 0
    ) {
        val pos = BlockPos(basePos.x + offsetX, basePos.y + offsetY, basePos.z + offsetZ)

        val gui = object : SimpleGui(MenuType.GENERIC_9x5, playerEntity, false) {
            override fun onPlayerClose(auto: Boolean) { removeGui(playerEntity); super.onPlayerClose(auto) }
        }
        gui.title = tr("gui.create.step3.title")

        // Fill all with glass
        for (i in 0 until 45) gui.setSlot(i, glassPane(8))

        // ── Current position display (centre) ──
        gui.setSlot(22, GuiElementBuilder(Items.COMPASS)
            .setName(tr("gui.create.pos.current", pos.x, pos.y, pos.z))
            .addLoreLine(tr("gui.create.pos.lore"))
            .addLoreLine(tr("gui.create.pos.click_use"))
            .setCallback { _, _, _, _ -> gui.close(); openItemListPage(shopName, isAdmin, adminShopType, pos) }
        )

        // ── X axis adjustment (row 2, slots 9-17) ──
        // -5  -1   [X display]  +1  +5
        gui.setSlot(10, GuiElementBuilder(Items.CONCRETE.red)
            .setName(tr("gui.create.pos.x_minus", 5))
            .setCallback { _, _, _, _ -> openPosConfirmPage(shopName, isAdmin, adminShopType, basePos, offsetX - 5, offsetY, offsetZ) }
        )
        gui.setSlot(11, GuiElementBuilder(Items.CONCRETE.orange)
            .setName(tr("gui.create.pos.x_minus", 1))
            .setCallback { _, _, _, _ -> openPosConfirmPage(shopName, isAdmin, adminShopType, basePos, offsetX - 1, offsetY, offsetZ) }
        )
        gui.setSlot(13, GuiElementBuilder(Items.CONCRETE.white)
            .setName(Component.literal("X: ${pos.x}  (${if (offsetX >= 0) "+$offsetX" else "$offsetX"})"))
        )
        gui.setSlot(15, GuiElementBuilder(Items.CONCRETE.orange)
            .setName(tr("gui.create.pos.x_plus", 1))
            .setCallback { _, _, _, _ -> openPosConfirmPage(shopName, isAdmin, adminShopType, basePos, offsetX + 1, offsetY, offsetZ) }
        )
        gui.setSlot(16, GuiElementBuilder(Items.CONCRETE.red)
            .setName(tr("gui.create.pos.x_plus", 5))
            .setCallback { _, _, _, _ -> openPosConfirmPage(shopName, isAdmin, adminShopType, basePos, offsetX + 5, offsetY, offsetZ) }
        )

        // ── Y axis adjustment (row 3, slots 18-26) ──
        gui.setSlot(19, GuiElementBuilder(Items.CONCRETE.blue)
            .setName(tr("gui.create.pos.y_minus", 5))
            .setCallback { _, _, _, _ -> openPosConfirmPage(shopName, isAdmin, adminShopType, basePos, offsetX, offsetY - 5, offsetZ) }
        )
        gui.setSlot(20, GuiElementBuilder(Items.CONCRETE.lightBlue)
            .setName(tr("gui.create.pos.y_minus", 1))
            .setCallback { _, _, _, _ -> openPosConfirmPage(shopName, isAdmin, adminShopType, basePos, offsetX, offsetY - 1, offsetZ) }
        )
        gui.setSlot(22, GuiElementBuilder(Items.COMPASS)
            .setName(tr("gui.create.pos.current", pos.x, pos.y, pos.z))
            .addLoreLine(tr("gui.create.pos.lore"))
            .addLoreLine(tr("gui.create.pos.click_use"))
            .setCallback { _, _, _, _ -> gui.close(); openItemListPage(shopName, isAdmin, adminShopType, pos) }
        )
        gui.setSlot(24, GuiElementBuilder(Items.CONCRETE.lightBlue)
            .setName(tr("gui.create.pos.y_plus", 1))
            .setCallback { _, _, _, _ -> openPosConfirmPage(shopName, isAdmin, adminShopType, basePos, offsetX, offsetY + 1, offsetZ) }
        )
        gui.setSlot(25, GuiElementBuilder(Items.CONCRETE.blue)
            .setName(tr("gui.create.pos.y_plus", 5))
            .setCallback { _, _, _, _ -> openPosConfirmPage(shopName, isAdmin, adminShopType, basePos, offsetX, offsetY + 5, offsetZ) }
        )

        // ── Z axis adjustment (row 4, slots 27-35) ──
        gui.setSlot(28, GuiElementBuilder(Items.CONCRETE.green)
            .setName(tr("gui.create.pos.z_minus", 5))
            .setCallback { _, _, _, _ -> openPosConfirmPage(shopName, isAdmin, adminShopType, basePos, offsetX, offsetY, offsetZ - 5) }
        )
        gui.setSlot(29, GuiElementBuilder(Items.CONCRETE.lime)
            .setName(tr("gui.create.pos.z_minus", 1))
            .setCallback { _, _, _, _ -> openPosConfirmPage(shopName, isAdmin, adminShopType, basePos, offsetX, offsetY, offsetZ - 1) }
        )
        gui.setSlot(31, GuiElementBuilder(Items.CONCRETE.white)
            .setName(Component.literal("Z: ${pos.z}  (${if (offsetZ >= 0) "+$offsetZ" else "$offsetZ"})"))
        )
        gui.setSlot(33, GuiElementBuilder(Items.CONCRETE.lime)
            .setName(tr("gui.create.pos.z_plus", 1))
            .setCallback { _, _, _, _ -> openPosConfirmPage(shopName, isAdmin, adminShopType, basePos, offsetX, offsetY, offsetZ + 1) }
        )
        gui.setSlot(34, GuiElementBuilder(Items.CONCRETE.green)
            .setName(tr("gui.create.pos.z_plus", 5))
            .setCallback { _, _, _, _ -> openPosConfirmPage(shopName, isAdmin, adminShopType, basePos, offsetX, offsetY, offsetZ + 5) }
        )

        // ── Bottom row: reset / back / confirm ──
        gui.setSlot(36, GuiElementBuilder(Items.ARROW)
            .setName(tr("gui.back"))
            .setCallback { _, _, _, _ -> gui.close(); openNameInputPage(isAdmin, adminShopType) }
        )
        gui.setSlot(40, GuiElementBuilder(Items.BARRIER)
            .setName(tr("gui.create.pos.reset"))
            .setCallback { _, _, _, _ -> openPosConfirmPage(shopName, isAdmin, adminShopType, playerEntity.blockPosition()) }
        )
        gui.setSlot(44, GuiElementBuilder(Items.EMERALD)
            .setName(tr("gui.create.pos.confirm"))
            .addLoreLine(tr("gui.create.pos.current", pos.x, pos.y, pos.z))
            .setCallback { _, _, _, _ -> gui.close(); openItemListPage(shopName, isAdmin, adminShopType, pos) }
        )

        gui.open()
    }

    // ── Step 4: item list ────────────────────────────────────────────────────

    private fun openItemListPage(
        shopName: String,
        isAdmin: Boolean,
        adminShopType: ShopType,
        pos: BlockPos,
        draft: ShopEntity = ShopEntity(
            id = -1, shopname = shopName,
            posX = pos.x, posY = pos.y, posZ = pos.z,
            world = playerEntity.level().dimension().identifier().toString(),
            admin = if (isAdmin) 1 else 0,
            type = if (isAdmin) adminShopType else ShopType.SELL,
            owner = playerEntity.scoreboardName, ownerUUID = playerEntity.uuid,
            items = mutableListOf(), income = 0.0
        )
    ) {
        val gui = object : SimpleGui(MenuType.GENERIC_9x4, playerEntity, false) {
            override fun onPlayerClose(auto: Boolean) { removeGui(playerEntity); super.onPlayerClose(auto) }
        }
        gui.title = tr("gui.create.step4.title", draft.items.size, 7)

        // Top 3 rows: item slots (slots 0-26), filled with glass by default
        for (i in 0..26) gui.setSlot(i, glassPane(8))

        draft.items.forEachIndexed { idx, item ->
            if (idx < 7) {
                // Display items starting at slot 1, spaced every 2 (so: 1,3,5,7,9,11,13)
                gui.setSlot(idx * 2 + 1, GuiElementBuilder.from(item.item.itemStack.copy())
                    .setName(item.item.itemStack.hoverName)
                    .addLoreLine(tr("gui.create.item.price", item.price))
                    .addLoreLine(tr("gui.create.item.qty", item.sellPerTime))
                    .addLoreLine(tr("gui.create.item.stock", item.stock["default"] ?: 0))
                    .addLoreLine(tr("gui.create.item.click_remove"))
                    .setCallback { _, _, _, _ ->
                        val stockBack = item.stock["default"] ?: 0
                        if (!isAdmin && stockBack > 0)
                            playerEntity.inventory.placeItemBackInInventory(ItemStack(item.item.item, stockBack))
                        draft.items.remove(item)
                        openItemListPage(shopName, isAdmin, adminShopType, pos, draft)
                    }
                )
            }
        }

        // Bottom row (slots 27-35): controls
        // Slot 28: Add item
        if (draft.items.size < 7) {
            gui.setSlot(28, GuiElementBuilder(Items.DYE.lime)
                .setName(tr("gui.create.item.add"))
                .setCallback { _, _, _, _ -> gui.close(); openAddItemPage(shopName, isAdmin, adminShopType, pos, draft) }
            )
        } else {
            gui.setSlot(28, glassPane(14))
        }

        // Slot 29: Cancel
        gui.setSlot(29, GuiElementBuilder(Items.DYE.red)
            .setName(tr("gui.create.cancel"))
            .setCallback { _, _, _, _ ->
                if (!isAdmin) offerItemToPlayer(playerEntity, draft.items)
                draft.items.clear()
                gui.close()
            }
        )

        // Slot 31: Shop info
        gui.setSlot(31, GuiElementBuilder(Items.OAK_SIGN)
            .setName(Component.literal(draft.shopname))
            .addLoreLine(tr("gui.create.info.pos", pos.x, pos.y, pos.z))
            .addLoreLine(tr("gui.create.info.type", draft.type.name))
            .addLoreLine(tr("gui.create.info.items", draft.items.size))
        )

        // Slot 34: Submit
        if (draft.items.isNotEmpty()) {
            gui.setSlot(34, GuiElementBuilder(Items.EMERALD)
                .setName(tr("gui.create.submit"))
                .addLoreLine(
                    if (isAdmin) tr("gui.create.submit.admin")
                    else tr("gui.create.submit.cost", checkPlayerMoney(playerEntity, registryAccess) / 100)
                )
                .setCallback { _, _, _, _ -> gui.close(); openSubmitConfirmPage(draft, isAdmin) }
            )
        } else {
            gui.setSlot(34, GuiElementBuilder(Items.BARRIER).setName(tr("gui.create.submit.need_item")))
        }

        gui.open()
    }

    // ── Step 5: pick item from inventory ────────────────────────────────────

    private fun openAddItemPage(
        shopName: String,
        isAdmin: Boolean,
        adminShopType: ShopType,
        pos: BlockPos,
        draft: ShopEntity,
        page: Int = 0
    ) {
        val gui = object : SimpleGui(MenuType.GENERIC_9x5, playerEntity, false) {
            override fun onPlayerClose(auto: Boolean) { removeGui(playerEntity); super.onPlayerClose(auto) }
        }
        gui.title = tr("gui.create.add_item.title")

        // ── Deduplicate: merge stacks of the same item+components ──
        val inv = playerEntity.inventory
        // Key: item registry id + encoded components string → Pair(representative stack, total count)
        val merged = linkedMapOf<String, Pair<ItemStack, Int>>()
        for (slot in 0 until inv.containerSize) {
            val stack = inv.getItem(slot)
            if (stack.isEmpty) continue
            val key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(stack.item).toString() +
                      "|" + stack.components.toString()
            val existing = merged[key]
            if (existing == null) {
                merged[key] = Pair(stack.copy(), stack.count)
            } else {
                merged[key] = Pair(existing.first, existing.second + stack.count)
            }
        }

        // Filter out items already in draft (same type+components)
        val draftKeys = draft.items.map { item ->
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(item.item.item.value()).toString() +
            "|" + item.item.itemStack.components.toString()
        }.toSet()

        val availableItems = merged.entries
            .filter { it.key !in draftKeys }
            .map { it.value } // List<Pair<ItemStack, totalCount>>

        // Pagination: 21 items per page (3 rows × 7, centred with glass border)
        val itemsPerPage = 21
        val totalPages = maxOf(1, (availableItems.size + itemsPerPage - 1) / itemsPerPage)
        val currentPage = page.coerceIn(0, totalPages - 1)
        val pageItems = availableItems.drop(currentPage * itemsPerPage).take(itemsPerPage)

        // ── Layout: rows 0-2 = items (slots 0-26), row 3 = filler, row 4 = controls ──
        for (i in 0 until 36) gui.setSlot(i, glassPane(8))
        for (i in 36 until 45) gui.setSlot(i, glassPane(7))

        pageItems.forEachIndexed { idx, (stack, totalCount) ->
            val capturedStack = stack.copy()
            val capturedCount = totalCount
            gui.setSlot(idx, GuiElementBuilder.from(capturedStack.copyWithCount(minOf(capturedCount, 64)))
                .setName(capturedStack.hoverName)
                .addLoreLine(tr("gui.create.add_item.total", capturedCount))
                .addLoreLine(tr("gui.create.add_item.click_select"))
                .setCallback { _, _, _, _ ->
                    gui.close()
                    openQtyInputPage(shopName, isAdmin, adminShopType, pos, draft, capturedStack, capturedCount)
                }
            )
        }

        // ── Controls row (row 4, slots 36-44) ──
        // Back
        gui.setSlot(36, GuiElementBuilder(Items.ARROW)
            .setName(tr("gui.back"))
            .setCallback { _, _, _, _ -> gui.close(); openItemListPage(shopName, isAdmin, adminShopType, pos, draft) }
        )
        // Prev page
        if (currentPage > 0) {
            gui.setSlot(39, GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(tr("gui.create.page.prev", currentPage, totalPages))
                .setCallback { _, _, _, _ -> openAddItemPage(shopName, isAdmin, adminShopType, pos, draft, currentPage - 1) }
            )
        }
        // Page indicator
        gui.setSlot(40, GuiElementBuilder(Items.PAPER)
            .setName(tr("gui.create.page.info", currentPage + 1, totalPages))
        )
        // Next page
        if (currentPage < totalPages - 1) {
            gui.setSlot(41, GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(tr("gui.create.page.next", currentPage + 2, totalPages))
                .setCallback { _, _, _, _ -> openAddItemPage(shopName, isAdmin, adminShopType, pos, draft, currentPage + 1) }
            )
        }
        // Browse containers button
        gui.setSlot(43, GuiElementBuilder(Items.DYED_SHULKER_BOX.purple)
            .setName(tr("gui.create.container.browse"))
            .addLoreLine(tr("gui.create.container.browse.lore"))
            .setCallback { _, _, _, _ -> gui.close(); openContainerPickerPage(shopName, isAdmin, adminShopType, pos, draft) }
        )
        // Empty inventory hint
        if (availableItems.isEmpty()) {
            gui.setSlot(22, GuiElementBuilder(Items.BARRIER)
                .setName(tr("gui.create.add_item.empty"))
            )
        }

        gui.open()
    }

    // ── Step 5b: pick a container (shulker box / bundle) ────────────────────

    private fun openContainerPickerPage(
        shopName: String,
        isAdmin: Boolean,
        adminShopType: ShopType,
        pos: BlockPos,
        draft: ShopEntity,
        page: Int = 0
    ) {
        // Collect containers: inventory stacks + nearby world shulker boxes
        val invContainers = findContainersInInventory(playerEntity)
            .map { (slot, stack) -> ContainerSource.InventorySlot(slot, stack) }

        val worldBoxes = findShulkerBoxesInWorld(playerEntity.level(), playerEntity.blockPosition())
            .map { (boxPos, _) -> ContainerSource.WorldBlock(boxPos) }

        val allSources: List<ContainerSource> = invContainers + worldBoxes

        val itemsPerPage = 21
        val totalPages = maxOf(1, (allSources.size + itemsPerPage - 1) / itemsPerPage)
        val currentPage = page.coerceIn(0, totalPages - 1)
        val pageSources = allSources.drop(currentPage * itemsPerPage).take(itemsPerPage)

        val gui = object : SimpleGui(MenuType.GENERIC_9x5, playerEntity, false) {
            override fun onPlayerClose(auto: Boolean) { removeGui(playerEntity); super.onPlayerClose(auto) }
        }
        gui.title = tr("gui.create.container.title")

        for (i in 0 until 36) gui.setSlot(i, glassPane(8))
        for (i in 36 until 45) gui.setSlot(i, glassPane(7))

        pageSources.forEachIndexed { idx, source ->
            when (source) {
                is ContainerSource.InventorySlot -> {
                    val stack = source.stack
                    val name = if (stack.hoverName.string.isNotBlank()) stack.hoverName
                               else Component.translatable(stack.item.descriptionId)
                    gui.setSlot(idx, GuiElementBuilder.from(stack.copyWithCount(1))
                        .setName(name)
                        .addLoreLine(tr("gui.create.container.inv_slot", source.invSlot))
                        .addLoreLine(tr("gui.create.container.click_open"))
                        .setCallback { _, _, _, _ ->
                            gui.close()
                            openContainerContentsPage(shopName, isAdmin, adminShopType, pos, draft, source)
                        }
                    )
                }
                is ContainerSource.WorldBlock -> {
                    val blockState = playerEntity.level().getBlockState(source.pos)
                    val blockItem = blockState.block.asItem()
                    val icon = if (blockItem != Items.AIR) blockItem
                               else Items.SHULKER_BOX
                    gui.setSlot(idx, GuiElementBuilder(icon)
                        .setName(blockState.block.name)
                        .addLoreLine(tr("gui.create.container.world_pos", source.pos.x, source.pos.y, source.pos.z))
                        .addLoreLine(tr("gui.create.container.click_open"))
                        .setCallback { _, _, _, _ ->
                            gui.close()
                            openContainerContentsPage(shopName, isAdmin, adminShopType, pos, draft, source)
                        }
                    )
                }
                is ContainerSource.EnderChest -> {
                    // EnderChest is handled by the dedicated fixed button at slot 44,
                    // it will never appear in the paginated list — but the branch is
                    // required for exhaustive when coverage.
                }
            }
        }

        // Controls
        gui.setSlot(36, GuiElementBuilder(Items.ARROW)
            .setName(tr("gui.back"))
            .setCallback { _, _, _, _ -> gui.close(); openAddItemPage(shopName, isAdmin, adminShopType, pos, draft) }
        )
        if (currentPage > 0) {
            gui.setSlot(39, GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(tr("gui.create.page.prev", currentPage, totalPages))
                .setCallback { _, _, _, _ -> openContainerPickerPage(shopName, isAdmin, adminShopType, pos, draft, currentPage - 1) }
            )
        }
        gui.setSlot(40, GuiElementBuilder(Items.PAPER)
            .setName(tr("gui.create.page.info", currentPage + 1, totalPages))
        )
        if (currentPage < totalPages - 1) {
            gui.setSlot(41, GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(tr("gui.create.page.next", currentPage + 2, totalPages))
                .setCallback { _, _, _, _ -> openContainerPickerPage(shopName, isAdmin, adminShopType, pos, draft, currentPage + 1) }
            )
        }
        // Ender chest shortcut — always available regardless of pagination
        gui.setSlot(44, GuiElementBuilder(Items.ENDER_CHEST)
            .setName(tr("gui.create.container.ender_chest"))
            .addLoreLine(tr("gui.create.container.click_open"))
            .setCallback { _, _, _, _ ->
                gui.close()
                openContainerContentsPage(shopName, isAdmin, adminShopType, pos, draft, ContainerSource.EnderChest)
            }
        )
        if (allSources.isEmpty()) {
            gui.setSlot(22, GuiElementBuilder(Items.BARRIER)
                .setName(tr("gui.create.container.empty"))
            )
        }

        gui.open()
    }

    // ── Step 5c: browse items inside a specific container ───────────────────

    private fun openContainerContentsPage(
        shopName: String,
        isAdmin: Boolean,
        adminShopType: ShopType,
        pos: BlockPos,
        draft: ShopEntity,
        source: ContainerSource,
        page: Int = 0
    ) {
        // Build merged item map depending on source type
        val merged: LinkedHashMap<String, Pair<ItemStack, Int>> = when (source) {
            is ContainerSource.InventorySlot -> {
                // Re-read from live inventory in case it changed
                val liveStack = playerEntity.inventory.getItem(source.invSlot)
                if (liveStack.isEmpty) linkedMapOf()
                else getItemsInsideContainerStack(liveStack)
            }
            is ContainerSource.WorldBlock -> {
                findShulkerBoxesInWorld(playerEntity.level(), source.pos, 0)
                    .firstOrNull()?.second ?: linkedMapOf()
            }
            is ContainerSource.EnderChest -> ItemManager.getItemsInEnderChest(playerEntity)
        }

        // Filter out items already in draft
        val draftKeys = draft.items.map { item ->
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(item.item.item.value()).toString() +
            "|" + item.item.itemStack.components.toString()
        }.toSet()
        val availableItems = merged.entries.filter { it.key !in draftKeys }.map { it.value }

        val itemsPerPage = 21
        val totalPages = maxOf(1, (availableItems.size + itemsPerPage - 1) / itemsPerPage)
        val currentPage = page.coerceIn(0, totalPages - 1)
        val pageItems = availableItems.drop(currentPage * itemsPerPage).take(itemsPerPage)

        // Container display name for title
        val containerLabel: String = when (source) {
            is ContainerSource.InventorySlot -> source.stack.hoverName.string.ifBlank {
                playerEntity.level().server.registryAccess().let { source.stack.item.descriptionId }
            }
            is ContainerSource.WorldBlock -> "(${source.pos.x}, ${source.pos.y}, ${source.pos.z})"
            is ContainerSource.EnderChest -> tr("gui.create.container.ender_chest").string
        }

        val gui = object : SimpleGui(MenuType.GENERIC_9x5, playerEntity, false) {
            override fun onPlayerClose(auto: Boolean) { removeGui(playerEntity); super.onPlayerClose(auto) }
        }
        gui.title = tr("gui.create.container.contents.title", containerLabel)

        for (i in 0 until 36) gui.setSlot(i, glassPane(8))
        for (i in 36 until 45) gui.setSlot(i, glassPane(7))

        pageItems.forEachIndexed { idx, (stack, totalCount) ->
            val capturedStack = stack.copy()
            val capturedCount = totalCount
            gui.setSlot(idx, GuiElementBuilder.from(capturedStack.copyWithCount(minOf(capturedCount, 64)))
                .setName(capturedStack.hoverName)
                .addLoreLine(tr("gui.create.add_item.total", capturedCount))
                .addLoreLine(tr("gui.create.add_item.click_select"))
                .setCallback { _, _, _, _ ->
                    gui.close()
                    // Pass the container source so the price/submit flow can use it
                    openQtyInputPageFromContainer(
                        shopName, isAdmin, adminShopType, pos, draft,
                        capturedStack, capturedCount, source
                    )
                }
            )
        }

        // Controls
        gui.setSlot(36, GuiElementBuilder(Items.ARROW)
            .setName(tr("gui.back"))
            .setCallback { _, _, _, _ -> gui.close(); openContainerPickerPage(shopName, isAdmin, adminShopType, pos, draft) }
        )
        if (currentPage > 0) {
            gui.setSlot(39, GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(tr("gui.create.page.prev", currentPage, totalPages))
                .setCallback { _, _, _, _ -> openContainerContentsPage(shopName, isAdmin, adminShopType, pos, draft, source, currentPage - 1) }
            )
        }
        gui.setSlot(40, GuiElementBuilder(Items.PAPER)
            .setName(tr("gui.create.page.info", currentPage + 1, totalPages))
        )
        if (currentPage < totalPages - 1) {
            gui.setSlot(41, GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(tr("gui.create.page.next", currentPage + 2, totalPages))
                .setCallback { _, _, _, _ -> openContainerContentsPage(shopName, isAdmin, adminShopType, pos, draft, source, currentPage + 1) }
            )
        }
        if (availableItems.isEmpty()) {
            gui.setSlot(22, GuiElementBuilder(Items.BARRIER)
                .setName(tr("gui.create.container.contents.empty"))
            )
        }

        gui.open()
    }

    // ── Step 6a (container variant): enter quantity ──────────────────────────

    private fun openQtyInputPageFromContainer(
        shopName: String,
        isAdmin: Boolean,
        adminShopType: ShopType,
        pos: BlockPos,
        draft: ShopEntity,
        selectedStack: ItemStack,
        totalCount: Int,
        source: ContainerSource
    ) {
        val anvilGui = object : AnvilInputGui(playerEntity, false) {
            override fun onPlayerClose(auto: Boolean) { removeGui(playerEntity); super.onPlayerClose(auto) }
            override fun onInput(input: String) {}
        }
        anvilGui.title = tr("gui.create.qty.title")
        anvilGui.setSlot(0, buildAnvilInputItem(selectedStack, tr("gui.create.qty.enter"))
            .also { b ->
                if (!isAdmin && totalCount > 0) b.addLoreLine(tr("gui.create.qty.available", totalCount))
                val sourceLore = when (source) {
                    is ContainerSource.InventorySlot -> tr("gui.create.container.source.inv")
                    is ContainerSource.WorldBlock    -> tr("gui.create.container.source.world",
                        source.pos.x, source.pos.y, source.pos.z)
                    is ContainerSource.EnderChest    -> tr("gui.create.container.source.ender")
                }
                b.addLoreLine(sourceLore)
            }
        )
        anvilGui.setSlot(1, GuiElementBuilder(Items.CLOCK)
            .setName(tr("gui.create.quick.qty"))
            .addLoreLine(tr("gui.create.quick.left", "1"))
            .addLoreLine(tr("gui.create.quick.right", "64"))
            .setCallback { _, clickType, _, _ ->
                val quick = parseQuickValue(clickType, "1", "64") ?: return@setCallback
                anvilGui.close()
                openPriceInputPageFromContainer(shopName, isAdmin, adminShopType, pos, draft,
                    selectedStack, quick.toInt(), totalCount, source)
            }
        )
        anvilGui.setSlot(2, GuiElementBuilder(Items.DYE.lime)
            .setName(tr("gui.create.qty.confirm"))
            .setCallback { _, clickType, _, _ ->
                if (clickType != ClickType.MOUSE_LEFT && clickType != ClickType.MOUSE_RIGHT) return@setCallback
                val qty = anvilGui.input.trim().toIntOrNull()
                if (qty == null || qty < 1 || qty > 99) {
                    playerEntity.sendSystemMessage(tr("gui.create.qty.invalid"))
                    anvilGui.close()
                    openQtyInputPageFromContainer(shopName, isAdmin, adminShopType, pos, draft, selectedStack, totalCount, source)
                    return@setCallback
                }
                if (!isAdmin && qty > totalCount) {
                    playerEntity.sendSystemMessage(tr("gui.create.qty.not_enough", totalCount))
                    anvilGui.close()
                    openQtyInputPageFromContainer(shopName,
                        false, adminShopType, pos, draft, selectedStack, totalCount, source)
                    return@setCallback
                }
                anvilGui.close()
                openPriceInputPageFromContainer(shopName, isAdmin, adminShopType, pos, draft,
                    selectedStack, qty, totalCount, source)
            }
        )
        anvilGui.open()
    }

    private fun openPriceInputPageFromContainer(
        shopName: String,
        isAdmin: Boolean,
        adminShopType: ShopType,
        pos: BlockPos,
        draft: ShopEntity,
        selectedStack: ItemStack,
        qty: Int,
        totalCount: Int,
        source: ContainerSource
    ) {
        val anvilGui = object : AnvilInputGui(playerEntity, false) {
            override fun onPlayerClose(auto: Boolean) { removeGui(playerEntity); super.onPlayerClose(auto) }
            override fun onInput(input: String) {}
        }
        anvilGui.title = tr("gui.create.price.title")
        anvilGui.setSlot(0, buildAnvilInputItem(selectedStack, tr("gui.create.price.enter", qty))
            .also { b ->
                if (!isAdmin && totalCount > 0) b.addLoreLine(tr("gui.create.qty.available", totalCount))
            }
        )
        anvilGui.setSlot(1, GuiElementBuilder(Items.GOLD_NUGGET)
            .setName(tr("gui.create.quick.price"))
            .addLoreLine(tr("gui.create.quick.left", "1"))
            .addLoreLine(tr("gui.create.quick.right", "10"))
            .setCallback { _, clickType, _, _ ->
                val quick = parseQuickValue(clickType, "1", "10")?.toDoubleOrNull() ?: return@setCallback
                anvilGui.close()
                if (isAdmin) {
                    openAdminStockInputPage(shopName, adminShopType, pos, draft, selectedStack, qty, quick)
                } else {
                    val removed = when (source) {
                        is ContainerSource.InventorySlot ->
                            removeItemFromShulkerBoxesInInventory(playerEntity, selectedStack.copyWithCount(1), qty)
                        is ContainerSource.WorldBlock ->
                            removeItemFromShulkerBoxBlockEntity(
                                playerEntity.level(), source.pos, selectedStack.copyWithCount(1), qty)
                        is ContainerSource.EnderChest ->
                            ItemManager.removeItemFromEnderChest(playerEntity, selectedStack.copyWithCount(1), qty)
                    }
                    val stock = removed + if (removed < qty) {
                        removeItemFromInventory(playerEntity, selectedStack.copyWithCount(1), qty - removed)
                    } else 0
                    val newItem = buildItemManager(selectedStack, qty, quick, stock)
                    if (newItem != null && checkCanAddTradeOffer(draft, newItem, playerEntity)) draft.items.add(newItem)
                    openItemListPage(shopName, false, adminShopType, pos, draft)
                }
            }
        )
        anvilGui.setSlot(2, GuiElementBuilder(Items.DYE.lime)
            .setName(tr("gui.create.price.confirm"))
            .setCallback { _, clickType, _, _ ->
                if (clickType != ClickType.MOUSE_LEFT && clickType != ClickType.MOUSE_RIGHT) return@setCallback
                val price = anvilGui.input.trim().toDoubleOrNull()
                if (price == null || price < 0.1) {
                    playerEntity.sendSystemMessage(tr("gui.create.price.invalid"))
                    anvilGui.close()
                    openPriceInputPageFromContainer(shopName, isAdmin, adminShopType, pos, draft, selectedStack, qty, totalCount, source)
                    return@setCallback
                }
                anvilGui.close()
                if (isAdmin) {
                    openAdminStockInputPage(shopName, adminShopType, pos, draft, selectedStack, qty, price)
                } else {
                    // Take items from container source first, fall back to regular inventory
                    val removed = when (source) {
                        is ContainerSource.InventorySlot ->
                            removeItemFromShulkerBoxesInInventory(playerEntity, selectedStack.copyWithCount(1), qty)
                        is ContainerSource.WorldBlock ->
                            removeItemFromShulkerBoxBlockEntity(
                                playerEntity.level(), source.pos, selectedStack.copyWithCount(1), qty)
                        is ContainerSource.EnderChest ->
                            ItemManager.removeItemFromEnderChest(playerEntity, selectedStack.copyWithCount(1), qty)
                    }
                    // If container didn't have enough, try to take the rest from regular inventory
                    val stock = removed + if (removed < qty) {
                        removeItemFromInventory(playerEntity, selectedStack.copyWithCount(1), qty - removed)
                    } else 0
                    val newItem = buildItemManager(selectedStack, qty, price, stock)
                    if (newItem != null && checkCanAddTradeOffer(draft, newItem, playerEntity)) draft.items.add(newItem)
                    openItemListPage(shopName, false, adminShopType, pos, draft)
                }
            }
        )
        anvilGui.open()
    }

    // ── Step 6a: enter quantity ──────────────────────────────────────────────

    private fun openQtyInputPage(
        shopName: String,
        isAdmin: Boolean,
        adminShopType: ShopType,
        pos: BlockPos,
        draft: ShopEntity,
        selectedStack: ItemStack,
        totalCount: Int = 0
    ) {
        val anvilGui = object : AnvilInputGui(playerEntity, false) {
            override fun onPlayerClose(auto: Boolean) { removeGui(playerEntity); super.onPlayerClose(auto) }
            override fun onInput(input: String) {}
        }
        anvilGui.title = tr("gui.create.qty.title")
        anvilGui.setSlot(0, buildAnvilInputItem(selectedStack, tr("gui.create.qty.enter"))
            .also { b ->
                if (!isAdmin && totalCount > 0)
                    b.addLoreLine(tr("gui.create.qty.available", totalCount))
            }
        )
        anvilGui.setSlot(1, GuiElementBuilder(Items.CLOCK)
            .setName(tr("gui.create.quick.qty"))
            .addLoreLine(tr("gui.create.quick.left", "1"))
            .addLoreLine(tr("gui.create.quick.right", "64"))
            .setCallback { _, clickType, _, _ ->
                val quick = parseQuickValue(clickType, "1", "64") ?: return@setCallback
                anvilGui.close()
                openPriceNumberInputPage(shopName, isAdmin, adminShopType, pos, draft, selectedStack, quick.toInt(), totalCount)
            }
        )
        anvilGui.setSlot(2, GuiElementBuilder(Items.DYE.lime)
            .setName(tr("gui.create.qty.confirm"))
            .setCallback { _, clickType, _, _ ->
                if (clickType != ClickType.MOUSE_LEFT && clickType != ClickType.MOUSE_RIGHT) return@setCallback
                val qty = anvilGui.input.trim().toIntOrNull()
                if (qty == null || qty < 1 || qty > 99) {
                    playerEntity.sendSystemMessage(tr("gui.create.qty.invalid"))
                    anvilGui.close()
                    openQtyInputPage(shopName, isAdmin, adminShopType, pos, draft, selectedStack, totalCount)
                    return@setCallback
                }
                if (!isAdmin && qty > totalCount) {
                    playerEntity.sendSystemMessage(tr("gui.create.qty.not_enough", totalCount))
                    anvilGui.close()
                    openQtyInputPage(shopName, false, adminShopType, pos, draft, selectedStack, totalCount)
                    return@setCallback
                }
                anvilGui.close()
                openPriceNumberInputPage(shopName, isAdmin, adminShopType, pos, draft, selectedStack, qty, totalCount)
            }
        )
        anvilGui.open()
    }

    // ── Step 6b: enter price ─────────────────────────────────────────────────

    private fun openPriceNumberInputPage(
        shopName: String,
        isAdmin: Boolean,
        adminShopType: ShopType,
        pos: BlockPos,
        draft: ShopEntity,
        selectedStack: ItemStack,
        qty: Int,
        totalCount: Int = 0
    ) {
        val anvilGui = object : AnvilInputGui(playerEntity, false) {
            override fun onPlayerClose(auto: Boolean) { removeGui(playerEntity); super.onPlayerClose(auto) }
            override fun onInput(input: String) {}
        }
        anvilGui.title = tr("gui.create.price.title")
        anvilGui.setSlot(0, buildAnvilInputItem(selectedStack, tr("gui.create.price.enter", qty))
            .also { b ->
                if (!isAdmin && totalCount > 0)
                    b.addLoreLine(tr("gui.create.qty.available", totalCount))
            }
        )
        anvilGui.setSlot(1, GuiElementBuilder(Items.GOLD_NUGGET)
            .setName(tr("gui.create.quick.price"))
            .addLoreLine(tr("gui.create.quick.left", "1"))
            .addLoreLine(tr("gui.create.quick.right", "10"))
            .setCallback { _, clickType, _, _ ->
                val quick = parseQuickValue(clickType, "1", "10")?.toDoubleOrNull() ?: return@setCallback
                anvilGui.close()
                if (isAdmin) {
                    openAdminStockInputPage(shopName, adminShopType, pos, draft, selectedStack, qty, quick)
                } else {
                    val stock = removeItemFromInventory(playerEntity, selectedStack.copyWithCount(1), qty)
                    val newItem = buildItemManager(selectedStack, qty, quick, stock)
                    if (newItem != null && checkCanAddTradeOffer(draft, newItem, playerEntity)) draft.items.add(newItem)
                    openItemListPage(shopName, false, adminShopType, pos, draft)
                }
            }
        )
        anvilGui.setSlot(2, GuiElementBuilder(Items.DYE.lime)
            .setName(tr("gui.create.price.confirm"))
            .setCallback { _, clickType, _, _ ->
                if (clickType != ClickType.MOUSE_LEFT && clickType != ClickType.MOUSE_RIGHT) return@setCallback
                val price = anvilGui.input.trim().toDoubleOrNull()
                if (price == null || price < 0.1) {
                    playerEntity.sendSystemMessage(tr("gui.create.price.invalid"))
                    anvilGui.close()
                    openPriceNumberInputPage(shopName, isAdmin, adminShopType, pos, draft, selectedStack, qty, totalCount)
                    return@setCallback
                }
                anvilGui.close()
                if (isAdmin) {
                    openAdminStockInputPage(shopName, adminShopType, pos, draft, selectedStack, qty, price)
                } else {
                    val stock = removeItemFromInventory(playerEntity, selectedStack.copyWithCount(1), qty)
                    val newItem = buildItemManager(selectedStack, qty, price, stock)
                    if (newItem != null && checkCanAddTradeOffer(draft, newItem, playerEntity)) draft.items.add(newItem)
                    openItemListPage(shopName, false, adminShopType, pos, draft)
                }
            }
        )
        anvilGui.open()
    }

    // ── Step 6c (admin only): enter stock ────────────────────────────────────

    private fun openAdminStockInputPage(
        shopName: String,
        adminShopType: ShopType,
        pos: BlockPos,
        draft: ShopEntity,
        selectedStack: ItemStack,
        qty: Int,
        price: Double
    ) {
        val anvilGui = object : AnvilInputGui(playerEntity, false) {
            override fun onPlayerClose(auto: Boolean) { removeGui(playerEntity); super.onPlayerClose(auto) }
            override fun onInput(input: String) {}
        }
        anvilGui.title = tr("gui.create.stock.title")
        anvilGui.setSlot(0, buildAnvilInputItem(selectedStack, tr("gui.create.stock.enter")))
        anvilGui.setSlot(1, GuiElementBuilder(Items.CHEST)
            .setName(tr("gui.create.quick.stock"))
            .addLoreLine(tr("gui.create.quick.left", "0"))
            .addLoreLine(tr("gui.create.quick.right", "64"))
            .setCallback { _, clickType, _, _ ->
                val quick = parseQuickValue(clickType, "0", "64")?.toIntOrNull() ?: return@setCallback
                val newItem = buildItemManager(selectedStack, qty, price, quick)
                if (newItem != null && checkCanAddTradeOffer(draft, newItem, playerEntity)) draft.items.add(newItem)
                anvilGui.close()
                openItemListPage(shopName, true, adminShopType, pos, draft)
            }
        )
        anvilGui.setSlot(2, GuiElementBuilder(Items.DYE.lime)
            .setName(tr("gui.create.stock.confirm"))
            .setCallback { _, clickType, _, _ ->
                if (clickType != ClickType.MOUSE_LEFT && clickType != ClickType.MOUSE_RIGHT) return@setCallback
                val stock = anvilGui.input.trim().toIntOrNull()
                if (stock == null || stock < 0) {
                    playerEntity.sendSystemMessage(tr("gui.create.stock.invalid"))
                    anvilGui.close()
                    openAdminStockInputPage(shopName, adminShopType, pos, draft, selectedStack, qty, price)
                    return@setCallback
                }
                val newItem = buildItemManager(selectedStack, qty, price, stock)
                if (newItem != null && checkCanAddTradeOffer(draft, newItem, playerEntity)) draft.items.add(newItem)
                anvilGui.close()
                openItemListPage(shopName, true, adminShopType, pos, draft)
            }
        )
        anvilGui.open()
    }

    // ── Helper: build ItemManager ────────────────────────────────────────────

    private fun buildItemManager(stack: ItemStack, qty: Int, price: Double, stock: Int): ItemManager? {
        return try {
            val entry = net.minecraft.core.registries.BuiltInRegistries.ITEM.wrapAsHolder(stack.item)
            val tradedItem = net.minecraft.world.item.trading.ItemCost(
                entry, qty,
                net.minecraft.core.component.DataComponentExactPredicate.allOf(stack.copyWithCount(qty).components),
                stack.copyWithCount(qty)
            )
            ItemManager(tradedItem, qty, price, mutableMapOf("default" to stock), registries)
        } catch (_: Exception) {
            playerEntity.sendSystemMessage(tr("gui.create.item.error"))
            null
        }
    }

    // ── Final: submit ────────────────────────────────────────────────────────

    private fun openSubmitConfirmPage(draft: ShopEntity, isAdmin: Boolean) {
        val gui = object : SimpleGui(MenuType.GENERIC_9x3, playerEntity, false) {
            override fun onPlayerClose(auto: Boolean) { removeGui(playerEntity); super.onPlayerClose(auto) }
        }
        gui.title = tr("gui.confirm.title")
        border(gui, 3)

        gui.setSlot(13, GuiElementBuilder(Items.EMERALD)
            .setName(tr("gui.create.submit"))
            .addLoreLine(tr("gui.confirm.pending"))
            .addLoreLine(if (isAdmin) tr("gui.create.submit.admin") else tr("gui.create.submit.cost", checkPlayerMoney(playerEntity, registryAccess) / 100))
        )

        gui.setSlot(11, GuiElementBuilder(Items.DYE.lime)
            .setName(tr("gui.confirm.confirm"))
            .setCallback { _, _, _, _ ->
                gui.close()
                doSubmit(draft, isAdmin)
            }
        )

        gui.setSlot(15, GuiElementBuilder(Items.DYE.red)
            .setName(tr("gui.confirm.cancel"))
            .setCallback { _, _, _, _ ->
                gui.close()
                openItemListPage(
                    draft.shopname,
                    isAdmin,
                    draft.type,
                    BlockPos(draft.posX, draft.posY, draft.posZ),
                    draft
                )
            }
        )

        gui.open()
    }

    private fun doSubmit(draft: ShopEntity, isAdmin: Boolean) {
        val world = playerEntity.level()
        if (isAdmin) {
            customScope.launch {
                draft.adminShopCreate()
                playerEntity.level().server.execute {
                    val entity = draft.spawnOrRespawn(world)
                    synchronized(shopEntityList) { shopEntityList[draft.id] = entity }
                    playerEntity.sendSystemMessage(tr("commands.shop.create.success"))
                    notifyNameCommandHintIfNeeded(draft.shopname)
                    removeGui(playerEntity)
                }
            }
        } else {
            val amount = checkPlayerMoney(playerEntity, registryAccess)
            if (amount <= 0) {
                playerEntity.sendSystemMessage(tr("commands.shop.create.failed.lack"))
                offerItemToPlayer(playerEntity, draft.items)
                removeGui(playerEntity)
                return
            }
            customScope.launch {
                calculateAndTakeMoney(playerEntity, amount)
                draft.playerShopCreate()
                playerEntity.level().server.execute {
                    val entity = draft.spawnOrRespawn(world)
                    synchronized(shopEntityList) { shopEntityList[draft.id] = entity }
                    playerEntity.sendSystemMessage(tr("commands.shop.create.success"))
                    notifyNameCommandHintIfNeeded(draft.shopname)
                    removeGui(playerEntity)
                }
            }
        }
    }

    private fun notifyNameCommandHintIfNeeded(name: String) {
        if (name.contains(' ') || name.any { it.code > 127 }) {
            playerEntity.sendSystemMessage(tr("commands.shop.name.quote_hint"))
        }
    }

    // ── guiSet helpers ───────────────────────────────────────────────────────

    private fun addGui(player: ServerPlayer) = VillagerShopMain.guiSet.add(player)
    fun removeGui(player: ServerPlayer) = VillagerShopMain.guiSet.remove(player)
}
