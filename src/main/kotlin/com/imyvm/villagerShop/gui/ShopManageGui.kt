package com.imyvm.villagerShop.gui

import com.imyvm.villagerShop.VillagerShopMain
import com.imyvm.villagerShop.VillagerShopMain.Companion.shopDBService
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
import com.imyvm.villagerShop.shops.ShopEntity.Companion.checkCanAddTradeOffer
import eu.pb4.sgui.api.ClickType
import eu.pb4.sgui.api.elements.GuiElementBuilder
import eu.pb4.sgui.api.gui.AnvilInputGui
import eu.pb4.sgui.api.gui.SimpleGui
import kotlinx.coroutines.launch
import net.minecraft.commands.CommandBuildContext
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponentExactPredicate
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.trading.ItemCost

// ─── Container source (local copy, mirrors ShopCreateGui) ───────────────────

private sealed class MgContainerSource {
    data class InventorySlot(val invSlot: Int, val stack: ItemStack) : MgContainerSource()
    data class WorldBlock(val pos: BlockPos) : MgContainerSource()
    object EnderChest : MgContainerSource()
}

// ─── ShopManageGui ──────────────────────────────────────────────────────────

/**
 * GUI for managing existing shops owned by [playerEntity].
 * Entry points:
 *  - [open]       → shop list (all owner's shops)
 *  - [openFor]    → directly open a specific shop's manage page
 */
