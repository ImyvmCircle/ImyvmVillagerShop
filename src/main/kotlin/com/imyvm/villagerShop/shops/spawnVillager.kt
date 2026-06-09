package com.imyvm.villagerShop.shops

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.npc.villager.Villager

fun spawnInvulnerableVillager(
    pos: BlockPos, world: ServerLevel,
    shopName: String,
    type: Int = 0,
    id: Int
): Villager {
    val villager = Villager(EntityType.VILLAGER, world)
    villager.setPos(
        pos.x.toDouble() + if (pos.x >= 0) 0.5 else -0.5,
        pos.y.toDouble() + 1,
        pos.z.toDouble() + if (pos.z >= 0) 0.5 else -0.5
    )
    villager.isInvulnerable = true
    villager.age = -1
    villager.isBaby = false
    villager.horizontalCollision = false
    villager.addTag("VillagerShop")
    villager.addTag("id:${id}")
    villager.addTag("type:${type}")
    villager.customName = Component.literal(shopName)
    world.addFreshEntity(villager)
    return villager
}