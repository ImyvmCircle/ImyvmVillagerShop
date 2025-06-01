package com.imyvm.villagerShop.shops

import net.minecraft.entity.EntityType
import net.minecraft.entity.passive.VillagerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos

fun spawnInvulnerableVillager(
    pos: BlockPos, world: ServerWorld,
    shopName: String,
    type: Int = 0,
    id: Int
) {
    val villager = VillagerEntity(EntityType.VILLAGER, world)
    villager.setPos(pos.x.toDouble(), pos.y.toDouble() + 1, pos.z.toDouble())
    villager.isInvulnerable = true
    villager.breedingAge = -1
    villager.isBaby = false
    villager.horizontalCollision = false
    villager.addCommandTag("VillagerShop")
    villager.addCommandTag("id:${id}")
    villager.addCommandTag("type:${type}")
    villager.customName = Text.of(shopName)
    world.spawnEntity(villager)
}