class ShopManageGui(
    private val playerEntity: ServerPlayer,
    private val registryAccess: CommandBuildContext
) {
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

    private val registries: HolderLookup.Provider = registryAccess

    // ── Public entry points ──────────────────────────────────────────────────

    /** Opens the shop-list page showing all shops owned by the player. */
    fun open() {
        addGui(playerEntity)
        openShopListPage()
    }

    /** Opens the manage page for a specific already-loaded [shop] directly. */
    fun openFor(shop: ShopEntity) {
        addGui(playerEntity)
        openShopMainPage(shop)
    }

    // ── GUI factory helpers ──────────────────────────────────────────────────

    private fun simpleGui(type: MenuType<*>): SimpleGui =
        object : SimpleGui(type, playerEntity, false) {
            override fun onPlayerClose(auto: Boolean) { removeGui(playerEntity); super.onPlayerClose(auto) }
        }

    private fun anvilGui(): AnvilInputGui =
        object : AnvilInputGui(playerEntity, false) {
            override fun onPlayerClose(auto: Boolean) { removeGui(playerEntity); super.onPlayerClose(auto) }
            override fun onInput(input: String) {}
        }

    // ── Page 1: shop list ──────────────────���─────────────────────────────────

    private fun openShopListPage(page: Int = 0) {
        val shops = shopDBService.readByOwner(playerEntity.scoreboardName, registryAccess)

        val perPage = 21
        val totalPages = maxOf(1, (shops.size + perPage - 1) / perPage)
        val current = page.coerceIn(0, totalPages - 1)
        val pageShops = shops.drop(current * perPage).take(perPage)

        val gui = simpleGui(MenuType.GENERIC_9x5)
        gui.title = tr("gui.manage.list.title")

        for (i in 0 until 36) gui.setSlot(i, glassPane(8))
        for (i in 36 until 45) gui.setSlot(i, glassPane(7))

        pageShops.forEachIndexed { idx, shop ->
            val pos = BlockPos(shop.posX, shop.posY, shop.posZ)
            val icon = when (shop.type) {
                ShopType.SELL             -> Items.EMERALD
                ShopType.UNLIMITED_BUY    -> Items.GOLD_INGOT
                ShopType.REFRESHABLE_SELL -> Items.EMERALD_BLOCK
                ShopType.REFRESHABLE_BUY  -> Items.GOLD_BLOCK
            }
            gui.setSlot(idx, GuiElementBuilder(icon)
                .setName(Component.literal(shop.shopname))
                .addLoreLine(tr("gui.manage.list.type", shop.type.name))
                .addLoreLine(tr("gui.manage.list.pos", pos.x, pos.y, pos.z))
                .addLoreLine(tr("gui.manage.list.items", shop.items.size))
                .addLoreLine(tr("gui.manage.list.click_manage"))
                .setCallback { _, _, _, _ ->
                    gui.close()
                    openShopMainPage(shop)
                }
            )
        }

        // Controls
        gui.setSlot(36, GuiElementBuilder(Items.BARRIER)
            .setName(tr("gui.manage.list.close"))
            .setCallback { _, _, _, _ -> gui.close(); removeGui(playerEntity) }
        )
        if (current > 0) {
            gui.setSlot(39, GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(tr("gui.create.page.prev", current, totalPages))
                .setCallback { _, _, _, _ -> openShopListPage(current - 1) }
            )
        }
        gui.setSlot(40, GuiElementBuilder(Items.PAPER)
            .setName(tr("gui.create.page.info", current + 1, totalPages))
        )
        if (current < totalPages - 1) {
            gui.setSlot(41, GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(tr("gui.create.page.next", current + 2, totalPages))
                .setCallback { _, _, _, _ -> openShopListPage(current + 1) }
            )
        }
        if (shops.isEmpty()) {
            gui.setSlot(22, GuiElementBuilder(Items.BARRIER)
                .setName(tr("gui.manage.list.empty"))
            )
        }

        gui.open()
    }

    // ── Page 2: manage a single shop ─────────────────────────────────────────

    private fun openShopMainPage(shop: ShopEntity) {
        val gui = simpleGui(MenuType.GENERIC_9x3)
        gui.title = tr("gui.manage.main.title", shop.shopname)
        border(gui, 3)

        val pos = BlockPos(shop.posX, shop.posY, shop.posZ)

        // Centre: shop info display
        gui.setSlot(13, GuiElementBuilder(Items.OAK_SIGN)
            .setName(Component.literal(shop.shopname))
            .addLoreLine(tr("gui.manage.main.id", shop.id))
            .addLoreLine(tr("gui.manage.main.type", shop.type.name))
            .addLoreLine(tr("gui.manage.main.pos", pos.x, pos.y, pos.z))
            .addLoreLine(tr("gui.manage.main.items", shop.items.size))
            .addLoreLine(tr("gui.manage.main.income", shop.income))
        )

        // Row 2 actions
        // Rename
        gui.setSlot(10, GuiElementBuilder(Items.NAME_TAG)
            .setName(tr("gui.manage.main.rename"))
            .addLoreLine(tr("gui.manage.main.rename.lore"))
            .addLoreLine(tr("gui.manage.main.rename.lore2"))
            .setCallback { _, _, _, _ -> gui.close(); openRenameInputPage(shop) }
        )
        // Move
        gui.setSlot(11, GuiElementBuilder(Items.COMPASS)
            .setName(tr("gui.manage.main.move"))
            .addLoreLine(tr("gui.manage.main.move.lore"))
            .setCallback { _, _, _, _ ->
                gui.close()
                openMovePositionPage(shop, BlockPos(shop.posX, shop.posY, shop.posZ))
            }
        )
        // Items
        gui.setSlot(15, GuiElementBuilder(Items.CHEST)
            .setName(tr("gui.manage.main.items_btn"))
            .addLoreLine(tr("gui.manage.main.items_btn.lore"))
            .setCallback { _, _, _, _ -> gui.close(); openItemManagePage(shop) }
        )
        // Restock
        gui.setSlot(14, GuiElementBuilder(Items.BARREL)
            .setName(tr("gui.manage.main.restock"))
            .addLoreLine(tr("gui.manage.main.restock.lore"))
            .setCallback { _, _, _, _ -> gui.close(); openRestockItemSelectPage(shop) }
        )
        // Delete
        gui.setSlot(16, GuiElementBuilder(Items.TNT)
            .setName(tr("gui.manage.main.delete"))
            .addLoreLine(tr("gui.manage.main.delete.lore"))
            .setCallback { _, _, _, _ -> gui.close(); openDeleteConfirmPage(shop) }
        )

        // Back to list
        gui.setSlot(18, GuiElementBuilder(Items.ARROW)
            .setName(tr("gui.back"))
            .setCallback { _, _, _, _ -> gui.close(); openShopListPage() }
        )

        gui.open()
    }

    // ── Page 3: rename via anvil ─────────────────────────────────────────────

    private fun openRenameInputPage(shop: ShopEntity) {
        val gui = anvilGui()
        gui.title = tr("gui.manage.rename.title")
        gui.setSlot(0, buildAnvilInputItem(ItemStack(Items.NAME_TAG), tr("gui.create.name.placeholder"))
            .addLoreLine(tr("commands.shopinfo.shopname", shop.shopname))
        )
        gui.setSlot(2, GuiElementBuilder(Items.LIME_DYE)
            .setName(tr("gui.manage.rename.confirm"))
            .setCallback { _, clickType, _, _ ->
                if (clickType != ClickType.MOUSE_LEFT && clickType != ClickType.MOUSE_RIGHT) return@setCallback
                val newName = gui.input.trim()
                if (newName.isEmpty()) { playerEntity.sendSystemMessage(tr("gui.create.name.empty")); return@setCallback }
                shop.shopname = newName
                shop.updateAsync()
                shopEntityList.getOrDefault(shop.id, null)?.customName = Component.literal(newName)
                playerEntity.sendSystemMessage(tr("gui.manage.rename.success", newName))
                notifyNameCommandHintIfNeeded(newName)
                gui.close()
                openShopMainPage(shop)
            }
        )
        gui.open()
    }

    // ── Page 4: move position (reuses create-style XYZ adjuster) ────────────

    private fun openMovePositionPage(
        shop: ShopEntity,
        basePos: BlockPos,
        offsetX: Int = 0,
        offsetY: Int = 0,
        offsetZ: Int = 0
    ) {
        val pos = BlockPos(basePos.x + offsetX, basePos.y + offsetY, basePos.z + offsetZ)

        val gui = simpleGui(MenuType.GENERIC_9x5)
        gui.title = tr("gui.manage.move.title")
        for (i in 0 until 45) gui.setSlot(i, glassPane(8))

        fun adj(delta: Int, axis: String): () -> Unit = {
            val nx = if (axis == "x") offsetX + delta else offsetX
            val ny = if (axis == "y") offsetY + delta else offsetY
            val nz = if (axis == "z") offsetZ + delta else offsetZ
            openMovePositionPage(shop, basePos, nx, ny, nz)
        }

        fun offsetLabel(axis: String, value: Int, offset: Int) =
            GuiElementBuilder(Items.WHITE_CONCRETE)
                .setName(Component.literal("$axis: $value  (${if (offset >= 0) "+$offset" else "$offset"})"))

        // Current pos display
        gui.setSlot(22, GuiElementBuilder(Items.COMPASS)
            .setName(tr("gui.create.pos.current", pos.x, pos.y, pos.z))
            .addLoreLine(tr("gui.create.pos.lore"))
        )

        // X row (10-16)
        gui.setSlot(10, GuiElementBuilder(Items.RED_CONCRETE).setName(tr("gui.create.pos.x_minus", 5))
            .setCallback { _, _, _, _ -> adj(-5, "x")() })
        gui.setSlot(11, GuiElementBuilder(Items.ORANGE_CONCRETE).setName(tr("gui.create.pos.x_minus", 1))
            .setCallback { _, _, _, _ -> adj(-1, "x")() })
        gui.setSlot(13, offsetLabel("X", pos.x, offsetX))
        gui.setSlot(15, GuiElementBuilder(Items.ORANGE_CONCRETE).setName(tr("gui.create.pos.x_plus", 1))
            .setCallback { _, _, _, _ -> adj(1, "x")() })
        gui.setSlot(16, GuiElementBuilder(Items.RED_CONCRETE).setName(tr("gui.create.pos.x_plus", 5))
            .setCallback { _, _, _, _ -> adj(5, "x")() })

        // Y row (19-25)
        gui.setSlot(19, GuiElementBuilder(Items.BLUE_CONCRETE).setName(tr("gui.create.pos.y_minus", 5))
            .setCallback { _, _, _, _ -> adj(-5, "y")() })
        gui.setSlot(20, GuiElementBuilder(Items.LIGHT_BLUE_CONCRETE).setName(tr("gui.create.pos.y_minus", 1))
            .setCallback { _, _, _, _ -> adj(-1, "y")() })
        gui.setSlot(24, GuiElementBuilder(Items.LIGHT_BLUE_CONCRETE).setName(tr("gui.create.pos.y_plus", 1))
            .setCallback { _, _, _, _ -> adj(1, "y")() })
        gui.setSlot(25, GuiElementBuilder(Items.BLUE_CONCRETE).setName(tr("gui.create.pos.y_plus", 5))
            .setCallback { _, _, _, _ -> adj(5, "y")() })

        // Z row (28-34)
        gui.setSlot(28, GuiElementBuilder(Items.GREEN_CONCRETE).setName(tr("gui.create.pos.z_minus", 5))
            .setCallback { _, _, _, _ -> adj(-5, "z")() })
        gui.setSlot(29, GuiElementBuilder(Items.LIME_CONCRETE).setName(tr("gui.create.pos.z_minus", 1))
            .setCallback { _, _, _, _ -> adj(-1, "z")() })
        gui.setSlot(31, offsetLabel("Z", pos.z, offsetZ))
        gui.setSlot(33, GuiElementBuilder(Items.LIME_CONCRETE).setName(tr("gui.create.pos.z_plus", 1))
            .setCallback { _, _, _, _ -> adj(1, "z")() })
        gui.setSlot(34, GuiElementBuilder(Items.GREEN_CONCRETE).setName(tr("gui.create.pos.z_plus", 5))
            .setCallback { _, _, _, _ -> adj(5, "z")() })

        // Bottom row
        gui.setSlot(36, GuiElementBuilder(Items.ARROW).setName(tr("gui.back"))
            .setCallback { _, _, _, _ -> gui.close(); openShopMainPage(shop) }
        )
        gui.setSlot(40, GuiElementBuilder(Items.BARRIER).setName(tr("gui.create.pos.reset"))
            .setCallback { _, _, _, _ -> openMovePositionPage(shop, BlockPos(shop.posX, shop.posY, shop.posZ)) }
        )
        gui.setSlot(44, GuiElementBuilder(Items.EMERALD)
            .setName(tr("gui.manage.move.confirm"))
            .addLoreLine(tr("gui.create.pos.current", pos.x, pos.y, pos.z))
            .setCallback { _, _, _, _ ->
                shop.posX = pos.x; shop.posY = pos.y; shop.posZ = pos.z
                shop.updateAsync()
                shopEntityList.getOrDefault(shop.id, null)?.setPos(
                    pos.x + 0.5, pos.y + 1.0, pos.z + 0.5
                )
                playerEntity.sendSystemMessage(tr("gui.manage.move.success", pos.x, pos.y, pos.z))
                gui.close()
                openShopMainPage(shop)
            }
        )

        gui.open()
    }

    // ── Page 5: item management ──────────────────────────────────────────────

    private fun openItemManagePage(shop: ShopEntity) {
        val gui = simpleGui(MenuType.GENERIC_9x4)
        gui.title = tr("gui.manage.items.title", shop.shopname)

        for (i in 0..26) gui.setSlot(i, glassPane(8))
        for (i in 27..35) gui.setSlot(i, glassPane(7))

        shop.items.forEachIndexed { idx, item ->
            if (idx < 7) {
                val stock = item.stock["default"] ?: 0
                gui.setSlot(idx * 2 + 1, GuiElementBuilder.from(item.item.itemStack.copy())
                    .setName(item.item.itemStack.hoverName)
                    .addLoreLine(tr("gui.create.item.price", item.price))
                    .addLoreLine(tr("gui.create.item.qty", item.sellPerTime))
                    .addLoreLine(tr("gui.create.item.stock", stock))
                    .addLoreLine(tr("gui.manage.items.left_edit"))
                    .addLoreLine(tr("gui.manage.items.right_delete"))
                    .setCallback { _, clickType, _, _ ->
                        gui.close()
                        when (clickType) {
                            ClickType.MOUSE_RIGHT -> openDeleteItemConfirmPage(shop, item)
                            else                  -> openEditItemPage(shop, item)
                        }
                    }
                )
            }
        }

        // Add item button
        if (shop.items.size < 7) {
            gui.setSlot(27, GuiElementBuilder(Items.LIME_DYE)
                .setName(tr("gui.create.item.add"))
                .setCallback { _, _, _, _ -> gui.close(); openPickItemPage(shop) }
            )
        } else {
            gui.setSlot(27, glassPane(14))
        }

        // Back
        gui.setSlot(29, GuiElementBuilder(Items.ARROW)
            .setName(tr("gui.back"))
            .setCallback { _, _, _, _ -> gui.close(); openShopMainPage(shop) }
        )
        // Restock entry
        gui.setSlot(31, GuiElementBuilder(Items.BARREL)
            .setName(tr("gui.manage.main.restock"))
            .addLoreLine(tr("gui.manage.items.restock_btn.lore"))
            .setCallback { _, _, _, _ -> gui.close(); openRestockItemSelectPage(shop) }
        )

        gui.open()
    }

    // ── Page 5x: restock flow ─────────────────────────────────────────────────

    private fun openRestockItemSelectPage(shop: ShopEntity) {
        val gui = simpleGui(MenuType.GENERIC_9x3)
        gui.title = tr("gui.manage.stock.item.title", shop.shopname)
        border(gui, 3)

        if (shop.items.isEmpty()) {
            gui.setSlot(13, GuiElementBuilder(Items.BARRIER).setName(tr("gui.manage.stock.item.empty")))
            gui.setSlot(18, GuiElementBuilder(Items.ARROW)
                .setName(tr("gui.back"))
                .setCallback { _, _, _, _ -> gui.close(); openShopMainPage(shop) }
            )
            gui.open()
            return
        }

        shop.items.forEachIndexed { idx, item ->
            if (idx > 6) return@forEachIndexed
            val stock = item.stock["default"] ?: 0
            gui.setSlot(idx * 2 + 1, GuiElementBuilder.from(item.item.itemStack.copy())
                .setName(item.item.itemStack.hoverName)
                .addLoreLine(tr("gui.create.item.qty", item.sellPerTime))
                .addLoreLine(tr("gui.create.item.price", item.price))
                .addLoreLine(tr("gui.create.item.stock", stock))
                .addLoreLine(tr("gui.manage.stock.item.click"))
                .setCallback { _, _, _, _ -> gui.close(); openRestockSourcePage(shop, item) }
            )
        }

        gui.setSlot(18, GuiElementBuilder(Items.ARROW)
            .setName(tr("gui.back"))
            .setCallback { _, _, _, _ -> gui.close(); openItemManagePage(shop) }
        )
        gui.open()
    }

    private fun openRestockSourcePage(shop: ShopEntity, item: ItemManager) {
        val gui = simpleGui(MenuType.GENERIC_9x3)
        gui.title = tr("gui.manage.stock.source.title")
        border(gui, 3)

        val preview = item.item.itemStack.copyWithCount(1)
        gui.setSlot(13, GuiElementBuilder.from(preview)
            .setName(preview.hoverName)
            .addLoreLine(tr("gui.manage.stock.source.choose"))
        )

        gui.setSlot(10, GuiElementBuilder(Items.CHEST)
            .setName(tr("gui.manage.stock.source.inventory"))
            .addLoreLine(tr("gui.manage.stock.source.available", countAvailableFromSource(item.item.itemStack, null)))
            .setCallback { _, _, _, _ ->
                gui.close()
                openRestockQtyInputPage(shop, item, null)
            }
        )
        gui.setSlot(12, GuiElementBuilder(Items.PURPLE_SHULKER_BOX)
            .setName(tr("gui.manage.stock.source.container"))
            .addLoreLine(tr("gui.create.container.browse.lore"))
            .setCallback { _, _, _, _ ->
                gui.close()
                openRestockContainerPickerPage(shop, item)
            }
        )
        gui.setSlot(14, GuiElementBuilder(Items.ENDER_CHEST)
            .setName(tr("gui.create.container.ender_chest"))
            .addLoreLine(tr("gui.manage.stock.source.available", countAvailableFromSource(item.item.itemStack, MgContainerSource.EnderChest)))
            .setCallback { _, _, _, _ ->
                gui.close()
                openRestockQtyInputPage(shop, item, MgContainerSource.EnderChest)
            }
        )

        gui.setSlot(18, GuiElementBuilder(Items.ARROW)
            .setName(tr("gui.back"))
            .setCallback { _, _, _, _ -> gui.close(); openRestockItemSelectPage(shop) }
        )

        gui.open()
    }

    private fun openRestockContainerPickerPage(shop: ShopEntity, item: ItemManager, page: Int = 0) {
        val invContainers = findContainersInInventory(playerEntity)
            .map { (slot, stack) -> MgContainerSource.InventorySlot(slot, stack) }
        val worldBoxes = findShulkerBoxesInWorld(playerEntity.level(), playerEntity.blockPosition())
            .map { (p, _) -> MgContainerSource.WorldBlock(p) }
        val allSources = (invContainers + worldBoxes)
            .filter { countAvailableFromSource(item.item.itemStack, it) > 0 }

        val perPage = 21
        val totalPages = maxOf(1, (allSources.size + perPage - 1) / perPage)
        val current = page.coerceIn(0, totalPages - 1)
        val pageSources = allSources.drop(current * perPage).take(perPage)

        val gui = simpleGui(MenuType.GENERIC_9x5)
        gui.title = tr("gui.manage.stock.container.title")

        for (i in 0 until 36) gui.setSlot(i, glassPane(8))
        for (i in 36 until 45) gui.setSlot(i, glassPane(7))

        pageSources.forEachIndexed { idx, source ->
            when (source) {
                is MgContainerSource.InventorySlot -> {
                    val stack = source.stack
                    val name = stack.hoverName.takeIf { it.string.isNotBlank() } ?: Component.translatable(stack.item.descriptionId)
                    gui.setSlot(idx, GuiElementBuilder.from(stack.copyWithCount(1))
                        .setName(name)
                        .addLoreLine(tr("gui.create.container.inv_slot", source.invSlot))
                        .addLoreLine(tr("gui.manage.stock.source.available", countAvailableFromSource(item.item.itemStack, source)))
                        .addLoreLine(tr("gui.create.container.click_open"))
                        .setCallback { _, _, _, _ -> gui.close(); openRestockQtyInputPage(shop, item, source) }
                    )
                }
                is MgContainerSource.WorldBlock -> {
                    val blockState = playerEntity.level().getBlockState(source.pos)
                    val icon = blockState.block.asItem().takeIf { it != Items.AIR } ?: Items.SHULKER_BOX
                    gui.setSlot(idx, GuiElementBuilder(icon)
                        .setName(blockState.block.name)
                        .addLoreLine(tr("gui.create.container.world_pos", source.pos.x, source.pos.y, source.pos.z))
                        .addLoreLine(tr("gui.manage.stock.source.available", countAvailableFromSource(item.item.itemStack, source)))
                        .addLoreLine(tr("gui.create.container.click_open"))
                        .setCallback { _, _, _, _ -> gui.close(); openRestockQtyInputPage(shop, item, source) }
                    )
                }
                is MgContainerSource.EnderChest -> {}
            }
        }

        gui.setSlot(36, GuiElementBuilder(Items.ARROW)
            .setName(tr("gui.back"))
            .setCallback { _, _, _, _ -> gui.close(); openRestockSourcePage(shop, item) }
        )
        if (current > 0) {
            gui.setSlot(39, GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(tr("gui.create.page.prev", current, totalPages))
                .setCallback { _, _, _, _ -> openRestockContainerPickerPage(shop, item, current - 1) }
            )
        }
        gui.setSlot(40, GuiElementBuilder(Items.PAPER)
            .setName(tr("gui.create.page.info", current + 1, totalPages))
        )
        if (current < totalPages - 1) {
            gui.setSlot(41, GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(tr("gui.create.page.next", current + 2, totalPages))
                .setCallback { _, _, _, _ -> openRestockContainerPickerPage(shop, item, current + 1) }
            )
        }

        if (allSources.isEmpty()) {
            gui.setSlot(22, GuiElementBuilder(Items.BARRIER)
                .setName(tr("gui.manage.stock.container.empty"))
            )
        }

        gui.open()
    }

    private fun openRestockQtyInputPage(shop: ShopEntity, item: ItemManager, source: MgContainerSource?) {
        val available = countAvailableFromSource(item.item.itemStack, source)
        val gui = anvilGui()
        gui.title = tr("gui.manage.stock.qty.title")
        gui.setSlot(0, buildAnvilInputItem(item.item.itemStack, tr("gui.manage.stock.qty.enter"))
            .addLoreLine(tr("gui.manage.stock.source.available", available))
            .also { if (source != null) it.addLoreLine(sourceLore(source)) }
        )
        gui.setSlot(1, GuiElementBuilder(Items.CLOCK)
            .setName(tr("gui.create.quick.qty"))
            .addLoreLine(tr("gui.create.quick.left", "1"))
            .addLoreLine(tr("gui.create.quick.right", "64"))
            .setCallback { _, clickType, _, _ ->
                val quick = parseQuickValue(clickType, "1", "64")?.toIntOrNull() ?: return@setCallback
                if (quick > available) {
                    playerEntity.sendSystemMessage(tr("gui.create.qty.not_enough", available))
                    gui.close()
                    openRestockQtyInputPage(shop, item, source)
                    return@setCallback
                }
                val removed = removeFromSelectedSource(source, item.item.itemStack, quick)
                if (removed <= 0) {
                    playerEntity.sendSystemMessage(tr("gui.create.qty.not_enough", available))
                    gui.close()
                    openRestockQtyInputPage(shop, item, source)
                    return@setCallback
                }
                item.stock["default"] = (item.stock["default"] ?: 0) + removed
                shop.updateAsync()
                if (source != null) playerEntity.sendSystemMessage(tr("commands.stock.add.ok", removed))
                playerEntity.sendSystemMessage(tr("gui.manage.stock.success", removed))
                gui.close()
                openItemManagePage(shop)
            }
        )
        gui.setSlot(2, GuiElementBuilder(Items.LIME_DYE)
            .setName(tr("gui.create.qty.confirm"))
            .setCallback { _, clickType, _, _ ->
                if (clickType != ClickType.MOUSE_LEFT && clickType != ClickType.MOUSE_RIGHT) return@setCallback
                val qty = gui.input.trim().toIntOrNull()
                if (qty == null || qty < 1 || qty > 999999) {
                    playerEntity.sendSystemMessage(tr("gui.create.qty.invalid"))
                    gui.close()
                    openRestockQtyInputPage(shop, item, source)
                    return@setCallback
                }
                if (qty > available) {
                    playerEntity.sendSystemMessage(tr("gui.create.qty.not_enough", available))
                    gui.close()
                    openRestockQtyInputPage(shop, item, source)
                    return@setCallback
                }
                val removed = removeFromSelectedSource(source, item.item.itemStack, qty)
                if (removed <= 0) {
                    playerEntity.sendSystemMessage(tr("gui.create.qty.not_enough", available))
                    gui.close()
                    openRestockQtyInputPage(shop, item, source)
                    return@setCallback
                }
                item.stock["default"] = (item.stock["default"] ?: 0) + removed
                shop.updateAsync()
                if (source != null) playerEntity.sendSystemMessage(tr("commands.stock.add.ok", removed))
                playerEntity.sendSystemMessage(tr("gui.manage.stock.success", removed))
                gui.close()
                openItemManagePage(shop)
            }
        )
        gui.open()
    }

    // ── Page 5a: delete item with confirmation ───────────────────────────────

    private fun openDeleteItemConfirmPage(shop: ShopEntity, item: ItemManager) {
        val gui = simpleGui(MenuType.GENERIC_9x3)
        gui.title = tr("gui.manage.items.delete.title")
        border(gui, 3)
        val stock = item.stock["default"] ?: 0

        gui.setSlot(13, GuiElementBuilder.from(item.item.itemStack.copy())
            .setName(item.item.itemStack.hoverName)
            .addLoreLine(tr("gui.create.item.price", item.price))
            .addLoreLine(tr("gui.create.item.qty", item.sellPerTime))
            .addLoreLine(tr("gui.create.item.stock", stock))
            .addLoreLine(if (stock > 0) tr("gui.manage.items.delete.return", stock) else tr("gui.manage.items.delete.no_return"))
        )

        gui.setSlot(11, GuiElementBuilder(Items.LIME_DYE)
            .setName(tr("gui.manage.items.delete.confirm"))
            .setCallback { _, _, _, _ ->
                shop.deleteTradedItem(item.item.itemStack)
                if (stock > 0)
                    playerEntity.inventory.placeItemBackInInventory(ItemStack(item.item.item, stock))
                playerEntity.sendSystemMessage(tr("gui.manage.items.delete.success",
                    item.item.itemStack.hoverName))
                gui.close()
                openItemManagePage(shop)
            }
        )
        gui.setSlot(15, GuiElementBuilder(Items.RED_DYE)
            .setName(tr("gui.manage.items.delete.cancel"))
            .setCallback { _, _, _, _ -> gui.close(); openItemManagePage(shop) }
        )
        gui.open()
    }

    // ── Page 5b: edit an existing item (qty + price) ─────────────────────────

    private fun openEditItemPage(shop: ShopEntity, item: ItemManager) {
        val gui = anvilGui()
        gui.title = tr("gui.manage.items.edit.qty.title")
        gui.setSlot(0, buildAnvilInputItem(item.item.itemStack, tr("gui.manage.items.edit.qty.enter", item.sellPerTime))
            .addLoreLine(tr("gui.create.item.price", item.price))
        )
        gui.setSlot(1, GuiElementBuilder(Items.CLOCK)
            .setName(tr("gui.create.quick.qty"))
            .addLoreLine(tr("gui.create.quick.left", "1"))
            .addLoreLine(tr("gui.create.quick.right", "64"))
            .setCallback { _, clickType, _, _ ->
                val quick = parseQuickValue(clickType, "1", "64")?.toIntOrNull() ?: return@setCallback
                gui.close()
                openEditItemPricePage(shop, item, quick)
            }
        )
        gui.setSlot(2, GuiElementBuilder(Items.LIME_DYE)
            .setName(tr("gui.create.qty.confirm"))
            .setCallback { _, clickType, _, _ ->
                if (clickType != ClickType.MOUSE_LEFT && clickType != ClickType.MOUSE_RIGHT) return@setCallback
                val newQty = gui.input.trim().toIntOrNull()
                if (newQty == null || newQty < 1 || newQty > 99) {
                    playerEntity.sendSystemMessage(tr("gui.create.qty.invalid"))
                    gui.close()
                    openEditItemPage(shop, item)
                    return@setCallback
                }
                gui.close()
                openEditItemPricePage(shop, item, newQty)
            }
        )
        gui.open()
    }

    private fun openEditItemPricePage(shop: ShopEntity, item: ItemManager, newQty: Int) {
        val gui = anvilGui()
        gui.title = tr("gui.manage.items.edit.price.title")
        gui.setSlot(0, buildAnvilInputItem(item.item.itemStack, tr("gui.manage.items.edit.price.enter", item.price))
        )
        gui.setSlot(1, GuiElementBuilder(Items.GOLD_NUGGET)
            .setName(tr("gui.create.quick.price"))
            .addLoreLine(tr("gui.create.quick.left", "1"))
            .addLoreLine(tr("gui.create.quick.right", "10"))
            .setCallback { _, clickType, _, _ ->
                val quick = parseQuickValue(clickType, "1", "10")?.toDoubleOrNull() ?: return@setCallback
                item.sellPerTime = newQty
                item.price = quick
                shop.updateAsync()
                playerEntity.sendSystemMessage(tr("gui.manage.items.edit.success",
                    item.item.itemStack.hoverName, newQty, quick))
                gui.close()
                openItemManagePage(shop)
            }
        )
        gui.setSlot(2, GuiElementBuilder(Items.LIME_DYE)
            .setName(tr("gui.create.price.confirm"))
            .setCallback { _, clickType, _, _ ->
                if (clickType != ClickType.MOUSE_LEFT && clickType != ClickType.MOUSE_RIGHT) return@setCallback
                val newPrice = gui.input.trim().toDoubleOrNull()
                if (newPrice == null || newPrice < 0.1) {
                    playerEntity.sendSystemMessage(tr("gui.create.price.invalid"))
                    gui.close()
                    openEditItemPricePage(shop, item, newQty)
                    return@setCallback
                }
                item.sellPerTime = newQty
                item.price = newPrice
                shop.updateAsync()
                playerEntity.sendSystemMessage(tr("gui.manage.items.edit.success",
                    item.item.itemStack.hoverName, newQty, newPrice))
                gui.close()
                openItemManagePage(shop)
            }
        )
        gui.open()
    }

    // ── Page 5c: pick a new item to add (inventory) ──────────────────────────

    private fun openPickItemPage(shop: ShopEntity, page: Int = 0) {
        val inv = playerEntity.inventory
        val merged = linkedMapOf<String, Pair<ItemStack, Int>>()
        for (slot in 0 until inv.containerSize) {
            val stack = inv.getItem(slot)
            if (stack.isEmpty) continue
            val key = BuiltInRegistries.ITEM.getId(stack.item).toString() + "|" + stack.components.toString()
            val ex = merged[key]
            merged[key] = if (ex == null) Pair(stack.copy(), stack.count)
                          else Pair(ex.first, ex.second + stack.count)
        }
        val draftKeys = shop.items.map { i ->
            BuiltInRegistries.ITEM.getId(i.item.item.value()).toString() + "|" + i.item.itemStack.components.toString()
        }.toSet()
        val available = merged.entries.filter { it.key !in draftKeys }.map { it.value }

        val perPage = 21
        val totalPages = maxOf(1, (available.size + perPage - 1) / perPage)
        val current = page.coerceIn(0, totalPages - 1)
        val pageItems = available.drop(current * perPage).take(perPage)

        val gui = simpleGui(MenuType.GENERIC_9x5)
        gui.title = tr("gui.create.add_item.title")

        for (i in 0 until 36) gui.setSlot(i, glassPane(8))
        for (i in 36 until 45) gui.setSlot(i, glassPane(7))

        pageItems.forEachIndexed { idx, (stack, totalCount) ->
            gui.setSlot(idx, GuiElementBuilder.from(stack.copyWithCount(minOf(totalCount, 64)))
                .setName(stack.hoverName)
                .addLoreLine(tr("gui.create.add_item.total", totalCount))
                .addLoreLine(tr("gui.create.add_item.click_select"))
                .setCallback { _, _, _, _ ->
                    gui.close()
                    openAddQtyPage(shop, stack, totalCount, null)
                }
            )
        }

        gui.setSlot(36, GuiElementBuilder(Items.ARROW)
            .setName(tr("gui.back"))
            .setCallback { _, _, _, _ -> gui.close(); openItemManagePage(shop) }
        )
        if (current > 0) {
            gui.setSlot(39, GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(tr("gui.create.page.prev", current, totalPages))
                .setCallback { _, _, _, _ -> openPickItemPage(shop, current - 1) }
            )
        }
        gui.setSlot(40, GuiElementBuilder(Items.PAPER)
            .setName(tr("gui.create.page.info", current + 1, totalPages))
        )
        if (current < totalPages - 1) {
            gui.setSlot(41, GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(tr("gui.create.page.next", current + 2, totalPages))
                .setCallback { _, _, _, _ -> openPickItemPage(shop, current + 1) }
            )
        }
        // Container browse shortcut
        gui.setSlot(43, GuiElementBuilder(Items.PURPLE_SHULKER_BOX)
            .setName(tr("gui.create.container.browse"))
            .addLoreLine(tr("gui.create.container.browse.lore"))
            .setCallback { _, _, _, _ -> gui.close(); openPickContainerPage(shop) }
        )
        if (available.isEmpty()) {
            gui.setSlot(22, GuiElementBuilder(Items.BARRIER).setName(tr("gui.create.add_item.empty")))
        }

        gui.open()
    }

    // ── Page 5d: pick from container ─────────────────────────────────────────

    private fun openPickContainerPage(shop: ShopEntity, page: Int = 0) {
        val invContainers = findContainersInInventory(playerEntity)
            .map { (slot, stack) -> MgContainerSource.InventorySlot(slot, stack) }
        val worldBoxes = findShulkerBoxesInWorld(playerEntity.level(), playerEntity.blockPosition())
            .map { (p, _) -> MgContainerSource.WorldBlock(p) }
        val allSources: List<MgContainerSource> = invContainers + worldBoxes

        val perPage = 21
        val totalPages = maxOf(1, (allSources.size + perPage - 1) / perPage)
        val current = page.coerceIn(0, totalPages - 1)
        val pageSources = allSources.drop(current * perPage).take(perPage)

        val gui = simpleGui(MenuType.GENERIC_9x5)
        gui.title = tr("gui.create.container.title")

        for (i in 0 until 36) gui.setSlot(i, glassPane(8))
        for (i in 36 until 45) gui.setSlot(i, glassPane(7))

        pageSources.forEachIndexed { idx, source ->
            when (source) {
                is MgContainerSource.InventorySlot -> {
                    val stack = source.stack
                    val name = stack.hoverName.takeIf { it.string.isNotBlank() }
                        ?: Component.translatable(stack.item.descriptionId)
                    gui.setSlot(idx, GuiElementBuilder.from(stack.copyWithCount(1))
                        .setName(name)
                        .addLoreLine(tr("gui.create.container.inv_slot", source.invSlot))
                        .addLoreLine(tr("gui.create.container.click_open"))
                        .setCallback { _, _, _, _ -> gui.close(); openContainerItemsPage(shop, source) }
                    )
                }
                is MgContainerSource.WorldBlock -> {
                    val blockState = playerEntity.level().getBlockState(source.pos)
                    val icon = blockState.block.asItem().takeIf { it != Items.AIR } ?: Items.SHULKER_BOX
                    gui.setSlot(idx, GuiElementBuilder(icon)
                        .setName(blockState.block.name)
                        .addLoreLine(tr("gui.create.container.world_pos", source.pos.x, source.pos.y, source.pos.z))
                        .addLoreLine(tr("gui.create.container.click_open"))
                        .setCallback { _, _, _, _ -> gui.close(); openContainerItemsPage(shop, source) }
                    )
                }
                is MgContainerSource.EnderChest -> { /* handled by fixed button */ }
            }
        }

        gui.setSlot(36, GuiElementBuilder(Items.ARROW)
            .setName(tr("gui.back"))
            .setCallback { _, _, _, _ -> gui.close(); openPickItemPage(shop) }
        )
        if (current > 0) {
            gui.setSlot(39, GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(tr("gui.create.page.prev", current, totalPages))
                .setCallback { _, _, _, _ -> openPickContainerPage(shop, current - 1) }
            )
        }
        gui.setSlot(40, GuiElementBuilder(Items.PAPER)
            .setName(tr("gui.create.page.info", current + 1, totalPages))
        )
        if (current < totalPages - 1) {
            gui.setSlot(41, GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(tr("gui.create.page.next", current + 2, totalPages))
                .setCallback { _, _, _, _ -> openPickContainerPage(shop, current + 1) }
            )
        }
        gui.setSlot(44, GuiElementBuilder(Items.ENDER_CHEST)
            .setName(tr("gui.create.container.ender_chest"))
            .addLoreLine(tr("gui.create.container.click_open"))
            .setCallback { _, _, _, _ -> gui.close(); openContainerItemsPage(shop, MgContainerSource.EnderChest) }
        )
        if (allSources.isEmpty()) {
            gui.setSlot(22, GuiElementBuilder(Items.BARRIER).setName(tr("gui.create.container.empty")))
        }

        gui.open()
    }

    // ── Page 5e: items inside a container ────────────────────────────────────

    private fun openContainerItemsPage(
        shop: ShopEntity,
        source: MgContainerSource,
        page: Int = 0
    ) {
        val merged: LinkedHashMap<String, Pair<ItemStack, Int>> = when (source) {
            is MgContainerSource.InventorySlot -> {
                val live = playerEntity.inventory.getItem(source.invSlot)
                if (live.isEmpty) linkedMapOf() else getItemsInsideContainerStack(live)
            }
            is MgContainerSource.WorldBlock ->
                findShulkerBoxesInWorld(playerEntity.level(), source.pos, 0).firstOrNull()?.second ?: linkedMapOf()
            is MgContainerSource.EnderChest -> ItemManager.getItemsInEnderChest(playerEntity)
        }

        val draftKeys = shop.items.map { i ->
            BuiltInRegistries.ITEM.getId(i.item.item.value()).toString() + "|" + i.item.itemStack.components.toString()
        }.toSet()
        val available = merged.entries.filter { it.key !in draftKeys }.map { it.value }

        val containerLabel: String = when (source) {
            is MgContainerSource.InventorySlot -> source.stack.hoverName.string.ifBlank { source.stack.item.descriptionId }
            is MgContainerSource.WorldBlock    -> "(${source.pos.x}, ${source.pos.y}, ${source.pos.z})"
            is MgContainerSource.EnderChest    -> tr("gui.create.container.ender_chest").string
        }

        val perPage = 21
        val totalPages = maxOf(1, (available.size + perPage - 1) / perPage)
        val current = page.coerceIn(0, totalPages - 1)
        val pageItems = available.drop(current * perPage).take(perPage)

        val gui = simpleGui(MenuType.GENERIC_9x5)
        gui.title = tr("gui.create.container.contents.title", containerLabel)

        for (i in 0 until 36) gui.setSlot(i, glassPane(8))
        for (i in 36 until 45) gui.setSlot(i, glassPane(7))

        pageItems.forEachIndexed { idx, (stack, totalCount) ->
            gui.setSlot(idx, GuiElementBuilder.from(stack.copyWithCount(minOf(totalCount, 64)))
                .setName(stack.hoverName)
                .addLoreLine(tr("gui.create.add_item.total", totalCount))
                .addLoreLine(tr("gui.create.add_item.click_select"))
                .setCallback { _, _, _, _ ->
                    gui.close()
                    openAddQtyPage(shop, stack, totalCount, source)
                }
            )
        }

        gui.setSlot(36, GuiElementBuilder(Items.ARROW)
            .setName(tr("gui.back"))
            .setCallback { _, _, _, _ -> gui.close(); openPickContainerPage(shop) }
        )
        if (current > 0) {
            gui.setSlot(39, GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(tr("gui.create.page.prev", current, totalPages))
                .setCallback { _, _, _, _ -> openContainerItemsPage(shop, source, current - 1) }
            )
        }
        gui.setSlot(40, GuiElementBuilder(Items.PAPER)
            .setName(tr("gui.create.page.info", current + 1, totalPages))
        )
        if (current < totalPages - 1) {
            gui.setSlot(41, GuiElementBuilder(Items.SPECTRAL_ARROW)
                .setName(tr("gui.create.page.next", current + 2, totalPages))
                .setCallback { _, _, _, _ -> openContainerItemsPage(shop, source, current + 1) }
            )
        }
        if (available.isEmpty()) {
            gui.setSlot(22, GuiElementBuilder(Items.BARRIER).setName(tr("gui.create.container.contents.empty")))
        }

        gui.open()
    }

    // ── Page 6: enter qty for new item ──────────────────────────────────────

    private fun openAddQtyPage(
        shop: ShopEntity,
        stack: ItemStack,
        totalCount: Int,
        source: MgContainerSource?
    ) {
        val isAdmin = shop.admin == 1
        val gui = anvilGui()
        gui.title = tr("gui.create.qty.title")
        gui.setSlot(0, buildAnvilInputItem(stack, tr("gui.create.qty.enter"))
            .also { b ->
                if (!isAdmin && totalCount > 0) b.addLoreLine(tr("gui.create.qty.available", totalCount))
                if (source != null) b.addLoreLine(sourceLore(source))
            }
        )
        gui.setSlot(1, GuiElementBuilder(Items.CLOCK)
            .setName(tr("gui.create.quick.qty"))
            .addLoreLine(tr("gui.create.quick.left", "1"))
            .addLoreLine(tr("gui.create.quick.right", "64"))
            .setCallback { _, clickType, _, _ ->
                val quick = parseQuickValue(clickType, "1", "64")?.toIntOrNull() ?: return@setCallback
                gui.close()
                openAddPricePage(shop, stack, quick, totalCount, source)
            }
        )
        gui.setSlot(2, GuiElementBuilder(Items.LIME_DYE)
            .setName(tr("gui.create.qty.confirm"))
            .setCallback { _, clickType, _, _ ->
                if (clickType != ClickType.MOUSE_LEFT && clickType != ClickType.MOUSE_RIGHT) return@setCallback
                val qty = gui.input.trim().toIntOrNull()
                if (qty == null || qty < 1 || qty > 99) {
                    playerEntity.sendSystemMessage(tr("gui.create.qty.invalid"))
                    gui.close()
                    openAddQtyPage(shop, stack, totalCount, source)
                    return@setCallback
                }
                if (!isAdmin && qty > totalCount) {
                    playerEntity.sendSystemMessage(tr("gui.create.qty.not_enough", totalCount))
                    gui.close()
                    openAddQtyPage(shop, stack, totalCount, source)
                    return@setCallback
                }
                gui.close()
                openAddPricePage(shop, stack, qty, totalCount, source)
            }
        )
        gui.open()
    }

    // ── Page 7: enter price for new item ────────────────────────────────────

    private fun openAddPricePage(
        shop: ShopEntity,
        stack: ItemStack,
        qty: Int,
        totalCount: Int,
        source: MgContainerSource?
    ) {
        val isAdmin = shop.admin == 1
        val gui = anvilGui()
        gui.title = tr("gui.create.price.title")
        gui.setSlot(0, buildAnvilInputItem(stack, tr("gui.create.price.enter", qty))
            .also { b ->
                if (!isAdmin && totalCount > 0) b.addLoreLine(tr("gui.create.qty.available", totalCount))
            }
        )
        gui.setSlot(1, GuiElementBuilder(Items.GOLD_NUGGET)
            .setName(tr("gui.create.quick.price"))
            .addLoreLine(tr("gui.create.quick.left", "1"))
            .addLoreLine(tr("gui.create.quick.right", "10"))
            .setCallback { _, clickType, _, _ ->
                val quick = parseQuickValue(clickType, "1", "10")?.toDoubleOrNull() ?: return@setCallback
                gui.close()
                if (isAdmin) {
                    openAddStockPage(shop, stack, qty, quick)
                } else {
                    val taken = takeFromSource(source, stack, qty)
                    val newItem = buildItemManager(stack, qty, quick, taken) ?: return@setCallback
                    if (checkCanAddTradeOffer(shop, newItem, playerEntity)) {
                        shop.addTradeOffer(newItem, playerEntity)
                    }
                    openItemManagePage(shop)
                }
            }
        )
        gui.setSlot(2, GuiElementBuilder(Items.LIME_DYE)
            .setName(tr("gui.create.price.confirm"))
            .setCallback { _, clickType, _, _ ->
                if (clickType != ClickType.MOUSE_LEFT && clickType != ClickType.MOUSE_RIGHT) return@setCallback
                val price = gui.input.trim().toDoubleOrNull()
                if (price == null || price < 0.1) {
                    playerEntity.sendSystemMessage(tr("gui.create.price.invalid"))
                    gui.close()
                    openAddPricePage(shop, stack, qty, totalCount, source)
                    return@setCallback
                }
                gui.close()
                if (isAdmin) {
                    openAddStockPage(shop, stack, qty, price)
                } else {
                    val taken = takeFromSource(source, stack, qty)
                    val newItem = buildItemManager(stack, qty, price, taken) ?: return@setCallback
                    if (checkCanAddTradeOffer(shop, newItem, playerEntity)) {
                        shop.addTradeOffer(newItem, playerEntity)
                    }
                    openItemManagePage(shop)
                }
            }
        )
        gui.open()
    }

    // ── Page 7b (admin only): enter initial stock ────────────────────────────

    private fun openAddStockPage(
        shop: ShopEntity,
        stack: ItemStack,
        qty: Int,
        price: Double
    ) {
        val gui = anvilGui()
        gui.title = tr("gui.create.stock.title")
        gui.setSlot(0, buildAnvilInputItem(stack, tr("gui.create.stock.enter")))
        gui.setSlot(1, GuiElementBuilder(Items.CHEST)
            .setName(tr("gui.create.quick.stock"))
            .addLoreLine(tr("gui.create.quick.left", "0"))
            .addLoreLine(tr("gui.create.quick.right", "64"))
            .setCallback { _, clickType, _, _ ->
                val quick = parseQuickValue(clickType, "0", "64")?.toIntOrNull() ?: return@setCallback
                val newItem = buildItemManager(stack, qty, price, quick) ?: return@setCallback
                if (checkCanAddTradeOffer(shop, newItem, playerEntity)) {
                    shop.addTradeOffer(newItem, playerEntity)
                }
                gui.close()
                openItemManagePage(shop)
            }
        )
        gui.setSlot(2, GuiElementBuilder(Items.LIME_DYE)
            .setName(tr("gui.create.stock.confirm"))
            .setCallback { _, clickType, _, _ ->
                if (clickType != ClickType.MOUSE_LEFT && clickType != ClickType.MOUSE_RIGHT) return@setCallback
                val stock = gui.input.trim().toIntOrNull()
                if (stock == null || stock < 0) {
                    playerEntity.sendSystemMessage(tr("gui.create.stock.invalid"))
                    gui.close()
                    openAddStockPage(shop, stack, qty, price)
                    return@setCallback
                }
                val newItem = buildItemManager(stack, qty, price, stock) ?: return@setCallback
                if (checkCanAddTradeOffer(shop, newItem, playerEntity)) {
                    shop.addTradeOffer(newItem, playerEntity)
                }
                gui.close()
                openItemManagePage(shop)
            }
        )
        gui.open()
    }

    // ── Page 8: delete shop confirmation ─────────────────────────────────────

    private fun openDeleteConfirmPage(shop: ShopEntity) {
        val gui = simpleGui(MenuType.GENERIC_9x3)
        gui.title = tr("gui.manage.delete.title", shop.shopname)
        border(gui, 3)

        gui.setSlot(13, GuiElementBuilder(Items.TNT)
            .setName(Component.literal(shop.shopname))
            .addLoreLine(tr("gui.manage.delete.warn"))
            .addLoreLine(tr("gui.manage.delete.warn2"))
        )
        gui.setSlot(11, GuiElementBuilder(Items.LIME_DYE)
            .setName(tr("gui.manage.delete.confirm"))
            .setCallback { _, _, _, _ ->
                customScope.launch {
                    shopEntityList.getOrDefault(shop.id, null)?.discard()
                    synchronized(shopEntityList) { shopEntityList.remove(shop.id) }
                    shop.deleteAsync()
                    playerEntity.level().server.execute {
                        offerItemToPlayer(playerEntity, shop.items)
                        playerEntity.sendSystemMessage(tr("gui.manage.delete.success", shop.shopname))
                        gui.close()
                        openShopListPage()
                    }
                }
            }
        )
        gui.setSlot(15, GuiElementBuilder(Items.RED_DYE)
            .setName(tr("gui.manage.delete.cancel"))
            .setCallback { _, _, _, _ -> gui.close(); openShopMainPage(shop) }
        )
        gui.open()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun sourceLore(source: MgContainerSource): Component = when (source) {
        is MgContainerSource.InventorySlot -> tr("gui.create.container.source.inv")
        is MgContainerSource.WorldBlock    -> tr("gui.create.container.source.world",
            source.pos.x, source.pos.y, source.pos.z)
        is MgContainerSource.EnderChest    -> tr("gui.create.container.source.ender")
    }

    private fun stackKey(stack: ItemStack): String =
        BuiltInRegistries.ITEM.getId(stack.item).toString() + "|" + stack.components.toString()

    private fun countItemInPlayerInventory(target: ItemStack): Int {
        val inv = playerEntity.inventory
        var total = 0
        for (slot in 0 until inv.containerSize) {
            val stack = inv.getItem(slot)
            if (stack.isEmpty) continue
            if (stack.item == target.item && stack.components == target.components) total += stack.count
        }
        return total
    }

    private fun countAvailableFromSource(target: ItemStack, source: MgContainerSource?): Int = when (source) {
        null -> countItemInPlayerInventory(target)
        is MgContainerSource.InventorySlot -> {
            val live = playerEntity.inventory.getItem(source.invSlot)
            if (live.isEmpty) 0
            else getItemsInsideContainerStack(live)[stackKey(target)]?.second ?: 0
        }
        is MgContainerSource.WorldBlock ->
            findShulkerBoxesInWorld(playerEntity.level(), source.pos, 0)
                .firstOrNull()?.second?.get(stackKey(target))?.second ?: 0
        is MgContainerSource.EnderChest -> ItemManager.getItemsInEnderChest(playerEntity)[stackKey(target)]?.second ?: 0
    }

    private fun removeFromSelectedSource(source: MgContainerSource?, stack: ItemStack, qty: Int): Int {
        val single = stack.copyWithCount(1)
        return when (source) {
            null -> removeItemFromInventory(playerEntity, single, qty)
            is MgContainerSource.InventorySlot -> removeItemFromShulkerBoxesInInventory(playerEntity, single, qty)
            is MgContainerSource.WorldBlock -> removeItemFromShulkerBoxBlockEntity(playerEntity.level(), source.pos, single, qty)
            is MgContainerSource.EnderChest -> ItemManager.removeItemFromEnderChest(playerEntity, single, qty)
        }
    }

    private fun takeFromSource(source: MgContainerSource?, stack: ItemStack, qty: Int): Int {
        val single = stack.copyWithCount(1)
        if (source == null) return removeItemFromInventory(playerEntity, single, qty)
        val fromContainer = when (source) {
            is MgContainerSource.InventorySlot -> removeItemFromShulkerBoxesInInventory(playerEntity, single, qty)
            is MgContainerSource.WorldBlock    -> removeItemFromShulkerBoxBlockEntity(playerEntity.level(), source.pos, single, qty)
            is MgContainerSource.EnderChest    -> ItemManager.removeItemFromEnderChest(playerEntity, single, qty)
        }
        return fromContainer + if (fromContainer < qty)
            removeItemFromInventory(playerEntity, single, qty - fromContainer) else 0
    }

    private fun buildItemManager(stack: ItemStack, qty: Int, price: Double, stock: Int): ItemManager? {
        return try {
            val entry = BuiltInRegistries.ITEM.wrapAsHolder(stack.item)
            val tradedItem = ItemCost(
                entry, qty,
                DataComponentExactPredicate.allOf(stack.copyWithCount(qty).components),
                stack.copyWithCount(qty)
            )
            ItemManager(tradedItem, qty, price, mutableMapOf("default" to stock), registries)
        } catch (_: Exception) {
            playerEntity.sendSystemMessage(tr("gui.create.item.error"))
            null
        }
    }

    private fun notifyNameCommandHintIfNeeded(name: String) {
        if (name.contains(' ') || name.any { it.code > 127 }) {
            playerEntity.sendSystemMessage(tr("commands.shop.name.quote_hint"))
        }
    }

    private fun addGui(player: ServerPlayer) = VillagerShopMain.guiSet.add(player)
    private fun removeGui(player: ServerPlayer) = VillagerShopMain.guiSet.remove(player)
}





